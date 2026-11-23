package app.vesper.feature.assistant.llm

import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.TurnEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * One assistant turn with Claude: the transcript goes to the Messages API with the display's
 * tools; tool calls run on [tools] (each one a "Working on it…" step); the loop ends with a short
 * spoken answer plus the cards the tools produced.
 *
 * - Model: Claude Haiku 5.5 by default (the settings page offers Sonnet and Opus); thinking off or
 *   minimal and effort low, since a voice turn is latency-bound ([ClaudeModels]).
 * - Tool inputs stream eagerly, so each is validated before it runs; a bad one goes back as an
 *   `is_error` tool_result (INVALID_JSON / INVALID_INPUT) and Claude retries.
 * - All tool results of one response go back in one user message; assistant content goes back unchanged.
 * - `refusal` and `max_tokens` stop reasons are checked before any tool runs.
 * - One retry for overloaded / rate-limited / network errors, only if nothing streamed yet.
 */
class AssistantAgent(
    private val api: ClaudeApi,
    private val tools: ToolHost,
    /** The Claude model id, read per turn (the settings CHOICE). */
    val model: () -> String = { ClaudeModels.DEFAULT },
    private val name: () -> String = { "Nova" },
    private val location: () -> String = { "" },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = 800,
) {
    /** [model]: the model that wrote [history] (history is only reused with the same model). */
    class Result(val answer: String, val cards: List<ResultCard>, val history: List<JSONObject>, val model: String)

    /**
     * Runs the turn. [history] is the previous turns' messages (a follow-up within a minute), [emit]
     * gets ToolStart / ToolDone events (from any thread). Throws [ClaudeException].
     */
    suspend fun turn(query: String, history: List<JSONObject>, emit: (TurnEvent) -> Unit): Result {
        val messages = ArrayList(history)
        messages += JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", context() + query)))
        val specs = tools.specs()
        val byName = specs.associateBy { it.name }
        val toolsJson = JSONArray(specs.map { it.toJson() })
        val cards = ArrayList<ResultCard>()
        val model = ClaudeModels.of(model())
        repeat(MAX_STEPS) {
            val msg = send(model, messages, toolsJson, emit)
            when (msg.stopReason) {
                "refusal" -> throw ClaudeException("refusal", "refused")
                "max_tokens" -> {
                    val t = msg.text.trim()
                    if (t.isEmpty()) throw ClaudeException("unknown", "max_tokens")
                    messages += JSONObject().put("role", "assistant").put("content", msg.contentForHistory())
                    return Result(t, pick(cards), messages, model)
                }
            }
            val uses = msg.toolUses
            messages += JSONObject().put("role", "assistant").put("content", msg.contentForHistory())
            if (msg.stopReason != "tool_use" || uses.isEmpty()) {
                return Result(msg.text.trim(), pick(cards), messages, model)
            }
            val results = JSONArray()
            for (u in uses) {
                val spec = byName[u.name]
                val outcome = when {
                    spec == null -> ToolOutcome.error("UNKNOWN_TOOL", "No tool named ${u.name}.")
                    u.input == null -> ToolOutcome.error("INVALID_JSON", "The tool input wasn't valid JSON. Send it again.")
                    else -> spec.validate(u.input)?.let { ToolOutcome.error("INVALID_INPUT", it) }
                        ?: try { tools.run(u.name, u.input) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { ToolOutcome.error("FAILED", "The tool failed (${e.javaClass.simpleName}).") }
                }
                outcome.card?.let(cards::add)
                emit(TurnEvent.ToolDone(u.id, ok = !outcome.isError))
                results.put(JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", u.id)
                    .put("content", outcome.content.toString())
                    .also { if (outcome.isError) it.put("is_error", true) })
            }
            messages += JSONObject().put("role", "user").put("content", results)
        }
        throw ClaudeException("timeout", "too many tool steps")
    }

    private suspend fun send(model: String, messages: List<JSONObject>, toolsJson: JSONArray, emit: (TurnEvent) -> Unit): ClaudeMessage {
        var attempt = 0
        while (true) {
            var streamed = false
            val listener = object : StreamListener {
                override fun onText(delta: String) { streamed = true; emit(TurnEvent.AnswerDelta(delta)) }
                override fun onToolUse(id: String, name: String) { streamed = true; emit(TurnEvent.ToolStart(id, name)) }
            }
            try {
                return api.stream(body(messages, toolsJson, model), listener)
            } catch (e: ClaudeException) {
                if (!e.retryable || streamed || attempt >= 1) throw e
                attempt++
                delay(retryDelayMs)
            }
        }
    }

    internal fun body(messages: List<JSONObject>, toolsJson: JSONArray, model: String = ClaudeModels.of(model())): JSONObject {
        val b = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", systemPrompt())
        ClaudeModels.thinking(model)?.let { b.put("thinking", it) }
        return b
            .put("output_config", JSONObject().put("effort", "low"))
            .put("tools", toolsJson)
            .put("messages", JSONArray(messages))
    }

    internal fun systemPrompt() = """
        You are ${name()}, the voice assistant on a bedside smart display. Your reply is spoken aloud by text-to-speech and shown in large type, so answer in one to three short sentences of plain speech: no markdown, lists, emoji or links.
        Use the tools to read and change this display: timers (start, pause, resume, cancel), alarms (set with leave-by and skip holidays, next alarm, on/off, delete), reminders (at a time: add, list, change, done, cancel), lists (the shopping list by default: add, read, remove, clear), weather, air quality, rain in the next hour, calendar (per person), travel time, the iPhone's battery, music, volume, brightness, Do Not Disturb, Night mode, camera, opening screens, switching profile, showing a note on screen, sleep sounds with a sleep timer, and the bedtime routine for "good night". Only say you did something when a tool confirmed it; if a tool fails, say so briefly. When a request is a little ambiguous, make the sensible choice and say what you did instead of asking. Before deleting an alarm or clearing a whole list, ask the user to confirm unless they plainly said to. For a reminder, work out the local date-time from the context's local time; if no time was given at all, ask when. Say times the way people speak them ("seven thirty", "9:30").
        If something is outside what the tools can do, say so in one sentence.
    """.trimIndent()

    private fun context(): String {
        val t = Instant.ofEpochMilli(now()).atZone(zone())
        val f = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a", Locale.US)
        val loc = location().takeIf { it.isNotBlank() }?.let { " Location: $it." } ?: ""
        return "<context>Local time: ${t.format(f)} (${t.zone.id}).$loc</context>\n\n"
    }

    /** Cards under the answer: one weather, one media, up to three actions (latest per name). */
    private fun pick(all: List<ResultCard>): List<ResultCard> {
        val media = all.filterIsInstance<ResultCard.Media>().lastOrNull()
        if (media != null && all.none { it is ResultCard.Action && it.icon != ResultCard.Icon.PLAY && it.icon != ResultCard.Icon.PAUSE }) return listOf(media)
        val weather = all.filterIsInstance<ResultCard.Weather>().lastOrNull()
        val actions = all.filterIsInstance<ResultCard.Action>().asReversed().distinctBy { it.name }.asReversed()
        return if (weather != null) listOf(weather) + actions.takeLast(1) else actions.takeLast(3)
    }

    companion object {
        const val MAX_TOKENS = 1024
        const val MAX_STEPS = 6
    }
}
