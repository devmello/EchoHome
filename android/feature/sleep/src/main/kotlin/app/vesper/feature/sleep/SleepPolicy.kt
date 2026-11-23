package app.vesper.feature.sleep

import android.media.AudioManager
import app.vesper.core.model.MediaSession
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.fake.FakeMediaSource

/**
 * Audio focus, which is how the Echo's sources take turns (same rules as AirPlay's and Spotify's
 * policies): sleep sounds take AUDIOFOCUS_GAIN when they start, so music pauses; AirPlay, the
 * Bluetooth speaker and Spotify take GAIN when they start, so the sleep sound gets LOSS and pauses
 * for good. Short transient losses (a notification, a timer chime) pause and come back; a long one
 * (an alarm ringing) doesn't bring the rain back afterwards. Pure.
 */
internal object SleepFocusPolicy {
    /** A transient loss shorter than this resumes on GAIN. */
    const val RESUME_WITHIN_MS = 30_000L

    data class Focus(
        val held: Boolean = false,
        val ducked: Boolean = false,
        /** elapsedRealtime of a transient loss that paused us; null = not paused by focus. */
        val pausedAt: Long? = null,
    )

    data class Step(val focus: Focus, val pause: Boolean = false, val resume: Boolean = false, val stop: Boolean = false)

    fun change(f: Focus, change: Int, playing: Boolean, now: Long): Step = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> {
            val back = f.pausedAt != null && now - f.pausedAt <= RESUME_WITHIN_MS
            Step(Focus(held = true), resume = back)
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Step(f.copy(ducked = true))
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            Step(f.copy(ducked = false, pausedAt = if (playing) now else f.pausedAt), pause = playing)
        // Someone else owns playback now: pause and let go of focus; nothing brings it back by itself.
        AudioManager.AUDIOFOCUS_LOSS -> Step(Focus(), pause = playing)
        else -> Step(f)
    }
}

/** The other sources on the hub. Pure. */
internal object SleepArbitration {
    /** Sources playing right now other than the sleep sound and the silent demo. */
    fun othersPlaying(sessions: List<MediaSession>): Set<String> =
        sessions.filter { it.sourceId != SleepSounds.SOURCE_ID && it.sourceId != FakeMediaSource.ID && it.state.playing }
            .map { it.sourceId }.toSet()

    /**
     * A source started playing since [before] while the sleep sound plays: pause it. Covers
     * sources that start without taking focus (an AirPlay 2 sender before PCM flows, a phone
     * resuming over AVRCP, Spotify Connect's first frames).
     */
    fun shouldPause(before: Set<String>, now: Set<String>, sleepPlaying: Boolean): Boolean =
        sleepPlaying && (now - before).isNotEmpty()
}
