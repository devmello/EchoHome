package app.vesper.feature.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.Banner
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSession
import app.vesper.core.model.VesperServices
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Spotify Connect receiver as a foreground service: runs the native receiver (librespot),
 * advertises it with NsdManager, plays its PCM with [SpotifyAudio], and publishes what's playing
 * into `services.media` (Now Playing, the music island and Control Center read the hub).
 *
 * Every native event is posted to one [HandlerThread] ("spotify-events"), which owns all state.
 * Idle (signed in or not, nothing playing), the only threads are librespot's two parked tokio
 * workers, its player thread, our audio thread polling twice a second, and the event threads.
 */
class SpotifyService : Service() {

    private lateinit var events: HandlerThread
    private lateinit var h: Handler
    private lateinit var services: VesperServices
    private lateinit var nsd: SpotifyNsd
    private lateinit var am: AudioManager
    private val artFetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "spotify-art") }
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Owned by the events thread.
    @Volatile private var handle = 0L
    @Volatile private var audio: SpotifyAudio? = null
    @Volatile private var state = SpotifyState()
    private var artwork: Bitmap? = null
    private var publishedMedia = false
    private var name = ""
    private var bitrate = SpotifyFeature.DEFAULT_BITRATE
    private var libMdns = false
    private var port = 0
    private var focusRequest: AudioFocusRequest? = null
    private var focus = SpotifyFocus()
    private var volumeSync: VolumeSync? = null
    private var speakerVolume: Float? = null
    private var othersPlaying: Set<String> = emptySet()
    /** PHASE_FAILED in a row (reset by playback); see [MAX_NATIVE_FAILURES]. */
    private var nativeFailures = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debugReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        val s = SpotifyFeature.services
        if (s == null) {
            Log.e(TAG, "SpotifyFeature not installed; stopping")
            stopSelf(); return
        }
        services = s
        am = getSystemService(AudioManager::class.java)
        events = HandlerThread("spotify-events").apply { start() }
        h = Handler(events.looper)
        nsd = SpotifyNsd(this)
        SpotifyFeature.signOut = { h.post { command(NativeBridge.CMD_FORGET) } }
        h.post { startReceiver() }
        mainScope.launch {
            services.settings.changes.collect { key ->
                // On / off (spotify.enabled, system.cc.spotify) is SpotifyFeature's job: it stops us.
                if (key in SpotifyFeature.RESTART_KEYS) h.post { restartReceiver("settings changed ($key)") }
            }
        }
        // The Echo's volume (keys, Control Center, Now Playing's capsule) goes back to Spotify.
        mainScope.launch {
            services.system.state.map { it.volume }.distinctUntilChanged().collect { v -> h.post { localVolume(v) } }
        }
        // AirPlay / Bluetooth starting while Spotify plays: pause Spotify (see Arbitration).
        mainScope.launch {
            services.media.sessions.collect { list -> h.post { arbitrate(list) } }
        }
        watchNetwork()
        if (services.config.debug) registerDebugReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        SpotifyFeature.signOut = null
        mainScope.cancel()
        if (::h.isInitialized) {
            h.post { stopReceiver() }
            events.quitSafely()
            events.join(8_000)
        }
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        debugReceiver?.let { unregisterReceiver(it) }
        artFetcher.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------
    // Receiver lifecycle (events thread)
    // ------------------------------------------------------------------------------------------

    private val markStable = Runnable {
        services.settings.putInt(SpotifyFeature.KEY_START_ATTEMPTS, 0)
    }

    private fun startReceiver() {
        if (handle != 0L) return
        val settings = services.settings
        name = SpotifyFeature.name(settings)
        if (!NativeBridge.load()) {
            Log.e(TAG, "libvesper_spotify.so missing (armeabi-v7a only); Spotify Connect off")
            SpotifyFeature.setStatus("Not available on this device (the receiver library is missing).")
            return
        }
        val attempts = settings.getInt(SpotifyFeature.KEY_START_ATTEMPTS, 0)
        if (!CrashGuard.allowed(attempts)) {
            Log.e(TAG, "receiver didn't survive $attempts starts in a row; staying off until it's switched on again")
            SpotifyFeature.setStatus(SpotifyStatus.text(null, null, name, failedStarts = true))
            return
        }
        // Counted before the native call: if it takes the process down, the next start knows.
        settings.putInt(SpotifyFeature.KEY_START_ATTEMPTS, attempts + 1)
        bitrate = SpotifyFeature.bitrate(settings)
        libMdns = settings.getString(SpotifyFeature.KEY_MDNS, SpotifyFeature.MDNS_NSD) == SpotifyFeature.MDNS_LIB
        // The cached login blob: no-backup storage, never in backups or git.
        val credentials = File(noBackupFilesDir, "spotify").apply { mkdirs() }
        // librespot streams tracks into temp files; start clean (a killed process leaves them).
        val tmp = File(cacheDir, "spotify-tmp").apply { deleteRecursively(); mkdirs() }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volumeSync = VolumeSync(max)
        val initial = SpotifyVolume.fromFraction(am.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
        val started = SystemClock.elapsedRealtime()
        handle = startNative(name, deviceId(), bitrate, libMdns, credentials.path, tmp.path, initial)
        if (handle == 0L) {
            settings.putInt(SpotifyFeature.KEY_START_ATTEMPTS, attempts) // it failed cleanly: not a crash
            SpotifyFeature.setStatus("Couldn't start. Retrying in ${RETRY_MS / 1000} s.")
            h.postDelayed({ startReceiver() }, RETRY_MS)
            return
        }
        port = NativeBridge.nativePort(handle)
        if (libMdns) setMulticastLock(true) else nsd.register(name, port)
        audio = SpotifyAudio(handle).also { it.start() }
        h.postDelayed(markStable, CrashGuard.STABLE_MS)
        Log.i(TAG, "receiver up as \"$name\" ($bitrate kbps, mDNS ${if (libMdns) "library" else "NsdManager"}) " +
            "port $port in ${SystemClock.elapsedRealtime() - started} ms")
        updateStatus()
    }

    private fun startNative(name: String, id: String, kbps: Int, lib: Boolean, cred: String, tmp: String, volume: Int): Long {
        for (p in intArrayOf(PORT, 0)) {
            try {
                return NativeBridge.nativeStart(name, id, p, kbps, lib, cred, tmp, volume, listener)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "receiver failed to start on port $p: ${e.message}")
            }
        }
        return 0L
    }

    private fun stopReceiver() {
        h.removeCallbacks(markStable)
        endPlayback()
        nsd.unregister()
        setMulticastLock(false)
        val hd = handle
        if (hd != 0L) {
            handle = 0L
            // The audio thread reads through the handle: it must be gone before the handle is freed.
            audio?.let { it.stop(); it.join(SpotifyAudio.POLL_MS + 1_000L) }
            NativeBridge.nativeStop(hd) // joins the native threads (≤ ~6 s)
            // A clean stop isn't a crash.
            services.settings.putInt(SpotifyFeature.KEY_START_ATTEMPTS, 0)
        }
        audio = null
        state = SpotifyState()
        artwork = null
        publish()
    }

    private fun restartReceiver(why: String) {
        Log.i(TAG, "$why: restarting receiver")
        stopReceiver()
        startReceiver()
    }

    private fun command(cmd: Int, arg: Long = 0): Boolean {
        val hd = handle
        return hd != 0L && NativeBridge.nativeCommand(hd, cmd, arg)
    }

    /** Stable per-device id (Spotify remembers devices by it): 40 hex characters. */
    private fun deviceId(): String {
        val settings = services.settings
        val saved = settings.getString(SpotifyFeature.KEY_DEVICE_ID, "")
        if (saved.length == 40 && saved.all { it in '0'..'9' || it in 'a'..'f' }) return saved
        val b = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        settings.putString(SpotifyFeature.KEY_DEVICE_ID, id)
        return id
    }

    // ------------------------------------------------------------------------------------------
    // Native events → state (events thread)
    // ------------------------------------------------------------------------------------------

    private val listener = object : NativeBridge.Listener {
        override fun onState(phase: Int, detail: String) { h.post { phase(phase, detail) } }
        override fun onClient(name: String) { h.post { update { SpotifyReducer.client(it, name) } } }
        override fun onTrack(title: String, artist: String, album: String, durationMs: Long, coverUrl: String) {
            h.post {
                Log.i(TAG, "track: \"$title\" / \"$artist\" / \"$album\" ($durationMs ms)")
                update { SpotifyReducer.track(it, title, artist, album, durationMs, coverUrl, now()) }
                if (state.coverUrl != state.artworkUrl) {
                    artwork = null
                    state.coverUrl?.let(::fetchArt)
                }
            }
        }
        override fun onPlayback(state: Int, positionMs: Long) { h.post { playback(state, positionMs) } }
        override fun onPosition(positionMs: Long) { h.post { update { SpotifyReducer.position(it, positionMs, now()) } } }
        override fun onStopped() {
            h.post {
                Log.i(TAG, "playback stopped (another device, or the session ended)")
                update { SpotifyReducer.stopped(it) }
                artwork = null
                endPlayback()
            }
        }
        override fun onVolume(volume: Int) { h.post { remoteVolume(volume) } }
        override fun onSink(open: Boolean) {
            if (!open) audio?.pauseNow() // at once, not after the event queue
            h.post {
                if (open) { if (!focus.hasFocus) requestFocus(); acquireStreamLocks() } else releaseStreamLocks()
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private inline fun update(transform: (SpotifyState) -> SpotifyState) {
        val next = transform(state)
        if (next != state) {
            state = next
            publish()
        }
    }

    private fun phase(phase: Int, detail: String) {
        val before = state.phase
        update { SpotifyReducer.phase(it, phase) }
        if (phase != before) Log.i(TAG, "phase ${phaseName(before)} → ${phaseName(phase)}" + if (detail.isNotBlank()) " ($detail)" else "")
        if (phase == NativeBridge.PHASE_PREMIUM_REQUIRED && before != phase) {
            artwork = null
            endPlayback()
            services.notifications.post(Banner(
                id = PREMIUM_BANNER_ID,
                appName = "Spotify",
                appColor = SPOTIFY_GREEN,
                glyph = "music",
                title = "Spotify Premium is needed",
                body = "Spotify Connect on EchoHome works with Premium accounts only. Pick “$name” again from a Premium account.",
            ))
        }
        if (phase == NativeBridge.PHASE_FAILED) {
            nativeFailures++
            if (nativeFailures <= MAX_NATIVE_FAILURES) {
                Log.w(TAG, "native receiver failed ($nativeFailures); restarting in ${RETRY_MS / 1000} s")
                h.postDelayed({ restartReceiver("native failure") }, RETRY_MS)
            } else {
                Log.e(TAG, "native receiver failed $nativeFailures times; staying off")
                h.post { stopReceiver() }
                SpotifyFeature.setStatus(SpotifyStatus.text(null, null, name, failedStarts = true))
                return
            }
        }
        updateStatus()
    }

    private fun playback(st: Int, positionMs: Long) {
        val wasPlaying = state.playing
        update { SpotifyReducer.playback(it, st, positionMs, now()) }
        if (state.playing) nativeFailures = 0
        if (state.playing && !wasPlaying) {
            if (!focus.hasFocus) requestFocus()
            // Spotify started: AirPlay / the Bluetooth speaker step aside.
            val others = Arbitration.toPauseWhenSpotifyStarts(services.media.sessions.value, SOURCE_ID)
            if (others.isNotEmpty()) {
                Log.i(TAG, "Spotify started: pausing ${others.joinToString { it.sourceId }}")
                mainScope.launch { others.forEach { it.controls.pause() } }
            }
        }
    }

    private fun arbitrate(sessions: List<MediaSession>) {
        val now = Arbitration.othersPlaying(sessions, SOURCE_ID)
        if (Arbitration.shouldPauseSpotify(othersPlaying, now, state.playing)) {
            Log.i(TAG, "${(now - othersPlaying).joinToString()} started: pausing Spotify")
            command(NativeBridge.CMD_PAUSE)
        }
        othersPlaying = now
    }

    private fun updateStatus() {
        SpotifyFeature.setStatus(SpotifyStatus.text(state.phase, state.client, name))
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state.client.takeIf { state.loaded }))
    }

    // ------------------------------------------------------------------------------------------
    // Cover art
    // ------------------------------------------------------------------------------------------

    private fun fetchArt(url: String) {
        artFetcher.execute {
            val bmp = try { downloadArt(url) } catch (e: Exception) { Log.w(TAG, "cover art: ${e.javaClass.simpleName}"); null }
            h.post {
                if (state.coverUrl == url && bmp != null) {
                    artwork = bmp
                    update { SpotifyReducer.artwork(it, url) }
                }
            }
        }
    }

    private fun downloadArt(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        try {
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > ART_MAX_BYTES) return null
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ARTWORK_MAX_PX) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Publishing into services.media
    // ------------------------------------------------------------------------------------------

    private fun publish() {
        val media = state.toMediaState(artwork, speakerVolume, bitrate)
        if (media != null) {
            services.media.publish(SOURCE_ID, media, controls)
            publishedMedia = true
        } else if (publishedMedia) {
            services.media.clear(SOURCE_ID)
            publishedMedia = false
            Log.i(TAG, "hub: cleared")
        }
    }

    private val controls = object : MediaControls {
        override fun play() { h.post { command(NativeBridge.CMD_PLAY) } }
        override fun pause() { h.post { command(NativeBridge.CMD_PAUSE) } }
        override fun next() { h.post { command(NativeBridge.CMD_NEXT) } }
        override fun previous() { h.post { command(NativeBridge.CMD_PREVIOUS) } }
        override fun seekTo(positionMs: Long) {
            h.post {
                if (command(NativeBridge.CMD_SEEK, positionMs.coerceAtLeast(0))) update { SpotifyReducer.position(it, positionMs, now()) }
            }
        }
        /** The Echo's speaker is Spotify's volume: set it, and [localVolume] tells Spotify. */
        override fun setVolume(volume: Float) {
            h.post {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val index = (volume.coerceIn(0f, 1f) * max).roundToInt()
                try { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) } catch (e: SecurityException) { Log.w(TAG, "volume: $e") }
            }
        }
        override val canSeek: Boolean get() = true
        override val canSkip: Boolean get() = true
        override val canPause: Boolean get() = true
        /** Leaves the Spotify session; the Spotify app shows nothing playing on the Echo. */
        override fun disconnect() { h.post { command(NativeBridge.CMD_DISCONNECT) } }
    }

    // ------------------------------------------------------------------------------------------
    // Volume
    // ------------------------------------------------------------------------------------------

    /** The Spotify app's slider moved: set the Echo's speaker (the island / HUD shows it). */
    private fun remoteVolume(volume: Int) {
        if (state.phase != NativeBridge.PHASE_CONNECTED) return
        val sync = volumeSync ?: return
        val index = sync.fromSpotify(volume, am.getStreamVolume(AudioManager.STREAM_MUSIC)) ?: return
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) } catch (e: SecurityException) { Log.w(TAG, "volume: $e") }
    }

    /** The Echo's music volume changed (0..1): Now Playing shows it, and Spotify hears about it. */
    private fun localVolume(fraction: Float) {
        if (speakerVolume != fraction) {
            speakerVolume = fraction
            if (publishedMedia) publish()
        }
        val sync = volumeSync ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val out = sync.fromSpeaker((fraction * max).roundToInt()) ?: return
        command(NativeBridge.CMD_VOLUME, out.toLong())
    }

    // ------------------------------------------------------------------------------------------
    // Audio focus, locks
    // ------------------------------------------------------------------------------------------

    private fun requestFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            // We duck ourselves: the framework's auto-duck may not reach a native-fed AudioTrack.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(::onFocusChange, h)
            .build()
            .also { focusRequest = it }
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = SpotifyFocusPolicy.requested(focus, granted)
        audio?.setGain(SpotifyFocusPolicy.gain(focus))
        Log.i(TAG, "audio focus ${if (granted) "granted" else "denied"}")
    }

    private fun onFocusChange(change: Int) {
        val step = SpotifyFocusPolicy.change(focus, change, state.playing)
        focus = step.focus
        audio?.setGain(SpotifyFocusPolicy.gain(focus))
        if (step.pause) command(NativeBridge.CMD_PAUSE)
        if (step.play) command(NativeBridge.CMD_PLAY)
        Log.i(TAG, "audio focus change $change → ${if (step.pause) "paused" else if (step.play) "resumed" else if (focus.ducked) "ducked" else "unchanged"}")
    }

    private fun abandonFocus() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focus = SpotifyFocus()
        audio?.setGain(1f)
    }

    /** Nothing playing any more: give back focus and locks. */
    private fun endPlayback() {
        releaseStreamLocks()
        abandonFocus()
    }

    private fun acquireStreamLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vesper:spotify").apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Vesper:spotify").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseStreamLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    /** Only for the library's own mDNS responder (spotify.mdns = lib). */
    private fun setMulticastLock(on: Boolean) {
        val held = multicastLock
        if (on && held == null) {
            multicastLock = applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("Vesper:spotify").apply { setReferenceCounted(false); acquire() }
        } else if (!on && held != null) {
            if (held.isHeld) held.release()
            multicastLock = null
        }
    }

    /** Wi-Fi came back: re-announce (the daemon drops registrations) and sign in again at once. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var lost = false
            override fun onLost(network: Network) { lost = true }
            override fun onAvailable(network: Network) {
                if (!lost) return
                lost = false
                h.post {
                    if (handle == 0L) return@post
                    Log.i(TAG, "network back: re-announcing and reconnecting")
                    if (!libMdns) nsd.register(name, port)
                    command(NativeBridge.CMD_RECONNECT)
                }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        networkCallback = cb
    }

    // ------------------------------------------------------------------------------------------
    // Notification, debug hooks
    // ------------------------------------------------------------------------------------------

    private fun notification(client: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Spotify Connect receiver", NotificationManager.IMPORTANCE_MIN))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Spotify Connect")
            .setContentText(if (client != null) "Playing from $client" else "Ready")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * Debug builds only: `adb shell am broadcast -a app.vesper.spotify.DEBUG` with
     * `--es cmd play|pause|next|previous|disconnect|reconnect|forget`, `--ef volume 0.5`,
     * `--ez dump true` (logs state + hub to `VesperSpotify`).
     */
    private fun registerDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("cmd")) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.next()
                    "previous" -> controls.previous()
                    "disconnect" -> controls.disconnect()
                    "reconnect" -> h.post { command(NativeBridge.CMD_RECONNECT) }
                    "forget" -> h.post { command(NativeBridge.CMD_FORGET) }
                }
                if (intent.hasExtra("volume")) controls.setVolume(intent.getFloatExtra("volume", 0.5f))
                if (intent.getBooleanExtra("dump", false)) h.post {
                    Log.i(TAG, "dump: handle=${handle != 0L} port=$port phase=${phaseName(state.phase)} loaded=${state.loaded} " +
                        "playing=${state.playing} art=${artwork?.let { "${it.width}x${it.height}" }} focus=$focus " +
                        "hub=${services.media.current.value?.sourceId}")
                }
            }
        }
        registerReceiver(r, IntentFilter(DEBUG_ACTION))
        debugReceiver = r
    }

    companion object {
        private const val TAG = "VesperSpotify"
        const val SOURCE_ID = "spotify"
        private const val CHANNEL_ID = "spotify"
        private const val NOTIFICATION_ID = 0x5907
        private const val PREMIUM_BANNER_ID = "spotify.premium"
        private val SPOTIFY_GREEN = 0xFF1DB954.toInt()
        /** Spotify's own desktop app answers zeroconf on 4070; any free port if it's taken. */
        private const val PORT = 4070
        private const val RETRY_MS = 15_000L
        private const val MAX_NATIVE_FAILURES = 3
        /** Artwork is decoded to at most this size (Now Playing draws it well under 600 px). */
        private const val ARTWORK_MAX_PX = 600
        private const val ART_MAX_BYTES = 3 * 1024 * 1024
        const val DEBUG_ACTION = "app.vesper.spotify.DEBUG"

        fun phaseName(p: Int) = when (p) {
            NativeBridge.PHASE_IDLE -> "idle"
            NativeBridge.PHASE_CONNECTING -> "connecting"
            NativeBridge.PHASE_CONNECTED -> "connected"
            NativeBridge.PHASE_OFFLINE -> "offline"
            NativeBridge.PHASE_PREMIUM_REQUIRED -> "premium-required"
            NativeBridge.PHASE_LOGIN_FAILED -> "login-failed"
            NativeBridge.PHASE_FAILED -> "failed"
            else -> p.toString()
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SpotifyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SpotifyService::class.java))
        }
    }
}
