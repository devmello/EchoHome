package app.vesper.feature.assistant.llm

import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.TurnEvent
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The tool loop end to end: AssistantAgent → ClaudeClient (OkHttp) → a fake Messages API. */
class AgentTest {
    private lateinit var server: MockWebServer
    private val ran = ArrayList<Pair<String, JSONObject>>()
    private val events = ArrayList<TurnEvent>()

    private val tools = object : ToolHost {
        override fun specs() = VesperTools.BASE
        override suspend fun run(name: String, input: JSONObject): ToolOutcome {
            ran += name to input
            return when (name) {
                "set_timer" -> ToolOutcome(JSONObject().put("timer_id", "t1"), card = ResultCard.Action("Pasta", "10 min · started", ResultCard.Icon.TIMER))
                "get_weather" -> ToolOutcome(JSONObject().put("now", JSONObject().put("temp", 51)), card = ResultCard.Weather("Now", "51°", "H:58° L:47°", false))
                else -> ToolOutcome.error("FAILED", "nope")
            }
        }
    }

    private fun agent(key: String = "sk-test-123") = AssistantAgent(
        ClaudeClient({ key }, baseUrl = server.url("/").toString().trimEnd('/')),
        tools, zone = { ZoneId.of("America/Los_Angeles") }, now = { 1_795_000_000_000L }, retryDelayMs = 1,
    )

    private fun sse(body: String) = MockResponse().setHeader("content-type", "text/event-stream").setBody(body)

    private fun bodyOf(r: RecordedRequest) = JSONObject(r.body.readUtf8())

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun `tool call then answer`() = runBlocking {
        server.enqueue(sse(SseFixtures.toolUse("toolu_1", "set_timer", listOf("{\"duration_seconds\": 600, ", "\"label\": \"Pasta\"}"))))
        server.enqueue(sse(SseFixtures.text("Your pasta timer is set for ten minutes.")))

        val r = agent().turn("Set a pasta timer for ten minutes", emptyList()) { events += it }

        assertEquals("Your pasta timer is set for ten minutes.", r.answer)
        assertEquals(listOf("set_timer"), ran.map { it.first })
        assertEquals(600, ran[0].second.getInt("duration_seconds"))
        assertEquals(listOf(TurnEvent.ToolStart("toolu_1", "set_timer"), TurnEvent.ToolDone("toolu_1", true)),
            events.filterNot { it is TurnEvent.AnswerDelta })
        // The answer text streams as it comes (the voice starts on its first sentence).
        assertEquals("Your pasta timer is set for ten minutes.",
            events.filterIsInstance<TurnEvent.AnswerDelta>().joinToString("") { it.text })
        assertEquals(1, r.cards.size)

        // Request 1: headers and body shape.
        val r1 = server.takeRequest()
        assertEquals("/v1/messages", r1.path)
        assertEquals("sk-test-123", r1.getHeader("x-api-key"))
        assertEquals("2026-10-21", r1.getHeader("anthropic-version"))
        val b1 = bodyOf(r1)
        assertEquals("claude-haiku-5-5", b1.getString("model"))
        assertTrue(b1.getBoolean("stream"))
        assertEquals("low", b1.getJSONObject("output_config").getString("effort"))
        assertEquals("disabled", b1.getJSONObject("thinking").getString("type"))
        for (k in listOf("temperature", "top_p", "top_k")) assertFalse(k, b1.has(k))
        val tool0 = b1.getJSONArray("tools").getJSONObject(0)
        assertTrue(tool0.getBoolean("eager_input_streaming"))
        assertEquals("object", tool0.getJSONObject("input_schema").getString("type"))
        val userText = b1.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(userText.startsWith("<context>Local time: "))
        assertTrue(userText.endsWith("Set a pasta timer for ten minutes"))

        // Request 2: assistant content passed back, then one user message with the tool_result.
        val msgs = bodyOf(server.takeRequest()).getJSONArray("messages")
        assertEquals(3, msgs.length())
        val asst = msgs.getJSONObject(1)
        assertEquals("assistant", asst.getString("role"))
        val tu = asst.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", tu.getString("type"))
        assertEquals("Pasta", tu.getJSONObject("input").getString("label"))
        val res = msgs.getJSONObject(2)
        assertEquals("user", res.getString("role"))
        val tr = res.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", tr.getString("type"))
        assertEquals("toolu_1", tr.getString("tool_use_id"))
        assertFalse(tr.has("is_error"))
        assertEquals("t1", JSONObject(tr.getString("content")).getString("timer_id"))
        // History ends with the final assistant message, ready for a follow-up.
        assertEquals("assistant", r.history.last().getString("role"))
    }

