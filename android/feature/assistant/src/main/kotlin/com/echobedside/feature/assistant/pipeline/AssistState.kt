package com.echobedside.feature.assistant.pipeline

/**
 * What the assistant is doing, as the screen shows it (Assistant.dc.html):
 *  - [IDLE]: "How can I help?". The mic may be streaming for the server-side wake word
 *    ([AssistState.wakeListening]) but nothing is shown for it.
 *  - [LISTENING]: wake word heard (or tapped): the command is being recorded / transcribed.
 *  - [WORKING]: "Working on it…" with the agent's tool steps.
 *  - [ANSWER]: the answer text, result cards, TTS.
 *  - [ERROR]: a short apology in the answer layout.
 */
enum class Phase { IDLE, LISTENING, WORKING, ANSWER, ERROR }

enum class StepStatus { PENDING, BUSY, DONE }

data class Step(val id: String, val label: String, val status: StepStatus)

data class AssistState(
    val phase: Phase = Phase.IDLE,
    /** A run is streaming mic audio for the wake word (background, nothing on screen). */
    val wakeListening: Boolean = false,
    val handlerId: Int? = null,
    /** The final transcript (stt-end / intent input). */
    val query: String = "",
    /** Demo partial transcript; real STT isn't streamed, so these stay empty with HA. */
    val heard: String = "",
    val unheard: String = "",
    val steps: List<Step> = emptyList(),
    val answer: String = "",
    val responseType: String? = null,
    val targets: List<Target> = emptyList(),
    val ttsUrl: String? = null,
    val conversationId: String? = null,
    val continueConversation: Boolean = false,
    val errorCode: String? = null,
    val runEnded: Boolean = false,
    /** Which demo produced this state ("a", "b", "c"), or null for a real HA run. */
    val demo: String? = null,
)

/**
 * The pipeline event state machine. Pure, so it's unit-tested on the JVM; [AssistantEngine] feeds it
 * HA events and the scripted demos feed it the same events.
 */
object AssistReducer {

    /** State at the start of a run beginning at [startStage] ("wake_word", "stt", "intent"). */
    fun begin(prev: AssistState, startStage: String, text: String? = null, demo: String? = null): AssistState = when (startStage) {
        // A background wake-word run keeps whatever answer is on screen.
        "wake_word" -> prev.copy(wakeListening = true, handlerId = null, runEnded = false, errorCode = null)
        "stt" -> AssistState(phase = Phase.LISTENING, conversationId = prev.conversationId, demo = demo)
        else -> AssistState(phase = Phase.WORKING, query = text.orEmpty(), conversationId = prev.conversationId, demo = demo)
    }

    fun reduce(s: AssistState, e: PipelineEvent): AssistState = when (e) {
        is PipelineEvent.RunStart -> s.copy(handlerId = e.handlerId, conversationId = e.conversationId ?: s.conversationId, runEnded = false)
        PipelineEvent.WakeWordStart -> s
        is PipelineEvent.WakeWordEnd ->
            if (e.detected) AssistState(phase = Phase.LISTENING, handlerId = s.handlerId, conversationId = s.conversationId, demo = s.demo)
            else s
        PipelineEvent.SttStart -> if (s.phase == Phase.IDLE || s.phase == Phase.ANSWER || s.phase == Phase.ERROR) s.copy(phase = Phase.LISTENING) else s
        PipelineEvent.SttVadStart, PipelineEvent.SttVadEnd -> s
        is PipelineEvent.SttEnd -> s.copy(query = e.text, heard = e.text, unheard = "", wakeListening = false)
        is PipelineEvent.DemoPartial -> s.copy(phase = Phase.LISTENING, heard = e.heard, unheard = e.unheard)
        is PipelineEvent.IntentStart -> s.copy(
            phase = Phase.WORKING, wakeListening = false,
            query = s.query.ifBlank { e.text }, heard = "", unheard = "",
        )
        is PipelineEvent.DemoPlan -> s.copy(steps = e.steps.map { Step(it, it, StepStatus.PENDING) })
        is PipelineEvent.IntentProgress -> progress(s, e)
        is PipelineEvent.IntentEnd -> {
            val error = e.responseType == "error"
            s.copy(
                phase = Phase.ANSWER,
                answer = e.speech.ifBlank { s.answer },
                responseType = e.responseType,
                targets = e.targets,
                steps = s.steps.map { if (it.status == StepStatus.BUSY) it.copy(status = StepStatus.DONE) else it },
                conversationId = e.conversationId ?: s.conversationId,
                continueConversation = e.continueConversation,
                errorCode = if (error) (e.errorCode ?: "intent-error") else null,
            )
        }
        is PipelineEvent.TtsStart -> s
        is PipelineEvent.TtsEnd -> s.copy(ttsUrl = e.url)
        PipelineEvent.RunEnd -> s.copy(runEnded = true, wakeListening = false, handlerId = null)
        is PipelineEvent.Error -> error(s, e)
        is PipelineEvent.Unknown -> s
    }

