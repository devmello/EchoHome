package app.vesper.feature.phone.audio

/**
 * Pauses the phone's Bluetooth music while the assistant listens or speaks, and resumes it after
 * ("I paused music here", design demo B). Pure logic. [BluetoothMediaSource] drives it.
 *
 * Why this is needed (checked on this ROM, see docs/status/phone.md "Assistant audio focus"):
 * the Bluetooth app holds AUDIOFOCUS_GAIN (USAGE_MEDIA) while the phone streams, and handles a
 * non-duckable transient loss itself (mute, AVRCP pause after 400 ms, play again on gain). But on
 * the assistant's AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, Android 11 ducks the loser in the framework
 * and doesn't tell it. The framework ducks only players registered with AudioService, and the A2DP
 * sink's native AudioTrack isn't one. So nobody ducks the phone's music, and the Bluetooth app's
 * own 25 % duck never runs. Its gain can't be set from another process, and lowering
 * STREAM_MUSIC would also lower the assistant's voice (USAGE_ASSISTANT plays on STREAM_MUSIC) and
 * move the iPhone's volume slider (AVRCP absolute volume). That leaves AVRCP pause/play.
 *
 * Rules:
 *  - Pause only if the phone is playing when the assistant starts. Resume only what we paused.
 *  - Resume [RESUME_DELAY_MS] after the assistant ends, so a short gap between its phases
 *    (working → speaking) doesn't flap the phone.
 *  - Never resume if, during the hold, the user (or the assistant's own media tools) sent a
 *    command through Now Playing, the phone started playing again on its own, or the hold
 *    lasted longer than [MAX_RESUME_AFTER_MS].
 */
class AssistantPause(
    private val pause: () -> Unit,
    private val play: () -> Unit,
    /** Runs [block] after [delayMs]. Returns a cancel function. */
    private val later: (delayMs: Long, block: () -> Unit) -> () -> Unit,
    private val now: () -> Long,
) {
    private var active = false
    private var pausedByUs = false
    /** The phone reported paused after our pause, so a later "playing" is a real restart. */
    private var confirmed = false
    private var pausedAt = 0L
    private var pendingResume: (() -> Unit)? = null

    val holding: Boolean get() = pausedByUs

    /** The assistant started (true) or stopped (false) a turn. [playing]: the phone's music now. */
    fun onAssistant(on: Boolean, playing: Boolean) {
        if (on) {
            pendingResume?.invoke(); pendingResume = null
            if (active) return
            active = true
            if (playing && !pausedByUs) {
                pausedByUs = true; confirmed = false; pausedAt = now()
                pause()
            }
        } else {
            if (!active) return
            active = false
            if (!pausedByUs) return
            pendingResume?.invoke()
            pendingResume = later(RESUME_DELAY_MS) { pendingResume = null; resume() }
        }
    }

    /** The phone's play state changed (AVRCP). */
    fun onPlayingChanged(playing: Boolean) {
        if (!pausedByUs) return
        if (!playing) confirmed = true
        else if (confirmed) drop() // started again on the phone during the hold: leave it be
    }

    /** A command from Now Playing (play, pause, skip, disconnect) wins over our resume. */
    fun onUserCommand() { if (pausedByUs) drop() }

    /** The phone's audio went away (A2DP/AVRCP disconnected, Bluetooth off, feature off). */
    fun reset() { drop(); active = false }

    private fun resume() {
        if (!pausedByUs || active) return
        val fresh = now() - pausedAt <= MAX_RESUME_AFTER_MS
        pausedByUs = false; confirmed = false
        if (fresh) play()
    }

    private fun drop() {
        pausedByUs = false; confirmed = false
        pendingResume?.invoke(); pendingResume = null
    }

    companion object {
        const val RESUME_DELAY_MS = 1_200L
        const val MAX_RESUME_AFTER_MS = 10 * 60_000L
    }
}