    @Test fun `invalid json and schema errors go back as is_error results without running`() = runBlocking {
        server.enqueue(sse(SseFixtures.toolUse("toolu_a", "set_timer", listOf("{\"duration_seconds\": 6"))))
        server.enqueue(sse(SseFixtures.toolUse("toolu_b", "set_alarm", listOf("{\"hour\": 31, \"minute\": 0}"))))
        server.enqueue(sse(SseFixtures.text("Sorry, I couldn't set that.")))

        agent().turn("x", emptyList()) { events += it }

        assertTrue(ran.isEmpty())
        server.takeRequest()
        val tr1 = bodyOf(server.takeRequest()).getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertTrue(tr1.getBoolean("is_error"))
        assertEquals("INVALID_JSON", JSONObject(tr1.getString("content")).getString("error"))
        val tr2 = bodyOf(server.takeRequest()).getJSONArray("messages").getJSONObject(4).getJSONArray("content").getJSONObject(0)
        assertEquals("INVALID_INPUT", JSONObject(tr2.getString("content")).getString("error"))
        assertTrue(events.contains(TurnEvent.ToolDone("toolu_a", false)))
    }

    @Test fun `refusal stops before tools`() = runBlocking {
        server.enqueue(sse(SseFixtures.text("", stop = "refusal")))
        try { agent().turn("x", emptyList()) {}; fail() } catch (e: ClaudeException) { assertEquals("refusal", e.code) }
        assertTrue(ran.isEmpty())
    }

    @Test fun `max_tokens returns the text so far`() = runBlocking {
        server.enqueue(sse(SseFixtures.text("It will rain", stop = "max_tokens")))
        assertEquals("It will rain", agent().turn("x", emptyList()) {}.answer)
    }

    @Test fun `401 is an auth error and never retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        try { agent().turn("x", emptyList()) {}; fail() } catch (e: ClaudeException) {
            assertEquals("auth", e.code)
            assertFalse(e.message!!.contains("x-api-key")) // only the error type is kept
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `overloaded is retried once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        server.enqueue(sse(SseFixtures.text("Hello.")))
        assertEquals("Hello.", agent().turn("hi", emptyList()) {}.answer)
        assertEquals(2, server.requestCount)
    }

    @Test fun `two busy responses give up`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429).setBody("{}")) }
        try { agent().turn("hi", emptyList()) {}; fail() } catch (e: ClaudeException) { assertEquals("busy", e.code) }
    }

    @Test fun `no key fails without a request`() = runBlocking {
        try { agent(key = "").turn("hi", emptyList()) {}; fail() } catch (e: ClaudeException) { assertEquals("no-key", e.code) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `history is sent on a follow-up`() = runBlocking {
        server.enqueue(sse(SseFixtures.text("It's 51 degrees.")))
        val first = agent().turn("Weather?", emptyList()) {}
        server.enqueue(sse(SseFixtures.text("Yes, bring a coat.")))
        agent().turn("Do I need a coat?", first.history) {}
        server.takeRequest()
        val msgs = bodyOf(server.takeRequest()).getJSONArray("messages")
        assertEquals(3, msgs.length())
        assertEquals("It's 51 degrees.", msgs.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test fun `weather and an action pick two cards`() = runBlocking {
        server.enqueue(sse(SseFixtures.toolUse("toolu_w", "get_weather", emptyList())))
        server.enqueue(sse(SseFixtures.toolUse("toolu_t", "set_timer", listOf("{\"duration_seconds\": 60}"))))
        server.enqueue(sse(SseFixtures.text("Done.")))
        val r = agent().turn("x", emptyList()) {}
        assertTrue(r.cards[0] is ResultCard.Weather)
        assertTrue(r.cards[1] is ResultCard.Action)
    }

    @Test fun `each model gets its own thinking setting`() = runBlocking {
        suspend fun bodyFor(model: String): JSONObject {
            server.enqueue(sse(SseFixtures.text("Hi.")))
            val a = AssistantAgent(ClaudeClient({ "k" }, baseUrl = server.url("/").toString().trimEnd('/')), tools, model = { model })
            val r = a.turn("hi", emptyList()) {}
            assertEquals(ClaudeModels.of(model), r.model)
            return bodyOf(server.takeRequest())
        }
        val haiku = bodyFor(ClaudeModels.HAIKU)
        assertEquals("disabled", haiku.getJSONObject("thinking").getString("type"))
        val sonnet = bodyFor(ClaudeModels.SONNET)
        assertEquals("claude-sonnet-5-5", sonnet.getString("model"))
        assertEquals("between_tools", sonnet.getJSONObject("thinking").getString("type"))
        val opus = bodyFor(ClaudeModels.OPUS)
        assertEquals("claude-opus-5-5", opus.getString("model"))
        assertFalse(opus.has("thinking"))
        for (b in listOf(haiku, sonnet, opus)) {
            assertEquals("low", b.getJSONObject("output_config").getString("effort"))
            assertFalse(b.has("temperature") || b.has("top_p") || b.has("top_k"))
        }
        // An unknown id (an old saved value) falls back to Haiku.
        assertEquals("claude-haiku-5-5", bodyFor("claude-unknown").getString("model"))
    }
}
