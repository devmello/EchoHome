package com.echobedside.core.model

import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** BLUETOOTH = a phone streaming to the Echo as a Bluetooth speaker (A2DP sink + AVRCP), `:feature:phone`. */
enum class MediaSourceKind { AIRPLAY, BLUETOOTH, SPOTIFY, OTHER_APP }

/**
 * One source's now-playing state. Position is a snapshot: use [positionNow] to extrapolate
 * (UIs tick once a second with 1 s linear transitions, NOTES §3.5).
 */
data class MediaState(
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: Bitmap? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** SystemClock.elapsedRealtime() when [positionMs] was sampled. */
    val positionSampledAt: Long = SystemClock.elapsedRealtime(),
    val playing: Boolean = false,
    val source: MediaSourceKind,
    /** e.g. "Maya's iPhone" for AirPlay, or the app label. */
    val sourceLabel: String = "",
    /** 0..1, the sender's volume when the source reports it. */
    val volume: Float? = null,
    /** Short labels for Now Playing's route popover chips, e.g. ["AirPlay 2", "Lossless · ALAC", "44.1 kHz"]. */
    val route: List<String> = emptyList(),
) {
    fun positionNow(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (playing) positionMs + (now - positionSampledAt) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

/** Remote control back to the sender. Every call is fire-and-forget; state comes back via [MediaHub]. */
interface MediaControls {
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    /** 0..1. */
    fun setVolume(volume: Float)
    val canSeek: Boolean get() = true
    /** false = next/previous do nothing for this source (e.g. AirPlay 2 senders): hide or dim them. */
    val canSkip: Boolean get() = true
    /** false = the sender can't be paused remotely (play/pause may only mute locally). */
    val canPause: Boolean get() = true
    /** Now Playing's "Disconnect": drop the sender (AirPlay, Bluetooth) or stop the app session. */
    fun disconnect() { pause() }
}

/** A source currently known to the hub. */
data class MediaSession(val sourceId: String, val state: MediaState, val controls: MediaControls)

/**
 * The one place Now Playing, the island and Control Center read media from.
 * Producers: the AirPlay receiver (`:feature:airplay`) and other apps' sessions (`:feature:media`).
 */
interface MediaHub {
    /** All sources with something loaded. */
    val sessions: StateFlow<List<MediaSession>>
    /** What to show: the most recently started playing session, else the most recently updated. */
    val current: StateFlow<MediaSession?>

    /** Add or replace [sourceId]'s state. Call on every metadata / play-state / position change. */
    fun publish(sourceId: String, state: MediaState, controls: MediaControls)
    fun clear(sourceId: String)
}

class DefaultMediaHub : MediaHub {
    private val _sessions = MutableStateFlow<List<MediaSession>>(emptyList())
    private val _current = MutableStateFlow<MediaSession?>(null)
    override val sessions: StateFlow<List<MediaSession>> = _sessions.asStateFlow()
    override val current: StateFlow<MediaSession?> = _current.asStateFlow()

    override fun publish(sourceId: String, state: MediaState, controls: MediaControls) {
        val session = MediaSession(sourceId, state, controls)
        // Most recently updated goes last; playing sessions win over paused ones.
        _sessions.update { list -> list.filterNot { it.sourceId == sourceId } + session }
        recompute()
    }

    override fun clear(sourceId: String) {
        _sessions.update { list -> list.filterNot { it.sourceId == sourceId } }
        recompute()
    }

    private fun recompute() {
        val list = _sessions.value
        _current.value = list.lastOrNull { it.state.playing } ?: list.lastOrNull()
    }
}
