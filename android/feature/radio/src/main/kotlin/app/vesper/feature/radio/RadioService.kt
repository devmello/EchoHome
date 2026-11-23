package app.vesper.feature.radio

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.core.model.RadioControl
import app.vesper.core.model.RadioNowPlaying
import app.vesper.core.model.RadioPlayResult
import app.vesper.core.model.VesperServices
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The radio: [RadioControl] for the assistant, the state the Radio screen draws, and the media
 * source "radio" on [services.media] (Now Playing, the island and Control Center show it as an
 * app source labelled with the station, with Stop / play and next / previous = the next favourite).
 *
 * Nothing starts by itself: only [playStation] (a tap) and [play] (a voice request) open a stream.
 * One source at a time: audio focus plus [RadioArbitration]. Main thread.
 */
class RadioService(
    private val context: Context,
    private val services: VesperServices,
) : RadioControl, RadioPlayer.Listener {

    data class Ui(
        val station: Station? = null,
        val state: RadioPlayer.State = RadioPlayer.State.IDLE,
        /** The current song from the stream ("Artist - Title"), if it sends one. */
        val song: String? = null,
        val error: String? = null,
        val bitrate: Int = 0,
    ) {
        val on get() = state == RadioPlayer.State.CONNECTING || state == RadioPlayer.State.PLAYING
    }

    private val http = RadioBrowser.defaultClient()
    val browser = RadioBrowser(http)
    val art = RadioArt(http)
    val library = RadioLibrary(File(context.filesDir, "radio.json"))
    private val player = RadioPlayer(context, http, this)
    private val main = Handler(Looper.getMainLooper())
    private val am = context.getSystemService(AudioManager::class.java)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private val _now = MutableStateFlow<RadioNowPlaying?>(null)
    override val nowPlaying: StateFlow<RadioNowPlaying?> = _now.asStateFlow()

    private var cover: Bitmap? = null
    private var coverFor: String? = null
    private var startedAt = 0L
    private var stoppedPosition = 0L
    private var focus: AudioFocusRequest? = null
    private var othersPlaying: Set<String> = emptySet()
    private var demoSong = 0

    private val demoTick = object : Runnable {
        override fun run() {
            val u = _ui.value
            if (u.station?.isDemo != true || !u.on) return
            demoSong = (demoSong + 1) % DemoStations.SONGS.size
            update { it.copy(song = DemoStations.SONGS[demoSong]) }
            main.postDelayed(this, DEMO_SONG_MS)
        }
    }
    /** A stopped session leaves the hub after a while (Now Playing / the island stop offering it). */
    private val clearIdle = Runnable { if (!_ui.value.on) { services.media.clear(SOURCE_ID); update { Ui() } } }

    /** After every feature is installed (reads the hub). */
    fun start() {
        services.scope.launch {
            services.media.sessions.collect { list ->
                val now = RadioArbitration.othersPlaying(list, SOURCE_ID)
                if (RadioArbitration.shouldStopRadio(othersPlaying, now, _ui.value.on)) {
                    Log.i(TAG, "${(now - othersPlaying).joinToString()} started: stopping the radio")
                    pause()
                }
                othersPlaying = now
            }
        }
    }

    // ---------------------------------------------------------------- RadioControl (voice)

    override suspend fun play(query: String): RadioPlayResult {
        val q = query.trim()
        val s = try { resolve(q) } catch (e: IOException) {
            return RadioPlayResult(false, error = "OFFLINE", message = "Couldn't reach the radio directory (radio-browser.info): ${e.message}")
        } ?: return if (RadioQuery.wantsFavourite(q)) RadioPlayResult(false, error = "NO_FAVOURITES", message = "There are no favourite or recent stations yet. Ask for a station or genre by name.")
            else RadioPlayResult(false, error = "NO_MATCH", message = "No station found for \"${q.take(60)}\". Try a station name or a genre like jazz or news.")
        playStation(s)
        return RadioPlayResult(true, s.name, listOf(s.genre, s.country, s.format).filter { it.isNotBlank() }.joinToString(" · "))
    }

    override fun stop(): Boolean {
        val was = _ui.value.on
        pause()
        return was
    }

    /** Favourites / recent first, then the directory: by name, then as a genre, then by country. */
    private suspend fun resolve(q: String): Station? {
        if (q.isEmpty() || RadioQuery.wantsFavourite(q)) return library.favourites.value.firstOrNull() ?: library.recent.value.firstOrNull()
            ?: if (services.config.demoData) DemoStations.ALL.first() else null
        library.match(q)?.let { return it }
        if (services.config.demoData) DemoStations.search(q).firstOrNull()?.let { return it }
        browser.search(RadioBrowser.Query(name = q, limit = 10)).firstOrNull()?.let { return it }
        val tag = RadioQuery.tagOf(q)
        val country = RadioQuery.countryCode(q)
        if (country != null) {
            val rest = RadioQuery.tagOf(q.lowercase().replace(Regex("\\b(from|in)\\b"), " ")).split(' ')
                .filter { w -> RadioQuery.countryCode(w) != country }.joinToString(" ").trim()
            browser.search(RadioBrowser.Query(tag = rest.ifEmpty { null }, countryCode = country, limit = 10)).firstOrNull()?.let { return it }
        }
        if (tag.isNotEmpty()) browser.search(RadioBrowser.Query(tag = tag, limit = 10)).firstOrNull()?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- playback

    /** A person picked [s] (tap, voice, next favourite). */
    fun playStation(s: Station) {
        main.removeCallbacks(clearIdle)
        main.removeCallbacks(demoTick)
        val sameStation = _ui.value.station?.uuid == s.uuid
        loadCover(s)
        update { Ui(station = s, state = RadioPlayer.State.CONNECTING, song = if (sameStation) it.song else null) }
        startedAt = SystemClock.elapsedRealtime()
        requestFocus()
        pauseOthers()
        library.played(s)
        if (s.isDemo) {
            // Sample station: silent, no network. Looks like a playing stream for reviews.
            player.stop()
            demoSong = (DemoStations.ALL.indexOf(s).coerceAtLeast(0)) % DemoStations.SONGS.size
            update { it.copy(state = RadioPlayer.State.PLAYING, song = DemoStations.SONGS[demoSong]) }
            main.postDelayed(demoTick, DEMO_SONG_MS)
            return
        }
        player.play(s)
        if (RadioSettings.reportPlays(services.settings)) services.scope.launch { browser.reportPlay(s.uuid) }
    }

    /** Stop the stream, keep the station (Now Playing's play resumes it). */
    fun pause() {
        main.removeCallbacks(demoTick)
        if (_ui.value.station == null) return
        stoppedPosition = position()
        player.stop()
        abandonFocus()
        update { it.copy(state = RadioPlayer.State.IDLE) }
        main.removeCallbacks(clearIdle)
        main.postDelayed(clearIdle, IDLE_CLEAR_MS)
    }

    fun resume() { _ui.value.station?.let(::playStation) }

    fun toggle() { if (_ui.value.on) pause() else resume() }

    /** Now Playing's Stop / Disconnect: off and out of the hub. */
    fun disconnect() {
        pause()
        main.removeCallbacks(clearIdle)
        services.media.clear(SOURCE_ID)
        update { Ui() }
    }

    /** Next / previous favourite (or recent station) to the current one. */
    fun skip(dir: Int) {
        val list = skipList()
        if (list.size < 2) return
        val i = list.indexOfFirst { it.uuid == _ui.value.station?.uuid }
        val next = list[((if (i < 0) 0 else i + dir) % list.size + list.size) % list.size]
        playStation(next)
    }

    private fun skipList(): List<Station> {
        val fav = library.favourites.value
        val cur = _ui.value.station?.uuid
        return if (fav.size >= 2 && (cur == null || fav.any { it.uuid == cur })) fav
            else if (cur != null && cur.startsWith("demo-")) DemoStations.ALL
            else fav.ifEmpty { library.recent.value }
    }

    private fun pauseOthers() {
        val others = RadioArbitration.toPause(services.media.sessions.value, SOURCE_ID)
        if (others.isNotEmpty()) {
            Log.i(TAG, "radio started: pausing ${others.joinToString { it.sourceId }}")
            others.forEach { it.controls.pause() }
        }
    }

    private fun position(): Long = if (_ui.value.on) SystemClock.elapsedRealtime() - startedAt else stoppedPosition

    // ---------------------------------------------------------------- player callbacks

    override fun onState(state: RadioPlayer.State, error: String?) {
        if (_ui.value.station?.isDemo == true) return
        update { it.copy(state = state, error = error) }
        if (state == RadioPlayer.State.ERROR) {
            abandonFocus()
            main.removeCallbacks(clearIdle)
            main.postDelayed(clearIdle, IDLE_CLEAR_MS)
        }
    }

    override fun onTitle(title: String?) { update { it.copy(song = title) } }

    override fun onBitrate(kbps: Int) { if (_ui.value.station?.bitrate == 0) update { it.copy(bitrate = kbps) } }

    // ---------------------------------------------------------------- state → hub

    private inline fun update(f: (Ui) -> Ui) {
        val old = _ui.value
        val next = f(old)
        if (next == old) return
        _ui.value = next
        publish(next)
    }

    private fun publish(u: Ui) {
        val s = u.station
        if (s == null) { _now.value = null; return }
        _now.value = RadioNowPlaying(s.name, u.song, u.on)
        val (artist, title) = u.song?.let(IcyMeta::split) ?: (null to null)
        val format = s.copy(bitrate = if (s.bitrate > 0) s.bitrate else u.bitrate).format
        val state = MediaState(
            title = title ?: s.name,
            artist = artist ?: if (title != null) s.name else s.subtitle.ifEmpty { "Live radio" },
            album = "",
            artwork = cover?.takeIf { coverFor == s.uuid },
            durationMs = 0,
            positionMs = position(),
            positionSampledAt = SystemClock.elapsedRealtime(),
            playing = u.on,
            source = MediaSourceKind.OTHER_APP,
            sourceLabel = s.name,
            route = listOfNotNull("Internet radio", format.ifEmpty { null }, s.countryCode.ifEmpty { null }),
        )
        services.media.publish(SOURCE_ID, state, controls)
    }

    private fun loadCover(s: Station) {
        if (coverFor == s.uuid && cover != null) return
        coverFor = s.uuid
        cover = RadioArt.cover(s, art.cachedIcon(s))
        if (s.favicon.isNotEmpty() && art.cachedIcon(s) == null) services.scope.launch {
            val icon = art.icon(s) ?: return@launch
            val c = withContext(Dispatchers.Default) { RadioArt.cover(s, icon) }
            if (coverFor == s.uuid) { cover = c; publish(_ui.value) }
        }
    }

    private val controls = object : MediaControls {
        override fun play() = resume()
        override fun pause() = this@RadioService.pause()
        override fun next() = skip(1)
        override fun previous() = skip(-1)
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
        override val canSeek get() = false
        override val canSkip get() = skipList().size >= 2
        override fun disconnect() = this@RadioService.disconnect()
    }

    // ---------------------------------------------------------------- audio focus

    private fun requestFocus() {
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            // MediaPlayer is ducked by the framework (assistant speaking, notifications).
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(::onFocusChange, main)
            .build().also { focus = it }
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) Log.i(TAG, "audio focus denied (a call or an alarm?); playing anyway")
    }

    private fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) } }

    private fun onFocusChange(change: Int) {
        when (change) {
            // Another source took over for good (AirPlay, Spotify, an app): stop.
            AudioManager.AUDIOFOCUS_LOSS -> { Log.i(TAG, "audio focus lost: stopping"); pause() }
            // An alarm or a call: silent until it's over, stream kept open.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.setGain(0f)
            AudioManager.AUDIOFOCUS_GAIN -> player.setGain(1f)
        }
    }

    companion object {
        const val TAG = RadioBrowser.TAG
        const val SOURCE_ID = "radio"
        const val DEMO_SONG_MS = 45_000L
        const val IDLE_CLEAR_MS = 30 * 60_000L
    }
}
