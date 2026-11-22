package com.echobedside.feature.assistant.pipeline

import com.echobedside.feature.assistant.pipeline.ResultCard.Icon

/**
 * The three scripted demos from Assistant.dc.html (sample data), played as [TurnEvent]s with the
 * design's timing so they drive the same state machine as a real turn:
 *  - listening: one word "heard" every 260 ms, then 700 ms
 *  - working: each step starts 520 ms after the previous one, then 500 ms
 *  - answer: the screen reveals a word every 70 ms, then fades the cards in.
 * Used in demo mode (demo data on and no Anthropic key) and for the screenshots.
 */
object DemoScripts {
    data class Demo(
        val id: String, val chip: String, val color: Int, val query: String,
        val steps: List<String>, val answer: String, val cards: List<ResultCard>,
    )

    val all = listOf(
        Demo("a", "What’s tomorrow looking like?", 0xFF64D2FF.toInt(),
            "What’s tomorrow looking like, and do I need to leave earlier?",
            listOf("Weather", "Calendar", "Traffic"),
            "Rain starts around 8 tomorrow, and your 9:30 is 25 minutes away in that traffic. I’d get you up a little earlier.",
            listOf(
                ResultCard.Weather("Tomorrow", "Rain from 8 AM", "70% chance · H:58° L:47°", rain = true),
                ResultCard.AlarmSuggestion(
                    "Move your alarm to 6:40 AM?", "Leaves 25 minutes for rain traffic before your 9:30.",
                    "Move alarm", "Keep 7:00", "Alarm moved to 6:40 AM", "Sunrise light starts at 6:10.",
                ),
            )),
        Demo("b", "Movie mode in the bedroom", 0xFFBF5AF2.toInt(),
            "Set the bedroom up for a movie.",
            listOf("Home", "Lights", "AirPlay"),
            "Done. The lamp is down to a warm 10%, the TV is on, and I paused music here so it won’t talk over the film.",
            listOf(
                ResultCard.Action("Bedside lamp", "10% · warm", Icon.LAMP),
                ResultCard.Action("Bedroom TV", "On · AirPlay ready", Icon.TV),
                ResultCard.Action("This display", "Music paused", Icon.PAUSE),
            )),
        Demo("c", "Play something calm", 0xFFFF9F0A.toInt(),
            "Play something calm for winding down.",
            listOf("Music"),
            "Here’s Afterglow from Night Swim, a slow one. I’ll fade it out after 30 minutes.",
            listOf(ResultCard.Media("Afterglow (Slow)", "Kito Rae · on this display"))),
    )

    fun byId(id: String?): Demo = all.firstOrNull { it.id == id } ?: all[0]

    const val WORD_MS = 260L
    const val STEP_MS = 520L
    const val REVEAL_WORD_MS = 70L

    fun listenMs(d: Demo) = d.query.split(' ').size * WORD_MS + 700
    fun thinkMs(d: Demo) = d.steps.size * STEP_MS + 500
    /** How long the answer takes to reveal (the demo "speaks" for this long). */
    fun speakMs(d: Demo) = d.answer.split(' ').size * REVEAL_WORD_MS

    /** (ms from start, event) in order, starting with [TurnEvent.ListenStart] at 0. */
    fun timeline(d: Demo): List<Pair<Long, TurnEvent>> {
        val out = ArrayList<Pair<Long, TurnEvent>>()
        out += 0L to TurnEvent.ListenStart
        val words = d.query.split(' ')
        for (k in words.indices) {
            out += k * WORD_MS to TurnEvent.Partial(
                words.take(k + 1).joinToString(" "),
                if (k + 1 < words.size) " " + words.drop(k + 1).joinToString(" ") else "",
            )
        }
        val listen = listenMs(d)
        out += listen to TurnEvent.Heard(d.query)
        out += listen to TurnEvent.StepsPlanned(d.steps)
        d.steps.forEachIndexed { i, s ->
            out += listen + i * STEP_MS + 1 to TurnEvent.ToolStart(s, s)
            out += listen + (i + 1) * STEP_MS + 1 to TurnEvent.ToolDone(s)
        }
        val answerAt = listen + thinkMs(d)
        out += answerAt to TurnEvent.Answer(d.answer, d.cards)
        out += answerAt + speakMs(d) to TurnEvent.TurnEnd
        return out
    }
}
