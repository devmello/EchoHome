package app.vesper.feature.spotify

import android.graphics.Bitmap
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState

/**
 * Everything the receiver knows, reduced from native events on the service's event thread.
 * Pure data; [SpotifyReducer] holds the transitions so they're unit-tested.
 */
internal data class SpotifyState(
    val phase: Int = NativeBridge.PHASE_IDLE,
    /** The Spotify app controlling the Echo ("Maya's iPhone"); null = unknown. */
    val client: String? = null,
    val title: String? = null,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    /** The cover to fetch (https), or null. */
    val coverUrl: String? = null,
    /** The URL of the bitmap the service holds; it belongs to this track only if it equals [coverUrl]. */
    val artworkUrl: String? = null,
    val playing: Boolean = false,
    /** A track is loading (start, skip, seek past the buffer). Shown as still playing if it was. */
    val loading: Boolean = false,
    val positionMs: Long = 0,
    /** elapsedRealtime when [positionMs] was sampled. */
    val positionAt: Long = 0,
) {
    val loaded: Boolean get() = title != null

    fun positionNow(now: Long): Long {
        val p = if (playing && !loading) positionMs + (now - positionAt) else positionMs
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }

    /**
     * What goes into `services.media`; null = nothing loaded. [speakerVolume] is the Echo's music
     * volume (0..1), which is also Spotify's volume (the app's slider sets the speaker).
     */
    fun toMediaState(artwork: Bitmap?, speakerVolume: Float?, bitrateKbps: Int): MediaState? {
        val t = title ?: return null
        return MediaState(
            title = t,
            artist = artist,
            album = album,
            artwork = if (artworkUrl != null && artworkUrl == coverUrl) artwork else null,
            durationMs = durationMs,
            positionMs = positionMs,
            positionSampledAt = positionAt,
            playing = playing,
            source = MediaSourceKind.SPOTIFY,
            sourceLabel = client.orEmpty(),
            volume = speakerVolume,
            route = route(bitrateKbps),
        )
    }

    /** Now Playing's route chips: ["Spotify Connect", "160 kbps", "Maya's iPhone"]. */
    fun route(bitrateKbps: Int): List<String> = buildList {
        add("Spotify Connect")
        add("$bitrateKbps kbps")
        client?.takeIf { it.isNotBlank() }?.let(::add)
    }
}

internal object SpotifyReducer {
    fun phase(s: SpotifyState, phase: Int): SpotifyState = when (phase) {
        // Signed out or refused: whatever was loaded is gone.
        NativeBridge.PHASE_IDLE, NativeBridge.PHASE_PREMIUM_REQUIRED, NativeBridge.PHASE_LOGIN_FAILED,
        NativeBridge.PHASE_FAILED -> SpotifyState(phase = phase)
        else -> s.copy(phase = phase)
    }

    fun client(s: SpotifyState, name: String) = s.copy(client = name.ifBlank { null })

    fun track(s: SpotifyState, title: String, artist: String, album: String, durationMs: Long, coverUrl: String, now: Long): SpotifyState {
        val same = title == s.title && artist == s.artist && album == s.album
        return s.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs.coerceAtLeast(0),
            coverUrl = coverUrl.ifBlank { null },
            positionMs = if (same) s.positionMs else 0,
            positionAt = if (same) s.positionAt else now,
        )
    }

    fun playback(s: SpotifyState, state: Int, positionMs: Long, now: Long): SpotifyState = when (state) {
        NativeBridge.PLAY_PLAYING -> s.copy(playing = true, loading = false, positionMs = positionMs, positionAt = now)
        NativeBridge.PLAY_PAUSED -> s.copy(playing = false, loading = false, positionMs = positionMs, positionAt = now)
        // Keep the play state through a load, so a skip doesn't flash "paused".
        else -> s.copy(loading = true, positionMs = positionMs, positionAt = now)
    }

    fun position(s: SpotifyState, positionMs: Long, now: Long) = s.copy(positionMs = positionMs, positionAt = now)

    /** The session moved to another device or ended: nothing loaded. Keeps the phase. */
    fun stopped(s: SpotifyState) = SpotifyState(phase = s.phase)

    fun artwork(s: SpotifyState, url: String?) = s.copy(artworkUrl = url)
}

/** The receiver's status line, for the settings page and logs. [name] is what the Spotify app lists. */
internal object SpotifyStatus {
    const val PREMIUM_NEEDED = "Spotify Premium is needed. Spotify Connect doesn't work with free accounts, so this account can't play on the Echo."

    fun text(phase: Int?, client: String?, name: String, failedStarts: Boolean = false): String = when {
        failedStarts -> "Turned off after failing to start several times. Switch the receiver off and on to try again."
        phase == null -> "Off."
        phase == NativeBridge.PHASE_IDLE -> "Ready. Open Spotify on a phone or computer on the same Wi-Fi and pick “$name”."
        phase == NativeBridge.PHASE_CONNECTING -> "Signing in to Spotify…"
        phase == NativeBridge.PHASE_CONNECTED ->
            if (!client.isNullOrBlank()) "Connected. Playing from $client." else "Signed in. “$name” is in your Spotify devices."
        phase == NativeBridge.PHASE_OFFLINE -> "Can't reach Spotify. Retrying on its own."
        phase == NativeBridge.PHASE_PREMIUM_REQUIRED -> PREMIUM_NEEDED
        phase == NativeBridge.PHASE_LOGIN_FAILED -> "Spotify refused the sign-in. Pick “$name” in the Spotify app again."
        phase == NativeBridge.PHASE_FAILED -> "The receiver stopped unexpectedly. Restarting it."
        else -> "Unknown state."
    }
}
