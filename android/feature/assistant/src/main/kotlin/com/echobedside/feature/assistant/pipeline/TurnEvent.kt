package com.echobedside.feature.assistant.pipeline

/**
 * What happens during one assistant turn (NOTES §6: on-device wake word / STT / TTS + Claude).
 * Produced by the engine (mic → streaming STT → Claude with tools → TTS) and by the scripted
 * demos, and folded into [AssistState] by [AssistReducer].
 */
sealed interface TurnEvent {
    /** Listening started (droplet tap or wake word). */
    data object ListenStart : TurnEvent
    /** Streaming transcript: what's been heard so far, plus (demo only) the words still to come. */
    data class Partial(val heard: String, val unheard: String = "") : TurnEvent
    /** The final transcript; the request goes to Claude next. */
    data class Heard(val text: String) : TurnEvent
    /** Demo: the steps are known up front and shown dimmed until they start. */
    data class StepsPlanned(val labels: List<String>) : TurnEvent
    /** Claude called a tool (a "Working on it…" step). */
    data class ToolStart(val id: String, val tool: String) : TurnEvent
    data class ToolDone(val id: String, val ok: Boolean = true) : TurnEvent
    /** Streamed answer text. */
    data class AnswerDelta(val text: String) : TurnEvent
    /** The final answer with its result cards. */
    data class Answer(val text: String, val cards: List<ResultCard> = emptyList()) : TurnEvent
    data class Failed(val code: String) : TurnEvent
    /** The turn is over (after the answer was spoken). */
    data object TurnEnd : TurnEvent
}

/** Cards under the answer (Assistant.dc.html): what the tools did or found. */
sealed interface ResultCard {
    /** Demo A's left card: "Tomorrow · Rain from 8 AM · 70% chance · H:58° L:47°". */
    data class Weather(val label: String, val headline: String, val detail: String, val rain: Boolean) : ResultCard
    /** Demo A's glass sheet: a suggestion the user can accept (moving the alarm). */
    data class AlarmSuggestion(val title: String, val detail: String, val accept: String, val decline: String,
                               val doneTitle: String, val doneDetail: String) : ResultCard
    /** Demo B's tiles: one thing that was done (lamp, TV, timer, alarm...). */
    data class Action(val name: String, val state: String, val icon: Icon, val ok: Boolean = true) : ResultCard
    /** Demo C: hands off to Now Playing. */
    data class Media(val title: String, val subtitle: String) : ResultCard

    enum class Icon { LAMP, TV, PAUSE, PLAY, TIMER, ALARM, POWER, CALENDAR }
}
