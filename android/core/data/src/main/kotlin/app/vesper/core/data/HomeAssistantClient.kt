package app.vesper.core.data

import app.vesper.core.model.HaConnection
import app.vesper.core.model.HaEntity
import app.vesper.core.model.HaException
import app.vesper.core.model.HomeAssistant
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * Home Assistant WebSocket + REST client (https://developers.home-assistant.io/docs/api/websocket).
 *
 * - Authenticates with a long-lived access token, reconnects with backoff (1 s → 30 s).
 * - Keeps [entities] live: subscribes to `state_changed`, then loads `get_states`.
 * - [subscribe] re-subscribes after reconnects for `subscribe_*` commands; any other command
 *   (e.g. `assist_pipeline/run`) is one-shot and its flow ends with an error on disconnect.
 * - Never logs the token or message bodies.
 */
class HomeAssistantClient(
    baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
) : HomeAssistant {

    private val base = baseUrl.trim().trimEnd('/')
    private val wsUrl = base.replaceFirst(Regex("^http"), "ws") + "/api/websocket"

    private val _connection = MutableStateFlow(HaConnection.DISCONNECTED)
    override val connection: StateFlow<HaConnection> = _connection.asStateFlow()
    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val eventHandlers = ConcurrentHashMap<Int, (JSONObject) -> Unit>()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var running = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    override fun start() {
        if (running) return
        running = true
        connect()
    }

    override fun stop() {
        running = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
        _connection.value = HaConnection.DISCONNECTED
    }

    private fun connect() {
        _connection.value = HaConnection.CONNECTING
        socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = try { JSONObject(text) } catch (_: Exception) { return }
            when (msg.optString("type")) {
                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                "auth_ok" -> {
                    attempt = 0
                    _connection.value = HaConnection.CONNECTED
                    scope.launch(Dispatchers.IO) { loadEntities() }
                }
                "auth_invalid" -> {
                    running = false // a bad token won't fix itself
                    _connection.value = HaConnection.AUTH_FAILED
                    webSocket.close(1000, null)
                }
                "result" -> pending.remove(msg.optInt("id"))?.complete(msg)
                "event" -> msg.optJSONObject("event")?.let { ev -> eventHandlers[msg.optInt("id")]?.invoke(ev) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    private fun dropped(ws: WebSocket) {
        if (socket !== ws && socket != null) return
        socket = null
        eventHandlers.clear()
        val err = HaException("disconnected", "Home Assistant connection lost")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        if (_connection.value != HaConnection.AUTH_FAILED) _connection.value = HaConnection.DISCONNECTED
        if (running) {
            val wait = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
            attempt++
            reconnectJob = scope.launch { delay(wait); if (running && socket == null) connect() }
        }
    }

    private suspend fun loadEntities() {
        try {
            val subId = ids.getAndIncrement()
            eventHandlers[subId] = { ev -> onStateChanged(ev) }
            send(subId, JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"))
            val states = send(ids.getAndIncrement(), JSONObject().put("type", "get_states")).optJSONArray("result")
            val map = HashMap<String, HaEntity>()
            if (states != null) for (i in 0 until states.length()) parseEntity(states.getJSONObject(i))?.let { map[it.entityId] = it }
            _entities.value = map
        } catch (_: Exception) {
            // Connection dropped mid-load; the reconnect will retry.
        }
    }

    private fun onStateChanged(event: JSONObject) {
        val data = event.optJSONObject("data") ?: return
        val id = data.optString("entity_id")
        val newState = data.optJSONObject("new_state")
        _entities.update { m ->
            if (newState == null) m - id else parseEntity(newState)?.let { m + (id to it) } ?: m
        }
    }

    /** Sends [message] with [id] and waits for its `result`. Throws [HaException] on failure. */
    private suspend fun send(id: Int, message: JSONObject): JSONObject {
        val ws = socket ?: throw HaException("not_connected", "Home Assistant isn't connected")
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        if (!ws.send(JSONObject(message.toString()).put("id", id).toString())) {
            pending.remove(id)
            throw HaException("not_connected", "Home Assistant isn't connected")
        }
        val res = d.await()
        if (!res.optBoolean("success", false)) {
            val e = res.optJSONObject("error")
            throw HaException(e?.optString("code") ?: "unknown", e?.optString("message") ?: "Command failed")
        }
        return res
    }

    private suspend fun awaitConnected() {
        if (_connection.value == HaConnection.CONNECTED) return
        if (!running) throw HaException("not_connected", "Home Assistant client isn't started")
        try { withTimeout(10_000) { _connection.first { it == HaConnection.CONNECTED } } }
        catch (_: Exception) { throw HaException("not_connected", "Home Assistant isn't connected") }
    }

    /** Returns the whole `result` message; read `optJSONObject("result")` or `optJSONArray("result")`. */
    override suspend fun command(message: JSONObject): JSONObject {
        awaitConnected()
        return send(ids.getAndIncrement(), message)
    }

    override suspend fun callService(
        domain: String,
        service: String,
        entityId: String?,
        data: JSONObject?,
        returnResponse: Boolean,
    ): JSONObject {
        val msg = JSONObject().put("type", "call_service").put("domain", domain).put("service", service)
        if (data != null) msg.put("service_data", data)
        if (entityId != null) msg.put("target", JSONObject().put("entity_id", entityId))
        if (returnResponse) msg.put("return_response", true)
        val result = command(msg).optJSONObject("result") ?: return JSONObject()
        return if (returnResponse) result.optJSONObject("response") ?: JSONObject() else result
    }

    override fun subscribe(message: JSONObject): Flow<JSONObject> = callbackFlow {
        val resubscribe = message.optString("type").startsWith("subscribe")
        var subId: Int? = null
        val job = launch {
            _connection.collect { state ->
                if (state == HaConnection.CONNECTED && subId == null) {
                    val id = ids.getAndIncrement()
                    subId = id
                    eventHandlers[id] = { trySend(it) }
                    try { send(id, message) } catch (e: HaException) { close(e) }
                } else if (state != HaConnection.CONNECTED && subId != null) {
                    subId = null
                    if (!resubscribe) close(HaException("disconnected", "Home Assistant connection lost"))
                }
            }
        }
        awaitClose {
            job.cancel()
            subId?.let { id ->
                eventHandlers.remove(id)
                if (resubscribe) socket?.send(
                    JSONObject().put("id", ids.getAndIncrement()).put("type", "unsubscribe_events").put("subscription", id).toString()
                )
            }
        }
    }

    override fun sendBinary(handlerId: Int, data: ByteArray) {
        val frame = ByteArray(data.size + 1)
        frame[0] = handlerId.toByte()
        System.arraycopy(data, 0, frame, 1, data.size)
        socket?.send(frame.toByteString())
    }

    override suspend fun fetchBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else base + (if (path.startsWith("/")) path else "/$path")
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${path.substringBefore('?')}")
            res.body!!.bytes()
        }
    }

    companion object {
        fun parseEntity(o: JSONObject): HaEntity? {
            val id = o.optString("entity_id").ifEmpty { return null }
            val changed = o.optString("last_changed").let { s ->
                try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
            }
            return HaEntity(id, o.optString("state"), o.optJSONObject("attributes") ?: JSONObject(), changed)
        }
    }
}
