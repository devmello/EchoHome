package com.echobedside.feature.assistant.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistReducerTest {

    private fun run(events: List<TurnEvent>, from: AssistState = AssistState()) =
        events.fold(from) { s, e -> AssistReducer.reduce(s, e) }

    @Test fun `demo timeline goes listening, working with steps, answer with cards`() {
        val d = DemoScripts.byId("a")
        val tl = DemoScripts.timeline(d)
        val listenEnd = DemoScripts.listenMs(d)

        val listening = run(tl.filter { it.first < listenEnd }.map { it.second })
        assertEquals(Phase.LISTENING, listening.phase)
        assertEquals(d.query, (listening.heard + listening.unheard).trim())

        val halfway = run(tl.filter { it.first <= listenEnd + DemoScripts.STEP_MS + 1 }.map { it.second })
        assertEquals(Phase.WORKING, halfway.phase)
        assertEquals(d.query, halfway.query)
        assertEquals(listOf("Weather", "Calendar", "Traffic"), halfway.steps.map { it.label })
        assertEquals(listOf(StepStatus.DONE, StepStatus.BUSY, StepStatus.PENDING), halfway.steps.map { it.status })

        val end = run(tl.map { it.second })
        assertEquals(Phase.ANSWER, end.phase)
        assertEquals(d.answer, end.answer)
        assertEquals(2, end.cards.size)
        assertTrue(end.turnEnded)
        assertTrue(end.steps.all { it.status == StepStatus.DONE })
    }

    @Test fun `first partial word is heard, the rest unheard`() {
        val tl = DemoScripts.timeline(DemoScripts.byId("b"))
        val s = run(tl.take(2).map { it.second })
        assertEquals("Set", s.heard)
        assertEquals(" the bedroom up for a movie.", s.unheard)
    }

    @Test fun `real tool calls add steps and repeats reuse the chip`() {
        val s = run(listOf(
            TurnEvent.ListenStart, TurnEvent.Heard("Set a timer"),
            TurnEvent.ToolStart("t1", "list_timers"), TurnEvent.ToolDone("t1"),
            TurnEvent.ToolStart("t2", "set_timer"),
            TurnEvent.ToolStart("t3", "get_weather"), TurnEvent.ToolDone("t3", ok = false),
        ))
        assertEquals(listOf("Timer", "Weather"), s.steps.map { it.label })
        assertEquals(listOf(StepStatus.BUSY, StepStatus.FAILED), s.steps.map { it.status })
        assertEquals("t2", s.steps[0].id)
    }

    @Test fun `failure shows a friendly line`() {
        val s = run(listOf(TurnEvent.ListenStart, TurnEvent.Failed("offline")))
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("offline", s.errorCode)
        assertTrue(s.answer.contains("Claude"))
    }

    @Test fun `late partials after the transcript are ignored`() {
        val s = run(listOf(TurnEvent.ListenStart, TurnEvent.Heard("hi"), TurnEvent.Partial("hi there")))
        assertEquals("", s.heard)
        assertEquals(Phase.WORKING, s.phase)
    }

    @Test fun `transcripts read as sentences`() {
        assertEquals("What’s the weather like tomorrow", Transcript.pretty("WHAT'S THE WEATHER LIKE TOMORROW"))
        assertEquals("Can I set an alarm", Transcript.pretty("  CAN I   SET AN ALARM "))
        assertEquals("", Transcript.pretty("   "))
        assertEquals("Already Mixed case", Transcript.pretty("Already Mixed case"))
    }

    @Test fun `tool labels`() {
        assertEquals("Timer", ToolLabels.label("set_timer"))
        assertEquals("Alarm", ToolLabels.label("update_alarm"))
        assertEquals("Weather", ToolLabels.label("get_weather"))
        assertEquals("Calendar", ToolLabels.label("get_calendar"))
        assertEquals("Music", ToolLabels.label("now_playing"))
        assertEquals("Music", ToolLabels.label("media_control"))
        assertEquals("Volume", ToolLabels.label("set_volume"))
        assertEquals("Brightness", ToolLabels.label("set_brightness"))
        assertEquals("Display", ToolLabels.label("get_display"))
        assertEquals("Home", ToolLabels.label("home_control"))
        assertEquals("AirPlay", ToolLabels.label("AirPlay"))
        assertEquals("Do thing", ToolLabels.label("do_thing"))
    }
}
