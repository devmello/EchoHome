package app.vesper.feature.phone.ams

import android.graphics.Bitmap
import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.feature.phone.audio.AvrcpMapping
import kotlin.math.roundToInt

/** AMS → MediaHub (pure, JVM-tested). */
object AmsMapping {
    const val SOURCE_ID = "iphone"

    /** "Spotify on Maya’s iPhone"; just the phone when AMS has no app name. */
    fun label(app: String, phone: String): String = if (app.isBlank()) phone else "$app on $phone"

    /**
     * Now Playing for a remote-only iPhone player (its audio isn't on the Echo): OTHER_APP, so
     * Now Playing says "Playing from Spotify on Maya’s iPhone". Null when nothing is loaded.
     */
    fun toState(np: AmsNowPlaying, phone: String, artwork: Bitmap?): MediaState? {
        if (np.empty) return null
        val duration = np.durationMs.coerceAtLeast(0)
        return MediaState(
            title = np.title.ifBlank { "Unknown track" },
            artist = np.artist,
            album = np.album,
            artwork = artwork,
            durationMs = duration,
            positionMs = if (duration > 0) np.elapsedMs.coerceAtMost(duration) else np.elapsedMs,
            positionSampledAt = np.sampledAt,
            playing = np.playing,
            source = MediaSourceKind.OTHER_APP,
            sourceLabel = label(np.app, phone),
            volume = np.volume,
            route = route(np.app, phone),
        )
    }

    fun route(app: String, phone: String): List<String> = listOfNotNull("Remote", phone.ifBlank { null }, app.ifBlank { null })

    /** Remote commands → Now Playing's buttons. */
    fun canPause(c: Set<Int>) = Ams.CMD_PAUSE in c || Ams.CMD_TOGGLE_PLAY_PAUSE in c
    fun canPlay(c: Set<Int>) = Ams.CMD_PLAY in c || Ams.CMD_TOGGLE_PLAY_PAUSE in c
    fun canSkip(c: Set<Int>) = Ams.CMD_NEXT_TRACK in c || Ams.CMD_PREVIOUS_TRACK in c

    /** The command for play (or pause), preferring the explicit one over toggle. */
    fun playCommand(c: Set<Int>, play: Boolean): Int? = when {
        play && Ams.CMD_PLAY in c -> Ams.CMD_PLAY
        !play && Ams.CMD_PAUSE in c -> Ams.CMD_PAUSE
        Ams.CMD_TOGGLE_PLAY_PAUSE in c -> Ams.CMD_TOGGLE_PLAY_PAUSE
        else -> null
    }

    /** iOS volume moves in 16 steps. */
    const val VOLUME_STEP = 1f / 16

    /** Volume Up/Down presses that move [from] to about [to]: positive = up. At most 16. */
    fun volumeSteps(from: Float?, to: Float): Int {
        val f = from ?: return 0
        val n = ((to.coerceIn(0f, 1f) - f) / VOLUME_STEP).roundToInt()
        return n.coerceIn(-16, 16)
    }

    enum class Show {
        /** Publish the iPhone as its own Now Playing source. */
        OWN,
        /** The same iPhone plays to the Echo over Bluetooth: one entry, the A2DP one, with AMS's app label. */
        MERGED,
        /** Nothing to show, or audio playing on the Echo (AirPlay, Bluetooth, another app) wins. */
        HIDDEN,
    }

    /**
     * Precedence. [others] = the hub's sessions except ours ([SOURCE_ID]). [a2dpSamePhone] = the
     * A2DP source is connected and it's this iPhone.
     */
    fun decide(np: AmsNowPlaying, others: List<MediaSession>, a2dpSamePhone: Boolean): Show = when {
        np.empty -> Show.HIDDEN
        a2dpSamePhone && others.any { it.sourceId == AvrcpMapping.SOURCE_ID } -> Show.MERGED
        others.any { it.state.playing } -> Show.HIDDEN
        else -> Show.OWN
    }

    /** Only republish when something a UI shows changed (as [AvrcpMapping.sameForUi]). */
    fun sameForUi(a: MediaState?, b: MediaState?) = AvrcpMapping.sameForUi(a, b)

    /** True if two track descriptions are the same song (AMS and AVRCP can differ in spacing/case). */
    fun sameTrack(t1: String, a1: String, t2: String, a2: String) =
        t1.trim().equals(t2.trim(), ignoreCase = true) && (a1.isBlank() || a2.isBlank() || a1.trim().equals(a2.trim(), ignoreCase = true))
}
