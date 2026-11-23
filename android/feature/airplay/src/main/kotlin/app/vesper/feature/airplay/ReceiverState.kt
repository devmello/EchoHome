package app.vesper.feature.airplay

import android.graphics.Bitmap
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import kotlin.math.pow
import kotlin.math.roundToInt

/** Unit conversions for the AirPlay protocol. Pure; unit-tested. */
internal object AirPlayMath {
    /** RAOP progress / RTP timestamps tick at 44.1 kHz. */
    const val RTP_RATE = 44_100L
    /** The sender's volume range: -30 dB (slider at the bottom) … 0 dB; -144 means mute. */
    const val MIN_DB = -30f
    const val MUTE_DB = -144f

    /** Sender volume (dB) → 0..1 slider position. */
    fun volumeFraction(db: Float): Float =
        if (db <= -100f) 0f else ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)

    /** 0..1 slider position → sender dB. */
    fun dbForFraction(fraction: Float): Float =
        if (fraction <= 0f) MUTE_DB else MIN_DB + -MIN_DB * fraction.coerceAtMost(1f)

    /** Sender volume (dB) → linear AudioTrack gain. The slider is linear in dB, so loudness follows it. */
    fun gain(db: Float): Float = if (db <= -100f) 0f else 10f.pow(db.coerceAtMost(0f) / 20f)

    /** 0..1 → DACP `dmcp.volume` (0..100). */
    fun dacpVolume(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).roundToInt()

    /** u32 RTP difference (wraps) → ms. */
    fun rtpDeltaMs(from: Long, to: Long): Long = ((to - from) and 0xFFFF_FFFFL) * 1000L / RTP_RATE

    /** RAOP `progress: start/current/end` → (position, duration) in ms. */
    fun progress(start: Long, current: Long, end: Long): Pair<Long, Long> {
        val duration = rtpDeltaMs(start, end)
        // `current` can sit a little before `start` right after a track change: treat as 0.
        val ahead = (current - start) and 0xFFFF_FFFFL
        val position = if (ahead > 0x8000_0000L) 0L else ahead * 1000L / RTP_RATE
        return position.coerceAtMost(duration) to duration
    }
}

/**
 * Everything the receiver knows about the current sender, reduced from native events on the
 * service's event thread. Pure data; [Reducer] holds the transitions so they're unit-tested.
 */
internal data class ReceiverState(
    /** Open RTSP connections per sender IP (iOS opens more than one). */
    val connections: Map<String, Int> = emptyMap(),
    /** The most recent sender IP that opened a connection. */
    val lastIp: String? = null,
    /** A sender is really using the receiver, not just probing `GET /info` from a picker. */
    val connected: Boolean = false,
    val senderName: String? = null,
    /** An audio stream is set up. */
    val streaming: Boolean = false,
    /** PCM is flowing. */
    val playing: Boolean = false,
    /** Paused from our side without DACP (AirPlay 2): audio is dropped until play(). */
    val locallyPaused: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** Clock (elapsedRealtime) when [positionMs] was sampled. */
    val positionAt: Long = 0,
    val volumeDb: Float? = null,
    /** DACP remote control is available. */
    val hasRemote: Boolean = false,
    /** When the current artwork arrived; null = no artwork for this track. */
    val artworkAt: Long? = null,
    /** When the last progress message arrived. */
    val progressAt: Long? = null,
    /** The receiver runs in AirPlay 2 mode (else AirPlay 1). */
    val ap2: Boolean = true,
    /** The current stream's format; 0 = no stream yet. */
    val sampleRate: Int = 0,
    val channels: Int = 0,
) {
    val anyConnection: Boolean get() = connections.isNotEmpty()

    /** Extrapolated position at [now]. */
    fun positionNow(now: Long): Long {
        val p = if (playing && !locallyPaused) positionMs + (now - positionAt) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /**
     * What goes into `services.media`; null = nothing to show yet (or any more). [speakerVolume] is
     * the Echo's own music volume (0..1), which Now Playing shows; without it, the sender's.
     */
    fun toMediaState(artwork: Bitmap?, speakerVolume: Float? = null): MediaState? {
        if (!anyConnection || (!streaming && title == null)) return null
        return MediaState(
            title = title ?: "",
            artist = artist ?: "",
            album = album ?: "",
            artwork = if (artworkAt != null) artwork else null,
            durationMs = durationMs,
            positionMs = positionMs,
            positionSampledAt = positionAt,
            playing = playing && !locallyPaused,
            source = MediaSourceKind.AIRPLAY,
            sourceLabel = senderName ?: DEFAULT_SENDER_LABEL,
            volume = speakerVolume ?: volumeDb?.let(AirPlayMath::volumeFraction),
            route = route(),
        )
    }

    /**
     * Now Playing's route chips, e.g. ["AirPlay 2", "44.1 kHz", "Maya's iPhone"]. The library
     * decodes to PCM without saying which codec the sender chose, so no codec chip.
     */
    fun route(): List<String> = buildList {
        add(if (ap2) "AirPlay 2" else "AirPlay")
        if (sampleRate > 0) add(formatRate(sampleRate))
        if (channels == 1) add("Mono")
        senderName?.let(::add)
    }

    companion object {
        const val DEFAULT_SENDER_LABEL = "AirPlay"

        /** 44100 → "44.1 kHz", 48000 → "48 kHz". */
        fun formatRate(hz: Int): String {
            val tenths = (hz + 50) / 100
            return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10}.${tenths % 10} kHz"
        }
    }
}

