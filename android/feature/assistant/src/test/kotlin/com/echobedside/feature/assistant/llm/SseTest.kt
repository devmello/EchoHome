package com.echobedside.feature.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SseTest {

    /** Feeds a raw SSE body through the parser and accumulator. */
    private fun accumulate(body: String, listener: StreamListener = object : StreamListener {}): ClaudeMessage {
        val p = SseParser()
        val acc = MessageAccumulator(listener)
        for (line in body.split("\n")) p.feed(line)?.let { acc.onEvent(it.first, it.second) }
        assertTrue(acc.done)
        return acc.result()
    }

    @Test fun `parser joins data lines and skips comments`() {
        val p = SseParser()
        assertNull(p.feed(": keep-alive"))
        assertNull(p.feed("event: ping"))
        assertNull(p.feed("data: {\"a\":"))
        assertNull(p.feed("data: 1}"))
        assertEquals("ping" to "{\"a\":\n1}", p.feed(""))
        assertNull(p.feed(""))
    }

    @Test fun `text and tool use with streamed json`() {
        val texts = StringBuilder()
        val tools = ArrayList<String>()
        val msg = accumulate(SseFixtures.toolUse("toolu_1", "set_timer", listOf("{\"duration_", "seconds\": 600, \"label\": \"Pasta\"}"), lead = "Sure. "),
            object : StreamListener {
                override fun onText(delta: String) { texts.append(delta) }
                override fun onToolUse(id: String, name: String) { tools += "$id:$name" }
            })
        assertEquals("tool_use", msg.stopReason)
        assertEquals("Sure. ", msg.text)
        assertEquals("Sure. ", texts.toString())
        assertEquals(listOf("toolu_1:set_timer"), tools)
        val u = msg.toolUses.single()
        assertEquals(600, u.input!!.getInt("duration_seconds"))
        assertEquals("Pasta", u.input!!.getString("label"))
    }

    @Test fun `invalid streamed json is flagged and sent back as an empty input`() {
        val msg = accumulate(SseFixtures.toolUse("toolu_2", "set_alarm", listOf("{\"hour\": 7, \"min")))
        val u = msg.toolUses.single()
        assertNull(u.input)
        val back = msg.contentForHistory().getJSONObject(0)
        assertEquals(0, back.getJSONObject("input").length())
        assertTrue(!back.has("__invalid_json"))
    }

    @Test fun `tool use without input deltas gets an empty object`() {
        val msg = accumulate(SseFixtures.toolUse("toolu_3", "get_weather", emptyList()))
        assertEquals(0, msg.toolUses.single().input!!.length())
    }

    @Test fun `thinking blocks keep text and signature`() {
        val body = SseFixtures.events(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":10}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Hmm"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig=="}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hi."}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val msg = accumulate(body)
        val th = msg.content.getJSONObject(0)
        assertEquals("Hmm", th.getString("thinking"))
        assertEquals("sig==", th.getString("signature"))
        assertEquals("Hi.", msg.text)
        assertEquals("end_turn", msg.stopReason)
        assertEquals(10, msg.usage!!.getInt("input_tokens"))
        assertEquals(5, msg.usage!!.getInt("output_tokens"))
    }

    @Test fun `stream error events become ClaudeException`() {
        val acc = MessageAccumulator()
        try {
            acc.onEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
            fail()
        } catch (e: ClaudeException) {
            assertEquals("busy", e.code)
            assertTrue(e.retryable)
        }
    }
}

/** SSE bodies like the Messages API sends. */
object SseFixtures {
    fun events(vararg e: Pair<String, String>) = e.joinToString("") { (t, d) -> "event: $t\ndata: $d\n\n" }

    fun text(text: String, stop: String = "end_turn") = events(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":12}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "ping" to """{"type":"ping"}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${org.json.JSONObject.quote(text)}}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":7}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    fun toolUse(id: String, name: String, parts: List<String>, lead: String? = null): String {
        val ev = ArrayList<Pair<String, String>>()
        ev += "message_start" to """{"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":12}}}"""
        var i = 0
        if (lead != null) {
            ev += "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
            ev += "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":${org.json.JSONObject.quote(lead)}}}"""
            ev += "content_block_stop" to """{"type":"content_block_stop","index":0}"""
            i = 1
        }
        ev += "content_block_start" to """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}"""
        for (p in parts) ev += "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":${org.json.JSONObject.quote(p)}}}"""
        ev += "content_block_stop" to """{"type":"content_block_stop","index":$i}"""
        ev += "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}"""
        ev += "message_stop" to """{"type":"message_stop"}"""
        return events(*ev.toTypedArray())
    }
}
