package app.vesper.feature.assistant.llm

import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.llm.Schema.props
import app.vesper.feature.assistant.llm.Schema.str
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import org.json.JSONObject

/**
 * Internet radio (services.radio, `:feature:radio`): play a station by name, genre, country or
 * "my favourites", and stop it. Only on a person's request, like every tool. Slots read at call time.
 */
internal object RadioTools {
    val SPECS = listOf(
        ToolSpec("play_radio", "Play an internet radio station on this display (Radio Browser directory). " +
            "query is a station name (\"KEXP\", \"BBC Radio 3\"), a genre (\"jazz\", \"lo-fi\", \"news\"), a genre with a country " +
            "(\"jazz from France\") or \"favourites\" for the user's favourite / last station. Only when the user asks for radio.",
            props("query" to str("What to play, in the user's words.", max = 120)), listOf("query")),
        ToolSpec("stop_radio", "Stop the internet radio on this display."),
    )

    /** null = not one of these tools. */
    suspend fun run(s: VesperServices, name: String, input: JSONObject): ToolOutcome? = when (name) {
        "play_radio" -> play(s, input.getString("query"))
        "stop_radio" -> {
            val station = s.radio.nowPlaying.value?.station
            if (!s.radio.stop()) ToolOutcome(JSONObject().put("ok", true).put("was_playing", false))
            else ToolOutcome(JSONObject().put("ok", true).put("was_playing", true).put("station", station ?: ""),
                card = ResultCard.Action(station ?: "Radio", "Stopped", Icon.PAUSE))
        }
        else -> null
    }

    private suspend fun play(s: VesperServices, query: String): ToolOutcome {
        if (!s.radio.available) return ToolOutcome.error("NOT_AVAILABLE", "There's no radio on this display.")
        val r = s.radio.play(query)
        if (!r.ok) return ToolOutcome.error(r.error ?: "FAILED", r.message.ifBlank { "Couldn't play that station." })
        return ToolOutcome(JSONObject().put("ok", true).put("station", r.station).put("detail", r.detail),
            card = ResultCard.Media(r.station, r.detail.ifBlank { "Internet radio" }))
    }
}