internal object Reducer {
    /** Artwork / progress that arrived this shortly before new track text belongs to the new track. */
    const val GRACE_MS = 1_500L

    fun clientConnected(s: ReceiverState, ip: String) =
        s.copy(connections = s.connections + (ip to (s.connections[ip] ?: 0) + 1), lastIp = ip)

    fun clientDisconnected(s: ReceiverState, ip: String): ReceiverState {
        val left = (s.connections[ip] ?: 0) - 1
        val connections = if (left > 0) s.connections + (ip to left) else s.connections - ip
        if (connections.isEmpty()) return ReceiverState(volumeDb = s.volumeDb, ap2 = s.ap2)
        return s.copy(connections = connections, lastIp = if (s.lastIp == ip && left <= 0) connections.keys.last() else s.lastIp)
    }

    /** The connection lasted long enough, or audio started: show "AirPlay connected". */
    fun markConnected(s: ReceiverState): ReceiverState =
        if (s.anyConnection) s.copy(connected = true) else s

    fun senderName(s: ReceiverState, ip: String, name: String): ReceiverState =
        if (s.connections.containsKey(ip)) s.copy(senderName = name) else s

    fun audioStart(s: ReceiverState, now: Long, sampleRate: Int = 44_100, channels: Int = 2) =
        s.copy(streaming = true, connected = s.anyConnection, playing = false, positionAt = now, sampleRate = sampleRate, channels = channels)

    fun audioStop(s: ReceiverState, now: Long) = freeze(s, now).copy(streaming = false, playing = false, locallyPaused = false)

    /** PCM started or stopped flowing (pause / resume as seen by the player). */
    fun flowing(s: ReceiverState, flowing: Boolean, now: Long): ReceiverState = when {
        flowing == s.playing -> s
        flowing -> s.copy(playing = true, positionAt = now)
        else -> freeze(s, now).copy(playing = false)
    }

    fun flush(s: ReceiverState, now: Long) = freeze(s, now).copy(playing = false)

    /**
     * Senders send text, artwork and progress as separate messages in any order, so artwork or
     * progress that arrived just before new track text belongs to the new track.
     */
    fun metadata(s: ReceiverState, title: String?, artist: String?, album: String?, durationMs: Long, now: Long): ReceiverState {
        val newTrack = title != s.title || artist != s.artist || album != s.album
        val keepArt = !newTrack || (s.artworkAt != null && now - s.artworkAt <= GRACE_MS)
        val keepProgress = !newTrack || (s.progressAt != null && now - s.progressAt <= GRACE_MS)
        return s.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = if (durationMs > 0) durationMs else if (keepProgress) s.durationMs else 0,
            positionMs = if (keepProgress) s.positionMs else 0,
            positionAt = if (keepProgress) s.positionAt else now,
            progressAt = if (keepProgress) s.progressAt else null,
            artworkAt = if (keepArt) s.artworkAt else null,
        )
    }

    fun coverArt(s: ReceiverState, now: Long) = s.copy(artworkAt = now)

    fun progress(s: ReceiverState, start: Long, current: Long, end: Long, now: Long): ReceiverState {
        val (position, duration) = AirPlayMath.progress(start, current, end)
        return s.copy(positionMs = position, durationMs = if (duration > 0) duration else s.durationMs, positionAt = now, progressAt = now)
    }

    fun volume(s: ReceiverState, db: Float) = s.copy(volumeDb = db)

    fun remote(s: ReceiverState, available: Boolean) = s.copy(hasRemote = available)

    fun locallyPaused(s: ReceiverState, paused: Boolean, now: Long) =
        if (paused == s.locallyPaused) s else freeze(s, now).copy(locallyPaused = paused, positionAt = now)

    private fun freeze(s: ReceiverState, now: Long) = s.copy(positionMs = s.positionNow(now), positionAt = now)
}
