package app.vesper.feature.spotify

import android.media.AudioManager
import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.fake.FakeMediaSource
import kotlin.math.roundToInt

/** Volume conversions. Spotify's volume is 0..65535 and is the Echo's music volume. Pure. */
internal object SpotifyVolume {
    const val MAX = 65_535

    fun fraction(volume: Int): Float = (volume.coerceIn(0, MAX) / MAX.toFloat())

    fun fromFraction(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * MAX).roundToInt()

    /** Spotify volume → STREAM_MUSIC index (0..[maxIndex]). */
    fun index(volume: Int, maxIndex: Int): Int = (fraction(volume) * maxIndex).roundToInt().coerceIn(0, maxIndex)
}

/**
 * Keeps the Spotify app's slider and the Echo's speaker in step without feedback loops:
 * the slider sets the speaker; the Echo's keys / Control Center report back to Spotify, except
 * when the speaker change was our own (from the slider), which would snap the slider to the
 * Echo's 15 steps while it's being dragged.
 */
internal class VolumeSync(private val maxIndex: Int) {
    /** The speaker index we last set from Spotify. */
    private var fromRemote: Int? = null

    /** Spotify set [volume]: the speaker index to apply, or null if it's already there. */
    fun fromSpotify(volume: Int, currentIndex: Int): Int? {
        val i = SpotifyVolume.index(volume, maxIndex)
        fromRemote = i
        return i.takeIf { it != currentIndex }
    }

    /** The speaker is at [index] now: the Spotify volume to report, or null (our own change, or unchanged). */
    fun fromSpeaker(index: Int): Int? {
        if (index == fromRemote) return null
        fromRemote = null
        return SpotifyVolume.fromFraction(index / maxIndex.toFloat())
    }
}

internal data class SpotifyFocus(
    val hasFocus: Boolean = false,
    /** Output lowered to [SpotifyFocusPolicy.DUCK_GAIN] (the assistant speaking, a notification). */
    val ducked: Boolean = false,
    /** We paused Spotify for a transient loss; GAIN plays again. */
    val pausedForLoss: Boolean = false,
)

/**
 * Audio focus, which is also how the Echo's sources take turns: AirPlay and the Bluetooth
 * speaker request AUDIOFOCUS_GAIN when they start, so Spotify gets LOSS and pauses; Spotify
 * requests GAIN when it starts, so they pause / mute. Same rules as AirPlay's AudioFocusPolicy.
 */
internal object SpotifyFocusPolicy {
    const val DUCK_GAIN = 0.2f

    data class Step(val focus: SpotifyFocus, val pause: Boolean = false, val play: Boolean = false)

    fun requested(f: SpotifyFocus, granted: Boolean) =
        if (granted) f.copy(hasFocus = true, ducked = false) else f.copy(hasFocus = false)

    fun change(f: SpotifyFocus, change: Int, playing: Boolean): Step = when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> Step(SpotifyFocus(hasFocus = true), play = f.pausedForLoss)
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Step(f.copy(ducked = true))
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
            Step(f.copy(ducked = false, pausedForLoss = f.pausedForLoss || playing), pause = playing)
        // Permanent: another source (AirPlay, Bluetooth, an app) owns playback now; nothing resumes.
        AudioManager.AUDIOFOCUS_LOSS -> Step(SpotifyFocus(), pause = playing)
        else -> Step(f)
    }

    fun gain(f: SpotifyFocus): Float = if (f.ducked) DUCK_GAIN else 1f
}

/**
 * The other receivers on the hub (AirPlay, Bluetooth speaker): they must not play at the same
 * time as Spotify. Audio focus does most of it; this covers sources that start without taking
 * focus (an AirPlay 2 sender before PCM flows, a phone that resumes over AVRCP). Pure.
 */
internal object Arbitration {
    private val RECEIVERS = setOf(MediaSourceKind.AIRPLAY, MediaSourceKind.BLUETOOTH)

    /** Other receivers playing right now (their source ids). */
    fun othersPlaying(sessions: List<MediaSession>, ownId: String): Set<String> =
        sessions.filter { it.sourceId != ownId && it.sourceId != FakeMediaSource.ID && it.state.playing && it.state.source in RECEIVERS }
            .map { it.sourceId }.toSet()

    /** A receiver started playing since [before] while Spotify plays: pause Spotify. */
    fun shouldPauseSpotify(before: Set<String>, now: Set<String>, spotifyPlaying: Boolean): Boolean =
        spotifyPlaying && (now - before).isNotEmpty()

    /** Spotify just started playing: the other receivers to pause. */
    fun toPauseWhenSpotifyStarts(sessions: List<MediaSession>, ownId: String): List<MediaSession> {
        val ids = othersPlaying(sessions, ownId)
        return sessions.filter { it.sourceId in ids }
    }
}

/**
 * Stops a native crash from turning into a crash loop of the HOME app: every start counts an
 * attempt (persisted before the native call); a receiver that stays up for [STABLE_MS] clears it.
 * After [MAX_ATTEMPTS] unfinished starts the receiver stays off until the user switches it on again.
 *
 * An unfinished start only counts when the process actually crashed: on the first start in a
 * process, [SpotifyService] asks Android why the previous process ended
 * (`ActivityManager.getHistoricalProcessExitReasons`), and a force-stop, an install or a
 * low-memory kill clears the count. (Three quick restarts, e.g. an install plus two
 * `am force-stop`s, used to switch Spotify off.)
 */
internal object CrashGuard {
    const val MAX_ATTEMPTS = 3
    const val STABLE_MS = 60_000L

    fun allowed(attempts: Int): Boolean = attempts < MAX_ATTEMPTS

    // ApplicationExitInfo.REASON_* (API 30), as plain ints so the JVM tests don't need android.jar.
    private const val REASON_UNKNOWN = 0
    private const val REASON_SIGNALED = 2
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_INITIALIZATION_FAILURE = 7

    /**
     * Whether the previous process's exit [reason] can be the receiver's fault. `null` = Android
     * has no record (first run after boot or a data wipe): keep the count, the safe side.
     */
    fun couldBeCrash(reason: Int?): Boolean = when (reason) {
        null, REASON_UNKNOWN, REASON_SIGNALED, REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR,
        REASON_INITIALIZATION_FAILURE -> true
        else -> false
    }
}
