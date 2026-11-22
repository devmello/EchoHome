package app.vesper.feature.assistant.llm

import org.json.JSONObject

/**
 * The models the settings page offers, and how each one turns thinking off (a voice turn is
 * latency-bound, so thinking is off or minimal and effort is low):
 *  - Haiku 5.5: `thinking: {type: "disabled"}`.
 *  - Sonnet 5.5: "disabled" is rejected; `between_tools` (allowed at effort high or below) skips
 *    thinking on the first step and only thinks between tool calls.
 *  - Opus 5.5: thinking can't be turned off, so it's left out and effort is the only control.
 * Thinking blocks belong to the model that wrote them: the engine drops the history on a model change.
 */
object ClaudeModels {
    const val HAIKU = "claude-haiku-5-5"
    const val SONNET = "claude-sonnet-5-5"
    const val OPUS = "claude-opus-5-5"
    const val DEFAULT = HAIKU

    val OPTIONS = listOf(
        HAIKU to "Claude Haiku 5.5 (fastest)",
        SONNET to "Claude Sonnet 5.5",
        OPUS to "Claude Opus 5.5 (slowest)",
    )

    fun of(id: String): String = if (OPTIONS.any { it.first == id }) id else DEFAULT

    /** The `thinking` parameter for [model], or null to leave it out. */
    fun thinking(model: String): JSONObject? = when (model) {
        HAIKU -> JSONObject().put("type", "disabled")
        SONNET -> JSONObject().put("type", "between_tools")
        else -> null
    }
}
