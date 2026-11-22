package app.vesper.feature.airplay

import android.media.AudioManager

/**
 * What AirPlay does with audio focus (pure, unit-tested). [AirPlayService] requests
 * `AUDIOFOCUS_GAIN` (USAGE_MEDIA) when PCM starts flowing, with `setWillPauseWhenDucked(true)`: the
 * framework then doesn't auto-duck our native-fed AudioTrack and calls us instead, so the duck is
 * always the same ~20 % (no double duck).
 *
 * - `LOSS_TRANSIENT_CAN_DUCK` (the assistant listening / speaking, a notification): output × [DUCK_GAIN].
 * - `LOSS_TRANSIENT` (a call, an alarm): drop the audio and, with DACP (AirPlay 1), pause the sender.
 * - `LOSS`: the same, but nothing resumes later.
 * - `GAIN`: back to full volume and, if we paused the sender for the transient loss, play again.
 */
internal data class FocusState(
    val hasFocus: Boolean = false,
    /** Output lowered to [AudioFocusPolicy.DUCK_GAIN]. */
    val ducked: Boolean = false,
    /** Output dropped (transient or permanent loss). */
    val muted: Boolean = false,
    /** We paused the sender over DACP for a transient loss; GAIN plays it again. */
    val pausedSender: Boolean = false,
)

internal object AudioFocusPolicy {
    /** Linear gain while ducked (about -14 dB, what Android's own duck uses). */
    const val DUCK_GAIN = 0.2f

    data class Step(val state: FocusState, val togglePlayPause: Boolean = false)

    /** Result of our own focus request. */
    fun requested(s: FocusState, granted: Boolean): FocusState =
        if (granted) s.copy(hasFocus = true, ducked = false, muted = false) else s.copy(hasFocus = false)

    /**
     * A focus change from the listener. [hasRemote]: the sender can be paused over DACP;
     * [playing]: it is playing now.
     */
    fun change(s: FocusState, change: Int, hasRemote: Boolean, playing: Boolean): Step = when (change) {
        AudioManager.AUDIOFOCUS_GAIN ->
            Step(FocusState(hasFocus = true), togglePlayPause = s.pausedSender)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
            Step(s.copy(ducked = true))
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            val pause = hasRemote && playing && !s.pausedSender
            Step(s.copy(ducked = false, muted = true, pausedSender = s.pausedSender || pause), togglePlayPause = pause)
        }
        AudioManager.AUDIOFOCUS_LOSS -> {
            val pause = hasRemote && playing && !s.pausedSender
            // Permanent: whoever took focus owns playback now, so nothing resumes by itself.
            Step(FocusState(hasFocus = false, muted = true), togglePlayPause = pause)
        }
        else -> Step(s)
    }

    /** AudioTrack gain for the sender's volume [base] (0..1) under focus state [s]. */
    fun gain(base: Float, s: FocusState): Float = if (s.ducked) base * DUCK_GAIN else base

    fun name(change: Int): String = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> change.toString()
    }
}