    private fun progress(s: AssistState, e: PipelineEvent.IntentProgress): AssistState {
        var steps = s.steps
        for (call in e.toolCalls) {
            val label = ToolLabels.label(call.name)
            // A planned step with the same label (demo) or an earlier call of the same tool → busy again.
            val i = steps.indexOfFirst { it.id == call.id || (it.status == StepStatus.PENDING && it.label == label) }
            steps = if (i >= 0) steps.toMutableList().also { it[i] = it[i].copy(id = call.id, status = StepStatus.BUSY) }
            else if (steps.any { it.label == label && it.status != StepStatus.PENDING }) {
                // Same kind of step again (e.g. two Home calls): show it once, busy.
                steps.map { if (it.label == label) it.copy(id = call.id, status = StepStatus.BUSY) else it }
            } else steps + Step(call.id, label, StepStatus.BUSY)
        }
        if (e.toolResultId != null || e.toolResultName != null) {
            val label = e.toolResultName?.let(ToolLabels::label)
            val i = steps.indexOfFirst { it.id == e.toolResultId }.takeIf { it >= 0 }
                ?: steps.indexOfFirst { it.label == label && it.status == StepStatus.BUSY }
            if (i >= 0) steps = steps.toMutableList().also { it[i] = it[i].copy(status = StepStatus.DONE) }
        }
        val answer = if (e.textDelta != null) s.answer + e.textDelta else s.answer
        return s.copy(steps = steps, answer = answer, phase = if (s.phase == Phase.IDLE || s.phase == Phase.LISTENING) Phase.WORKING else s.phase)
    }

    private fun error(s: AssistState, e: PipelineEvent.Error): AssistState = when {
        // Benign ends of a background wake-word run: just start the next one.
        s.phase == Phase.IDLE || (s.wakeListening && s.phase != Phase.LISTENING) ->
            s.copy(wakeListening = false, handlerId = null)
        // The run timeout arrives after run-end; an answer already on screen stays.
        e.code == "timeout" && s.phase == Phase.ANSWER -> s
        else -> s.copy(phase = Phase.ERROR, errorCode = e.code, answer = friendly(e.code), wakeListening = false, handlerId = null, targets = emptyList())
    }

    /** What the screen says for an error code (HA's messages are developer-facing). */
    fun friendly(code: String): String = when (code) {
        "stt-no-text-recognized" -> "Sorry, I didn’t catch that."
        "stt-stream-failed", "stt-provider-missing", "stt-provider-unsupported-metadata" -> "Speech recognition isn’t working right now."
        "intent-not-supported", "intent-failed", "intent-agent-not-found", "intent-error" -> "Sorry, I couldn’t do that."
        "tts-not-supported" -> "I have an answer, but I can’t speak it."
        "wake-engine-missing", "wake-provider-missing", "wake-stream-failed" -> "Wake word detection isn’t set up in Home Assistant."
        "duplicate_wake_up_detected" -> "Another speaker answered that."
        "cloud-auth-failed" -> "Home Assistant Cloud sign-in failed."
        "disconnected", "not_connected" -> "Lost the connection to Home Assistant."
        "timeout" -> "That took too long. Try again?"
        "not_found", "pipeline-not-found" -> "The assistant pipeline isn’t set up in Home Assistant."
        else -> "Something went wrong. Try again?"
    }
}

/** Short step labels for the agent's tool calls ("Working on it…" chips). */
object ToolLabels {
    private val map = linkedMapOf(
        "weather" to "Weather",
        "forecast" to "Weather",
        "calendar" to "Calendar",
        "event" to "Calendar",
        "travel" to "Traffic",
        "traffic" to "Traffic",
        "commute" to "Traffic",
        "waze" to "Traffic",
        "airplay" to "AirPlay",
        "media" to "Music",
        "music" to "Music",
        "play" to "Music",
        "timer" to "Timer",
        "alarm" to "Alarm",
        "light" to "Lights",
        "climate" to "Climate",
        "temperature" to "Climate",
        "todo" to "Lists",
        "shopping" to "Lists",
        "search" to "Web",
        "web" to "Web",
        "livecontext" to "Home",
        "turnon" to "Home",
        "turnoff" to "Home",
        "toggle" to "Home",
        "hass" to "Home",
    )

    fun label(tool: String): String {
        val key = tool.lowercase().replace("_", "").replace("-", "").replace(".", "")
        for ((k, v) in map) if (key.contains(k)) return v
        // Fallback: "get_stock_price" → "Get stock price"
        val words = tool.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('.', ' ').trim().lowercase()
        return words.replaceFirstChar { it.uppercase() }.ifEmpty { "Thinking" }
    }
}
