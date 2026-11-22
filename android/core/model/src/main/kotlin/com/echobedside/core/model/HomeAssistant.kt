package com.echobedside.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject

/**
 * Home Assistant link (REST + WebSocket). The real client lives in `:core:data`
 * (HomeAssistantClient) and is bound by `:app` when `ha.url` + `ha.token` are set in
 * secrets.properties; otherwise [NotConfiguredHomeAssistant] is used.
 */
data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JSONObject,
    val lastChangedMillis: Long = 0,
) {
    val domain: String get() = entityId.substringBefore('.')
    val friendlyName: String get() = attributes.optString("friendly_name", entityId)
}

enum class HaConnection { NOT_CONFIGURED, CONNECTING, CONNECTED, AUTH_FAILED, DISCONNECTED }

interface HomeAssistant {
    val connection: StateFlow<HaConnection>
    /** Every entity, kept live with `subscribe_events state_changed` while connected. */
    val entities: StateFlow<Map<String, HaEntity>>

    /** Connects and keeps reconnecting with backoff. Called by `:app`. */
    fun start()
    fun stop()

    /**
     * `call_service` over the WebSocket. Returns the service response when [returnResponse]
     * (e.g. `weather.get_forecasts`, `calendar.get_events`), else an empty object.
     * Throws [HaException] on errors.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String? = null,
        data: JSONObject? = null,
        returnResponse: Boolean = false,
    ): JSONObject

    /**
     * Any one-shot WebSocket command; the client fills in `id`. Returns the whole result message:
     * read `optJSONObject("result")` or `optJSONArray("result")`. Throws [HaException].
     */
    suspend fun command(message: JSONObject): JSONObject

    /**
     * A subscription command (`subscribe_events`, `assist_pipeline/run`, `subscribe_trigger`...).
     * Emits each `event` payload; unsubscribes when the collector is cancelled.
     * `subscribe_*` commands are re-sent after a reconnect. Anything else (e.g. `assist_pipeline/run`)
     * is one-shot: the flow fails with [HaException] ("disconnected") if the connection drops.
     */
    fun subscribe(message: JSONObject): Flow<JSONObject>

    /** Assist pipeline audio: a binary frame prefixed with the STT [handlerId]. Empty [data] ends the stream. */
    fun sendBinary(handlerId: Int, data: ByteArray)

    /** Authenticated GET of an HA path, e.g. `/api/camera_proxy/camera.front_door` or an entity_picture. */
    suspend fun fetchBytes(path: String): ByteArray
}

class HaException(val code: String, message: String) : Exception(message)

object NotConfiguredHomeAssistant : HomeAssistant {
    override val connection = MutableStateFlow(HaConnection.NOT_CONFIGURED)
    override val entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override fun start() {}
    override fun stop() {}
    private fun nope(): Nothing = throw HaException("not_configured", "Home Assistant isn't configured (secrets.properties)")
    override suspend fun callService(domain: String, service: String, entityId: String?, data: JSONObject?, returnResponse: Boolean) = nope()
    override suspend fun command(message: JSONObject) = nope()
    override fun subscribe(message: JSONObject): Flow<JSONObject> = emptyFlow()
    override fun sendBinary(handlerId: Int, data: ByteArray) {}
    override suspend fun fetchBytes(path: String) = nope()
}
