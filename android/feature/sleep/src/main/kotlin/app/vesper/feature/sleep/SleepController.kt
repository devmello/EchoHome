package app.vesper.feature.sleep

import android.graphics.Bitmap
import android.util.Log
import app.vesper.core.model.BedtimeReport
import app.vesper.core.model.BedtimeRequest
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.SleepState
import app.vesper.core.model.VesperServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One-shot timers on the main thread (a Handler in the app, manual in tests). */
internal interface SleepScheduler {
    /** Runs [r] at elapsedRealtime [atElapsed]; replaces the previous one. */
    fun at(atElapsed: Long, r: () -> Unit)
    fun cancel()
}

/**
 * `services.sleep`: the player state, the sleep timer, audio focus, arbitration with the other
 * sources, and the sleep sound as a session in `services.media` (Now Playing, island, Control
 * Center). Main thread. Never starts by itself: only [play] / [bedtime] (user actions) do.
 *
 * Paused (focus loss, another source, the pause button) keeps the sound loaded with the timer's
 * remaining time frozen; play resumes it. The timer stops the sound after its 30 s fade-out.
 */
internal class SleepController(
    private val services: () -> VesperServices,
    private val output: SleepOutput,
    private val focus: FocusGate,
    private val scheduler: SleepScheduler,
    private val elapsed: () -> Long,
    private val artwork: (SleepSound) -> Bitmap? = { null },
    private val scheduleWake: (Long) -> Unit = {},
    private val wall: () -> Long = System::currentTimeMillis,
) : SleepSounds {

    private val _state = MutableStateFlow(SleepState())
    override val state: StateFlow<SleepState> = _state.asStateFlow()

    private var focusState = SleepFocusPolicy.Focus()
    /** Timer time left while paused (ms); null = no timer. */
    private var remainingMs: Long? = null
    /** When the current timer was started, for Now Playing's progress (elapsed). */
    private var timerStartedAt = 0L
    private var othersBefore: Set<String> = emptySet()
    /** Timer time left while paused, for the screen. */
    val pausedRemainingMs: Long? get() = if (_state.value.playing) null else remainingMs
    /** Debug review only: publishes the playing state without any audio or focus. */
    private var silentPreview = false
    private var watchJob: Job? = null

    private val routine = BedtimeRoutine(services, { s, t -> play(s, t) }, scheduleWake, wall)

    init {
        output.onFinished = { onOutputFinished() }
    }

    /** Pause when another source starts playing (sources that start without taking focus). */
    fun watchHub() {
        val s = services()
        watchJob?.cancel()
        watchJob = s.scope.launch {
            s.media.sessions.collect { list ->
                val now = SleepArbitration.othersPlaying(list)
                if (SleepArbitration.shouldPause(othersBefore, now, _state.value.playing)) {
                    Log.i(SleepFeature.TAG, "another source started: pausing")
                    pause()
                }
                othersBefore = now
            }
        }
    }

    // ---------------- SleepSounds ----------------

    override fun play(sound: SleepSound?, timerMinutes: Int?): Boolean = start(sound, timerMinutes, silent = false)

    /** [silent] = debug preview: the UI, island and Now Playing show it, nothing plays. */
    fun start(sound: SleepSound?, timerMinutes: Int?, silent: Boolean): Boolean {
        val st = services().settings
        val snd = sound ?: _state.value.sound ?: SleepSettings.sound(st)
        val minutes = (timerMinutes ?: SleepSettings.timer(st)).coerceAtLeast(0)
        silentPreview = silent
        if (!silent && !takeFocus()) {
            Log.i(SleepFeature.TAG, "no audio focus: not starting")
            return false
        }
        othersBefore = SleepArbitration.othersPlaying(services().media.sessions.value)
        remainingMs = if (minutes > 0) minutes * 60_000L else null
        timerStartedAt = elapsed()
        val endsAt = remainingMs?.let { elapsed() + it }
        if (!silent) output.play(snd, SleepSettings.level(st), endsAt)
        _state.value = SleepState(snd, playing = true, timerMinutes = minutes, endsAtElapsed = endsAt)
        scheduleEnd(endsAt)
        publish()
        return true
    }

    override fun stop() {
        scheduler.cancel()
        if (!silentPreview) output.stop()
        focus.abandon(); focusState = SleepFocusPolicy.Focus()
        remainingMs = null
        silentPreview = false
        _state.value = SleepState()
        services().media.clear(SleepSounds.SOURCE_ID)
    }

    override fun setTimer(minutes: Int) {
        val cur = _state.value
        if (cur.sound == null) { services().settings.putString(SleepSettings.TIMER, minutes.coerceAtLeast(0).toString()); return }
        val m = minutes.coerceAtLeast(0)
        remainingMs = if (m > 0) m * 60_000L else null
        timerStartedAt = elapsed()
        if (cur.playing) {
            val endsAt = remainingMs?.let { elapsed() + it }
            if (!silentPreview) output.setEndsAt(endsAt)
            _state.value = cur.copy(timerMinutes = m, endsAtElapsed = endsAt, fading = false)
            scheduleEnd(endsAt)
        } else _state.value = cur.copy(timerMinutes = m, endsAtElapsed = null, fading = false)
        publish()
    }

    override fun bedtime(request: BedtimeRequest): BedtimeReport = routine.run(request)

    /** Pauses (keeps the sound and the timer's remaining time). */
    fun pause() {
        val cur = _state.value
        if (cur.sound == null || !cur.playing) return
        scheduler.cancel()
        cur.endsAtElapsed?.let { remainingMs = (it - elapsed()).coerceAtLeast(0) }
        if (!silentPreview) output.stop()
        _state.value = cur.copy(playing = false, endsAtElapsed = null, fading = false)
        publish()
    }

    /** Resumes a paused sound with the time it had left. */
    fun resume(): Boolean {
        val cur = _state.value
        val snd = cur.sound ?: return false
        if (cur.playing) return true
        if (!silentPreview && !takeFocus()) return false
        othersBefore = SleepArbitration.othersPlaying(services().media.sessions.value)
        val left = remainingMs
        if (left != null && left < MIN_RESUME_MS) { stop(); return false }
        val endsAt = left?.let { elapsed() + it }
        if (!silentPreview) output.play(snd, SleepSettings.level(services().settings), endsAt)
        _state.value = cur.copy(playing = true, endsAtElapsed = endsAt, fading = false)
        scheduleEnd(endsAt)
        publish()
        return true
    }

    /** Next / previous sound, keeping the timer (Now Playing's skip buttons, the screen's tiles). */
    fun switchTo(sound: SleepSound) {
        val cur = _state.value
        if (cur.sound == null) { services().settings.putString(SleepSettings.SOUND, sound.id); return }
        if (cur.sound == sound) return
        if (cur.playing && !silentPreview) output.play(sound, SleepSettings.level(services().settings), cur.endsAtElapsed)
        _state.value = cur.copy(sound = sound)
        publish()
    }

    fun skip(by: Int) {
        val cur = _state.value.sound ?: return
        val all = SleepSound.values()
        switchTo(all[((cur.ordinal + by) % all.size + all.size) % all.size])
    }

    /** The loudness setting changed (live). */
    fun levelChanged() { if (!silentPreview) output.setLevel(SleepSettings.level(services().settings)) }

    // ---------------- internals ----------------

    private fun takeFocus(): Boolean {
        if (focusState.held) return true
        val ok = focus.request { change -> onFocus(change) }
        focusState = if (ok) SleepFocusPolicy.Focus(held = true) else SleepFocusPolicy.Focus()
        return ok
    }

    internal fun onFocus(change: Int) {
        val step = SleepFocusPolicy.change(focusState, change, _state.value.playing, elapsed())
        focusState = step.focus
        if (!silentPreview) output.setDucked(step.focus.ducked)
        when {
            step.pause -> {
                pause()
                if (!step.focus.held && step.focus.pausedAt == null) focus.abandon()
            }
            step.resume -> resume()
        }
    }

    private fun scheduleEnd(endsAt: Long?) {
        scheduler.cancel()
        endsAt ?: return
        val fadeAt = endsAt - SleepSounds.FADE_OUT_MS
        if (fadeAt > elapsed()) scheduler.at(fadeAt) { markFading(endsAt) } else markFading(endsAt)
    }

    private fun markFading(endsAt: Long) {
        val cur = _state.value
        if (!cur.playing || cur.endsAtElapsed != endsAt) return
        _state.value = cur.copy(fading = true)
        // the output stops itself at the end of its fade; this is the backstop (and the silent preview's end)
        scheduler.at(endsAt + END_GRACE_MS) { if (_state.value.endsAtElapsed == endsAt) stop() }
    }

    private fun onOutputFinished() {
        val cur = _state.value
        if (!cur.playing) return // paused or stopped: expected
        val end = cur.endsAtElapsed
        if (end != null && elapsed() >= end - END_GRACE_MS) { stop(); return }
        // the track ended early (an audio error, or a resume raced the end of a stop ramp): start it again
        Log.w(SleepFeature.TAG, "output ended early: restarting")
        cur.sound?.let { output.play(it, SleepSettings.level(services().settings), end) }
    }

    private val controls = object : MediaControls {
        override fun play() { resume() }
        override fun pause() { this@SleepController.pause() }
        override fun next() = skip(1)
        override fun previous() = skip(-1)
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
        override val canSeek: Boolean get() = false
        override fun disconnect() = stop()
    }

    /** The sleep sound as a media session: the timer is the "track" (elapsed / remaining). */
    private fun publish() {
        val cur = _state.value
        val snd = cur.sound ?: return
        val total = cur.timerMinutes * 60_000L
        val left = cur.endsAtElapsed?.let { (it - elapsed()).coerceAtLeast(0) } ?: remainingMs
        val position = if (total > 0 && left != null) (total - left).coerceIn(0, total) else elapsed() - timerStartedAt
        services().media.publish(SleepSounds.SOURCE_ID, MediaState(
            title = snd.title,
            artist = TITLE,
            album = if (cur.timerMinutes > 0) "${cur.timerMinutes} min sleep timer" else "",
            artwork = artwork(snd),
            durationMs = total,
            positionMs = position,
            positionSampledAt = elapsed(),
            playing = cur.playing,
            source = MediaSourceKind.OTHER_APP,
            sourceLabel = TITLE,
            route = listOf("On this Echo", "Generated", "22.05 kHz"),
        ), controls)
    }

    companion object {
        const val TITLE = "Sleep sounds"
        private const val END_GRACE_MS = 1_500L
        /** Resuming with less than this left just stops. */
        private const val MIN_RESUME_MS = 5_000L
    }
}
