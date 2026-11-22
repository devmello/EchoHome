package app.vesper.feature.media

import android.graphics.Bitmap
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState

/**
 * A plain copy of what a MediaController reports (MediaMetadata + PlaybackState), so the mapping
 * to [MediaState] is testable on the JVM. Built from the framework objects in [AppSessions].
 */
data class SessionSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String? = null,
    val displayTitle: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val displaySubtitle: String? = null,
    val album: String? = null,
    /** MediaMetadata.METADATA_KEY_DURATION; 0 or negative = unknown (live streams). */
    val durationMs: Long = 0,
    /** PlaybackState.getState(), see [SessionMapping] constants. */
    val playbackState: Int = SessionMapping.STATE_NONE,
    val positionMs: Long = 0,
    /** PlaybackState.getLastPositionUpdateTime(), SystemClock.elapsedRealtime() base. */
    val lastPositionUpdate: Long = 0,
    val speed: Float = 1f,
    /** PlaybackState.getActions(). */
    val actions: Long = 0,
    /** current/max for remote playback (PlaybackInfo.PLAYBACK_TYPE_REMOTE); null for local. */
    val remoteVolume: Float? = null,
)

object SessionMapping {
    // android.media.session.PlaybackState values (copied so the JVM tests don't need the framework).
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_FAST_FORWARDING = 4
    const val STATE_REWINDING = 5
    const val STATE_BUFFERING = 6
    const val STATE_ERROR = 7
    const val STATE_CONNECTING = 8
    const val STATE_SKIPPING_TO_PREVIOUS = 9
    const val STATE_SKIPPING_TO_NEXT = 10
    const val ACTION_SEEK_TO = 1L shl 8

    /** Source id in the [app.vesper.core.model.MediaHub] for one app session. */
    fun sourceId(packageName: String, sessionTag: String) = "app:$packageName:$sessionTag"

    /**
     * Sessions we don't mirror: our own (the AirPlay receiver publishes to the hub directly) and
     * the Bluetooth stack's AVRCP session (`:feature:phone` publishes it as BLUETOOTH, "bluetooth").
     */
    fun isMirrored(packageName: String, ownPackage: String) = packageName != ownPackage && packageName != BLUETOOTH_PACKAGE

    const val BLUETOOTH_PACKAGE = "com.android.bluetooth"

    /** Whether the session is advancing (the UI extrapolates its position). */
    fun isPlaying(state: Int) = state == STATE_PLAYING || state == STATE_FAST_FORWARDING || state == STATE_REWINDING

    /** Whether to show it at all: something is loaded and it isn't stopped / in error. */
    fun isActive(s: SessionSnapshot): Boolean {
        if (s.playbackState == STATE_NONE || s.playbackState == STATE_STOPPED || s.playbackState == STATE_ERROR) return false
        return title(s).isNotBlank()
    }

    const val ACTION_PAUSE = 1L shl 1
    const val ACTION_SKIP_TO_PREVIOUS = 1L shl 4
    const val ACTION_SKIP_TO_NEXT = 1L shl 5
    const val ACTION_PLAY_PAUSE = 1L shl 9

    fun canSeek(s: SessionSnapshot) = s.durationMs > 0 && (s.actions and ACTION_SEEK_TO) != 0L

    /** Next or previous is offered (actions 0 = the app didn't say: assume yes). */
    fun canSkip(s: SessionSnapshot) = s.actions == 0L || (s.actions and (ACTION_SKIP_TO_NEXT or ACTION_SKIP_TO_PREVIOUS)) != 0L

    fun canPause(s: SessionSnapshot) = s.actions == 0L || (s.actions and (ACTION_PAUSE or ACTION_PLAY_PAUSE)) != 0L

    fun title(s: SessionSnapshot): String = firstNonBlank(s.title, s.displayTitle)

    fun artist(s: SessionSnapshot): String = firstNonBlank(s.artist, s.albumArtist, s.displaySubtitle)

    /**
     * Maps a snapshot to the hub's state, or null when nothing should be shown ([isActive] false).
     * The position is re-based at [now] when the playback speed isn't 1 (MediaState extrapolates
     * at 1×); buffering shows as paused so the bar doesn't run ahead of the audio.
     */
    fun toMediaState(s: SessionSnapshot, now: Long, artwork: Bitmap? = null): MediaState? {
        if (!isActive(s)) return null
        val playing = isPlaying(s.playbackState)
        var pos = s.positionMs.coerceAtLeast(0)
        var sampledAt = s.lastPositionUpdate
        if (playing && (s.speed != 1f || s.lastPositionUpdate <= 0)) {
            if (s.lastPositionUpdate > 0) pos += ((now - s.lastPositionUpdate) * s.speed).toLong()
            sampledAt = now
        }
        val dur = s.durationMs.coerceAtLeast(0)
        if (dur > 0) pos = pos.coerceAtMost(dur)
        return MediaState(
            title = title(s),
            artist = artist(s),
            album = firstNonBlank(s.album),
            artwork = artwork,
            durationMs = dur,
            positionMs = pos,
            positionSampledAt = sampledAt,
            playing = playing,
            source = MediaSourceKind.OTHER_APP,
            sourceLabel = s.appLabel,
            volume = s.remoteVolume?.coerceIn(0f, 1f),
        )
    }

    /** MediaStore's placeholder for a missing tag (`MediaStore.UNKNOWN_STRING`); treated as blank. */
    const val UNKNOWN = "<unknown>"

    private fun known(v: String?) = !v.isNullOrBlank() && v.trim() != UNKNOWN

    private fun firstNonBlank(vararg v: String?): String = v.firstOrNull(::known)?.trim().orEmpty()
}
