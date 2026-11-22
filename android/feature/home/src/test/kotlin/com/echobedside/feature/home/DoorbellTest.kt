package com.echobedside.feature.home

import com.echobedside.core.data.HomeAssistantClient
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.DefaultIslandManager
import com.echobedside.core.model.DefaultNotificationFeed
import com.echobedside.core.model.HaEntity
import com.echobedside.feature.home.doorbell.DoorbellWatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The doorbell flow end to end: the real HomeAssistantClient against a MockWebServer "Home Assistant". */
class DoorbellTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<JSONObject>()
    private val cameraHits = AtomicInteger()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var subId = -1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { ws?.close(1001, null); scope.cancel(); server.shutdown() }

    private fun state(id: String, s: String, name: String) = JSONObject().put("entity_id", id).put("state", s)
        .put("attributes", JSONObject().put("friendly_name", name))

    private fun result(id: Int, r: Any) = JSONObject().put("id", id).put("type", "result").put("success", true).put("result", r).toString()

    private fun startServer() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/websocket" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { ws = webSocket; webSocket.send("""{"type":"auth_required"}""") }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val m = JSONObject(text); received.add(m)
                        when (m.optString("type")) {
                            "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                            "subscribe_events" -> { subId = m.getInt("id"); webSocket.send(result(subId, JSONObject.NULL)) }
                            "get_states" -> webSocket.send(result(m.getInt("id"), JSONArray()
                                .put(state("binary_sensor.front_door_ding", "off", "Front door ding"))
                                .put(state("camera.front_door", "idle", "Front door"))
                                .put(state("lock.front_door", "locked", "Front door"))))
                            "call_service" -> webSocket.send(result(m.getInt("id"), JSONObject()))
                        }
                    }
                })
                request.path!!.startsWith("/api/camera_proxy/camera.front_door") -> {
                    assertEquals("Bearer t0ken", request.getHeader("Authorization"))
                    cameraHits.incrementAndGet()
                    MockResponse().setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @Test fun ringPostsBannerAndActivityRefreshesSnapshotAndUnlocks() = runBlocking {
        startServer()
        val ha = HomeAssistantClient(server.url("/").toString(), "t0ken", scope)
        val feed = DefaultNotificationFeed()
        val island = DefaultIslandManager()
        val decoded = AtomicInteger()
        val w = DoorbellWatcher(ha, MemSettings(), feed, island, scope, decode = { decoded.incrementAndGet(); null }, elapsed = { System.nanoTime() / 1_000_000 }, snapshotEveryMs = 50)
        ha.start()
        withTimeout(5000) { ha.entities.first { it.containsKey("binary_sensor.front_door_ding") } }
        w.start()
        assertEquals("binary_sensor.front_door_ding", w.doorbellId())
        kotlinx.coroutines.delay(300) // let the watcher see the "off" state first (a ring is off → on)

        // Press: state_changed off → on.
        withTimeout(5000) { while (subId < 0) kotlinx.coroutines.delay(10) }
        ws!!.send(JSONObject().put("id", subId).put("type", "event").put("event", JSONObject().put("event_type", "state_changed")
            .put("data", JSONObject().put("entity_id", "binary_sensor.front_door_ding").put("new_state", state("binary_sensor.front_door_ding", "on", "Front door ding")))).toString())

        val act = withTimeout(5000) { island.activities.first { l -> l.any { it.id == DoorbellWatcher.ACTIVITY_ID } } }.single()
        assertEquals(ActivityKind.DOORBELL, act.kind)
        assertTrue(act.alert)
        assertEquals(listOf("Unlock", "Dismiss"), act.actions.map { it.label }) // no Talk script set
        val banner = withTimeout(2000) { feed.banners.first { it.isNotEmpty() } }.single()
        assertEquals("Someone’s at the door", banner.title)
        assertTrue(banner.critical)
        assertEquals("Show camera", banner.primaryAction!!.label)

        withTimeout(5000) { while (cameraHits.get() < 2) kotlinx.coroutines.delay(20) } // refreshed, not just once
        withTimeout(2000) { while (decoded.get() < 2) kotlinx.coroutines.delay(20) }

        act.actions.first { it.label == "Unlock" }.onInvoke()
        val call = withTimeout(5000) { generateSequence { received.poll(5, TimeUnit.SECONDS) }.first { it.optString("type") == "call_service" } }
        assertEquals("lock", call.getString("domain")); assertEquals("unlock", call.getString("service"))
        assertEquals("lock.front_door", call.getJSONObject("target").getString("entity_id"))
        assertTrue(island.activities.value.none { it.id == DoorbellWatcher.ACTIVITY_ID }) // Unlock also dismisses
        assertTrue(feed.banners.value.isEmpty())
        w.stop(); ha.stop()
    }

    @Test fun ringDetection() {
        fun e(id: String, s: String) = HaEntity(id, s, JSONObject())
        assertTrue(DoorbellWatcher.isRing(e("binary_sensor.d", "off"), e("binary_sensor.d", "on")))
        assertFalse(DoorbellWatcher.isRing(null, e("binary_sensor.d", "on"))) // first load isn't a ring
        assertFalse(DoorbellWatcher.isRing(e("binary_sensor.d", "on"), e("binary_sensor.d", "on")))
        assertTrue(DoorbellWatcher.isRing(e("event.d", "2026-11-22T08:00:00Z"), e("event.d", "2026-11-22T08:01:00Z")))
        assertFalse(DoorbellWatcher.isRing(e("event.d", "2026-11-22T08:00:00Z"), e("event.d", "unavailable")))
    }
}
