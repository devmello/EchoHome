package com.echobedside.feature.assistant.pipeline

/**
 * The three scripted demos from Assistant.dc.html (sample data), played as pipeline events with the
 * design's timing so they exercise the same state machine as a real Home Assistant run:
 *  - listening: one word "heard" every 260 ms, then 700 ms
 *  - working: each step starts 520 ms after the previous one, then 500 ms
 *  - answer: the screen reveals a word every 70 ms, then fades the cards in.
 */
object DemoScripts {
    data class Demo(val id: String, val chip: String, val color: Int, val query: String, val steps: List<String>, val answer: String)

    val all = listOf(
        Demo("a", "What’s tomorrow looking like?", 0xFF64D2FF.toInt(),
            "What’s tomorrow looking like, and do I need to leave earlier?",
            listOf("Weather", "Calendar", "Traffic"),
            "Rain starts around 8 tomorrow, and your 9:30 is 25 minutes away in that traffic. I’d get you up a little earlier."),
        Demo("b", "Movie mode in the bedroom", 0xFFBF5AF2.toInt(),
            "Set the bedroom up for a movie.",
            listOf("Home", "Lights", "AirPlay"),
            "Done. The lamp is down to a warm 10%, the TV is on, and I paused music here so it won’t talk over the film."),
        Demo("c", "Play something calm", 0xFFFF9F0A.toInt(),
            "Play something calm for winding down.",
            listOf("Music"),
            "Here’s Afterglow from Night Swim, a slow one. I’ll fade it out after 30 minutes."),
    )

    fun byId(id: String?): Demo = all.firstOrNull { it.id == id } ?: all[0]

    const val WORD_MS = 260L
    const val STEP_MS = 520L
    const val REVEAL_WORD_MS = 70L

    fun listenMs(d: Demo) = d.query.split(' ').size * WORD_MS + 700
    fun thinkMs(d: Demo) = d.steps.size * STEP_MS + 500
    /** How long the answer takes to reveal (the demo "speaks" for this long). */
    fun speakMs(d: Demo) = d.answer.split(' ').size * REVEAL_WORD_MS

    /** (ms from start, event) in order. The run itself begins with AssistReducer.begin("stt"). */
    fun timeline(d: Demo): List<Pair<Long, PipelineEvent>> {
        val out = ArrayList<Pair<Long, PipelineEvent>>()
        val words = d.query.split(' ')
        for (k in words.indices) {
            out += k * WORD_MS to PipelineEvent.DemoPartial(
                words.take(k + 1).joinToString(" "),
                if (k + 1 < words.size) " " + words.drop(k + 1).joinToString(" ") else "",
            )
        }
        val listen = listenMs(d)
        out += listen to PipelineEvent.SttEnd(d.query)
        out += listen to PipelineEvent.IntentStart(d.query)
        out += listen to PipelineEvent.DemoPlan(d.steps)
        d.steps.forEachIndexed { i, s ->
            out += listen + i * STEP_MS + 1 to PipelineEvent.IntentProgress(toolCalls = listOf(ToolCall(s, s)))
            out += listen + (i + 1) * STEP_MS + 1 to PipelineEvent.IntentProgress(toolResultId = s)
        }
        val answerAt = listen + thinkMs(d)
        val targets = if (d.id == "b") listOf(
            Target("Bedside lamp", "light.bedside_lamp"),
            Target("Bedroom TV", "media_player.bedroom_tv"),
            Target("This display", "media_player.echo_bedside"),
        ) else emptyList()
        out += answerAt to PipelineEvent.IntentEnd(
            speech = d.answer,
            responseType = if (d.id == "b") "action_done" else "query_answer",
            conversationId = "demo-${d.id}",
            continueConversation = false,
            targets = targets,
            failed = emptyList(),
            processedLocally = false,
            errorCode = null,
        )
        out += answerAt to PipelineEvent.RunEnd
        return out
    }
}
