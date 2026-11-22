package com.echobedside.feature.assistant.pipeline

/**
 * What the assistant screen shows (Assistant.dc.html):
 *  - [IDLE]: "How can I help?" (the wake word may be listening in the background).
 *  - [LISTENING]: the command is being heard and transcribed on the device.
 *  - [WORKING]: "Working on it…" with Claude's tool steps.
 *  - [ANSWER]: the answer text and result cards, spoken by the on-device TTS.
 *  - [ERROR]: a short apology in the answer layout.
 */
enum class Phase { IDLE, LISTENING, WORKING, ANSWER, ERROR }

enum class StepStatus { PENDING, BUSY, DONE, FAILED }

data class Step(val id: String, val label: String, val status: StepStatus)

data class AssistState(
    val phase: Phase = Phase.IDLE,
    /** The final transcript (what was sent to Claude). */
    val query: String = "",
    /** While listening: heard so far, and (demo only) the rest still to come, dimmed. */
    val heard: String = "",
    val unheard: String = "",
    val steps: List<Step> = emptyList(),
    val answer: String = "",
    val cards: List<ResultCard> = emptyList(),
    val errorCode: String? = null,
    val turnEnded: Boolean = false,
    /** Which demo produced this state ("a", "b", "c"), or null for a real turn. */
    val demo: String? = null,
)

/** The turn state machine. Pure, so it's unit-tested on the JVM. */
object AssistReducer {

    fun reduce(s: AssistState, e: TurnEvent): AssistState = when (e) {
        TurnEvent.ListenStart -> AssistState(phase = Phase.LISTENING)
        is TurnEvent.Partial -> if (s.phase == Phase.LISTENING) s.copy(heard = e.heard, unheard = e.unheard) else s
        is TurnEvent.Heard -> if (s.phase == Phase.LISTENING || s.phase == Phase.IDLE)
            s.copy(phase = Phase.WORKING, query = e.text, heard = "", unheard = "") else s
        is TurnEvent.StepsPlanned -> s.copy(steps = e.labels.map { Step(it, it, StepStatus.PENDING) })
        is TurnEvent.ToolStart -> toolStart(s, e)
        is TurnEvent.ToolDone -> s.copy(steps = s.steps.map {
            if (it.id == e.id) it.copy(status = if (e.ok) StepStatus.DONE else StepStatus.FAILED) else it
        })
        is TurnEvent.AnswerDelta -> s.copy(answer = s.answer + e.text)
        is TurnEvent.Answer -> s.copy(
            phase = Phase.ANSWER,
            answer = e.text.ifBlank { s.answer }.trim(),
            cards = e.cards,
            steps = s.steps.map { if (it.status == StepStatus.BUSY) it.copy(status = StepStatus.DONE) else it },
        )
        is TurnEvent.Failed -> s.copy(phase = Phase.ERROR, errorCode = e.code, answer = friendly(e.code), cards = emptyList())
        TurnEvent.TurnEnd -> s.copy(turnEnded = true)
    }

    private fun toolStart(s: AssistState, e: TurnEvent.ToolStart): AssistState {
        val label = ToolLabels.label(e.tool)
        var steps = s.steps
        // A planned step (demo) with this id or label starts; a repeat of the same kind of tool reuses its chip.
        val i = steps.indexOfFirst { it.id == e.id || (it.status == StepStatus.PENDING && it.label == label) }
            .takeIf { it >= 0 } ?: steps.indexOfFirst { it.label == label }
        steps = if (i >= 0) steps.toMutableList().also { it[i] = it[i].copy(id = e.id, status = StepStatus.BUSY) }
        else steps + Step(e.id, label, StepStatus.BUSY)
        return s.copy(phase = if (s.phase == Phase.ANSWER || s.phase == Phase.ERROR) s.phase else Phase.WORKING, steps = steps)
    }

    /** What the screen says for an error code. */
    fun friendly(code: String): String = when (code) {
        "no-speech" -> "Sorry, I didn’t catch that."
        "no-key" -> "Add an Anthropic API key to talk to Claude."
        "auth" -> "The Anthropic API key isn’t valid."
        "offline" -> "I can’t reach Claude right now. Check the Wi-Fi?"
        "busy" -> "Claude is busy. Try again in a moment?"
        "refusal" -> "Sorry, I can’t help with that."
        "no-models" -> "The voice models aren’t installed yet."
        "mic" -> "The microphone isn’t available."
        "muted" -> "The microphone is off."
        "timeout" -> "That took too long. Try again?"
        else -> "Something went wrong. Try again?"
    }
}

/** The STT model writes UPPERCASE without punctuation; the screen shows it as a sentence. */
object Transcript {
    private val SMALL_I = Regex("\\bi\\b")

    fun pretty(raw: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return ""
        val s = if (t == t.uppercase()) t.lowercase() else t
        return s.replace(SMALL_I, "I").replace('\'', '’').replaceFirstChar { it.uppercase() }
    }
}

/** Short step labels for the tools Claude calls ("Working on it…" chips). */
object ToolLabels {
    private val map = linkedMapOf(
        "weather" to "Weather",
        "forecast" to "Weather",
        "calendar" to "Calendar",
        "event" to "Calendar",
        "traffic" to "Traffic",
        "commute" to "Traffic",
        "airplay" to "AirPlay",
        "display" to "Display", // before "play"
        "bright" to "Brightness",
        "media" to "Music",
        "music" to "Music",
        "play" to "Music",
        "timer" to "Timer",
        "alarm" to "Alarm",
        "volume" to "Volume",
        "light" to "Lights",
        "home" to "Home",
    )

    fun label(tool: String): String {
        val key = tool.lowercase().replace("_", "").replace("-", "")
        for ((k, v) in map) if (key.contains(k)) return v
        val words = tool.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').trim().lowercase()
        return words.replaceFirstChar { it.uppercase() }.ifEmpty { "Thinking" }
    }
}
