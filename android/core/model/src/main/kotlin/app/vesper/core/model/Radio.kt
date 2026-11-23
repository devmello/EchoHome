package app.vesper.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Internet radio (`:feature:radio`, Radio Browser directory). The assistant's `play_radio` /
 * `stop_radio` tools use it; Now Playing, the island and Control Center see the station through
 * [MediaHub] (source id "radio", [MediaSourceKind.OTHER_APP] with the station as the label).
 * Nothing plays unless a person asks (a tap or a voice request).
 */
interface RadioControl {
    /** false = no radio on this build (tools say so). */
    val available: Boolean get() = true
    /** What's on now, or null when the radio is stopped. */
    val nowPlaying: StateFlow<RadioNowPlaying?>

    /**
     * Finds the best station for [query] (a name, genre, place or "my favourites") and plays it.
     * Favourites match first, then the directory. Suspends for the search (a few seconds at most).
     */
    suspend fun play(query: String): RadioPlayResult

    /** Stops the radio. false = it wasn't playing. */
    fun stop(): Boolean
}

data class RadioNowPlaying(
    val station: String,
    /** The stream's current song ("Artist - Title" from ICY metadata), if it sends one. */
    val song: String? = null,
    val playing: Boolean = true,
)

data class RadioPlayResult(
    val ok: Boolean,
    val station: String = "",
    /** e.g. "Jazz · France · MP3 128 kbps". */
    val detail: String = "",
    /** NO_MATCH, OFFLINE, PLAYBACK; with ok = false. */
    val error: String? = null,
    val message: String = "",
)

object NoRadio : RadioControl {
    override val available = false
    override val nowPlaying: StateFlow<RadioNowPlaying?> = MutableStateFlow(null)
    override suspend fun play(query: String) = RadioPlayResult(false, error = "NOT_AVAILABLE", message = "There's no radio on this display.")
    override fun stop() = false
}
