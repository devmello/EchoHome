package com.echobedside.feature.phone.audio

import android.graphics.Bitmap
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.MediaState

/**
 * What the Bluetooth stack's AVRCP controller session reports, as plain values (pure, JVM-tested).
 * [state] uses android.media.session.PlaybackState's STATE_* numbers.
 */
data class AvrcpSnapshot(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val state: Int,
    /** PlaybackState.getPosition(); -1 = unknown. */
    val positionMs: Long,
    /** PlaybackState.getLastPositionUpdateTime(), SystemClock.elapsedRealtime() base; 0 = unknown. */
    val updatedAtElapsed: Long,
    val artwork: Bitmap? = null,
)

object AvrcpMapping {
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_FAST_FORWARDING = 4
    const val STATE_REWINDING = 5
    const val STATE_BUFFERING = 6
    const val STATE_ERROR = 7

    const val SOURCE_ID = "bluetooth"

    /**
     * The MediaHub state for [s], or null when there's nothing to show (no track, not
     * connected: the stack reports STATE_ERROR "not connected" or STATE_NONE then).
     */
    fun toState(s: AvrcpSnapshot?, label: String, volume: Float?, now: Long): MediaState? {
        if (s == null) return null
        if (s.state == STATE_NONE || s.state == STATE_ERROR) return null
        val title = s.title?.trim().orEmpty()
        val artist = s.artist?.trim().orEmpty()
        if (title.isEmpty() && artist.isEmpty()) return null
        val playing = s.state == STATE_PLAYING || s.state == STATE_FAST_FORWARDING || s.state == STATE_REWINDING
        val duration = s.durationMs.coerceAtLeast(0)
        val pos = if (s.positionMs < 0) 0 else if (duration > 0) s.positionMs.coerceAtMost(duration) else s.positionMs
        return MediaState(
            title = title.ifEmpty { "Unknown track" },
            artist = artist,
            album = s.album?.trim().orEmpty(),
            artwork = s.artwork,
            durationMs = duration,
            positionMs = pos,
            positionSampledAt = if (s.updatedAtElapsed > 0) s.updatedAtElapsed else now,
            playing = playing,
            source = MediaSourceKind.BLUETOOTH,
            sourceLabel = label,
            volume = volume,
        )
    }

    /**
     * Route popover chips. The sink codec (SBC/AAC) isn't readable by apps on Android 11
     * (BluetoothA2dp.getCodecStatus is source-side only), so no codec chip.
     */
    fun route(phoneName: String): List<String> = listOfNotNull("Bluetooth", phoneName.ifBlank { null })

    /** Only republish when something a UI shows changed (positions are extrapolated by readers). */
    fun sameForUi(a: MediaState?, b: MediaState?): Boolean {
        if (a == null || b == null) return a == b
        if (a.title != b.title || a.artist != b.artist || a.album != b.album || a.artwork !== b.artwork) return false
        if (a.playing != b.playing || a.durationMs != b.durationMs || a.sourceLabel != b.sourceLabel || a.volume != b.volume || a.route != b.route) return false
        // A position jump of more than 1.5 s (seek, skip, drift) is a change; steady playback isn't.
        val t = maxOf(a.positionSampledAt, b.positionSampledAt)
        return kotlin.math.abs(a.positionNow(t) - b.positionNow(t)) <= 1500
    }
}
