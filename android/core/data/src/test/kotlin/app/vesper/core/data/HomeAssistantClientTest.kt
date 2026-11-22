package app.vesper.core.data

import app.vesper.core.model.HaConnection
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A fake Home Assistant speaking the WebSocket protocol, driven by MockWebServer. */
class HomeAssistantClientTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JSONObject>()
    @Volatile private var serverSocket: WebSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                webSocket.send("""{"type":"auth_required","ha_version":"2026.11.0"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                received.add(m)
                when (m.optString("type")) {
                    "auth" -> webSocket.send(if (m.optString("access_token") == "good") """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                    "subscribe_events" -> webSocket.send(result(m.getInt("id"), JSONObject.NULL))
                    "get_states" -> webSocket.send(result(m.getInt("id"), JSONArray().put(
                        JSONObject().put("entity_id", "light.lamp").put("state", "off").put("attributes", JSONObject().put("friendly_name", "Lamp")))))
                    "call_service" -> webSocket.send(result(m.getInt("id"), JSONObject().put("response", JSONObject().put("ok", true))))
                }
            }
        }))
        server.start()
    }

    @After fun tearDown() { serverSocket?.close(1001, null); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); server.shutdown() }

    private fun result(id: Int, r: Any) = JSONObject().put("id", id).put("type", "result").put("success", true).put("result", r).toString()

    @Test fun authenticatesLoadsStatesFollowsChangesAndCallsServices() = runBlocking {
        val client = HomeAssistantClient(server.url("/").toString(), "good", scope)
        client.start()
        withTimeout(5000) { client.entities.first { it.containsKey("light.lamp") } }
        assertEquals("Lamp", client.entities.value["light.lamp"]!!.friendlyName)

        val sub = generateSequence { received.poll(2, TimeUnit.SECONDS) }.first { it.optString("type") == "subscribe_events" }
        serverSocket!!.send(JSONObject().put("id", sub.getInt("id")).put("type", "event").put("event", JSONObject()
            .put("event_type", "state_changed").put("data", JSONObject().put("entity_id", "light.lamp")
                .put("new_state", JSONObject().put("entity_id", "light.lamp").put("state", "on").put("attributes", JSONObject())))).toString())
        withTimeout(5000) { client.entities.first { it["light.lamp"]?.state == "on" } }

        val resp = client.callService("light", "turn_on", "light.lamp", JSONObject().put("brightness", 128), returnResponse = true)
        assertEquals(true, resp.optBoolean("ok"))
        val call = generateSequence { received.poll(2, TimeUnit.SECONDS) }.first { it.optString("type") == "call_service" }
        assertEquals("light.lamp", call.getJSONObject("target").getString("entity_id"))
        assertEquals(128, call.getJSONObject("service_data").getInt("brightness"))
        client.stop()
    }

    @Test fun badTokenStopsReconnecting() = runBlocking {
        val client = HomeAssistantClient(server.url("/").toString(), "bad", scope)
        client.start()
        withTimeout(5000) { client.connection.first { it == HaConnection.AUTH_FAILED } }
        assertEquals(HaConnection.AUTH_FAILED, client.connection.value)
        client.stop()
    }
}
