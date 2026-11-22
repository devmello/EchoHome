package com.echobedside.feature.home.ha

import com.echobedside.core.model.HaConnection
import com.echobedside.core.model.HaEntity
import com.echobedside.core.model.HaException
import com.echobedside.core.model.HomeAssistant
import com.echobedside.feature.home.home.Slot
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * An in-memory Home Assistant with the designs' sample home (Home.dc.html: Bedroom / Living /
 * Kitchen, 68° thermostat, bedside lamp at 60 %, Sleep scene, front door locked, garage closed).
 *
 * Used only by `:feature:home`, only when HA isn't configured and demo data is on, so the real
 * code paths (registries → rooms → tiles → service calls → state updates) run on the device
 * without a server. It is never bound to `services.homeAssistant`.
 */
class DemoHomeAssistant(private val scope: CoroutineScope, private val latencyMs: Long = 150) : HomeAssistant {
    override val connection: StateFlow<HaConnection> = MutableStateFlow(HaConnection.CONNECTED)
    private val _entities = MutableStateFlow(initial())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    /** Tests / demos: make the next service calls fail, to see the rollback. */
    @Volatile var failCalls = false
    /** Bytes served for any camera_proxy path (the doorbell snapshot), set by the doorbell demo. */
    @Volatile var cameraImage: ByteArray? = null

    override fun start() {}
    override fun stop() {}

    override suspend fun command(message: JSONObject): JSONObject {
        val result: Any = when (message.optString("type")) {
            "get_config" -> JSONObject().put("location_name", "Santa Cruz")
                .put("unit_system", JSONObject().put("temperature", "°F").put("length", "mi"))
            "config/area_registry/list" -> JSONArray().apply {
                AREAS.forEach { (id, name) -> put(JSONObject().put("area_id", id).put("name", name)) }
            }
            "config/device_registry/list" -> JSONArray()
            "config/entity_registry/list" -> JSONArray().apply {
                ENTITY_AREAS.forEach { (id, area) -> put(JSONObject().put("entity_id", id).put("area_id", area ?: JSONObject.NULL)) }
                put(JSONObject().put("entity_id", "sensor.purifier_filter_life").put("area_id", "bedroom").put("entity_category", "diagnostic"))
            }
            else -> throw HaException("unknown_command", "Demo HA: ${message.optString("type")}")
        }
        return JSONObject().put("type", "result").put("success", true).put("result", result)
    }

    override suspend fun callService(domain: String, service: String, entityId: String?, data: JSONObject?, returnResponse: Boolean): JSONObject {
        delay(latencyMs)
        if (failCalls) throw HaException("home_assistant_error", "Demo failure")
        val id = entityId ?: return JSONObject()
        val e = _entities.value[id] ?: throw HaException("not_found", "Entity $id not found")
        val a = JSONObject(e.attributes.toString())
        var state = e.state
        when ("$domain.$service") {
            "light.turn_on" -> { state = "on"; data?.optInt("brightness_pct", -1)?.takeIf { it >= 0 }?.let { a.put("brightness", it * 255 / 100) }
                if (!a.has("brightness") || a.isNull("brightness")) a.put("brightness", 255) }
            "light.turn_off" -> { state = "off"; a.remove("brightness") }
            "light.toggle", "switch.toggle", "fan.toggle" -> state = if (state == "on") "off" else "on"
            "switch.turn_on", "fan.turn_on", "input_boolean.turn_on" -> state = "on"
            "switch.turn_off", "fan.turn_off", "input_boolean.turn_off" -> state = "off"
            "climate.set_temperature" -> data?.keys()?.forEach { k -> a.put(k, data.get(k)) }
            "climate.set_hvac_mode" -> state = data?.optString("hvac_mode") ?: state
            "scene.turn_on", "script.turn_on" -> state = OffsetDateTime.now().toString()
            "lock.lock" -> { state = "locking"; later(id, 900) { it.copy(state = "locked") } }
            "lock.unlock" -> { state = "unlocking"; later(id, 900) { it.copy(state = "unlocked") } }
            "cover.open_cover" -> { state = "opening"; later(id, 2500) { it.copy(state = "open") } }
            "cover.close_cover" -> { state = "closing"; later(id, 2500) { it.copy(state = "closed") } }
        }
        if (domain == "climate") {
            val target = a.optDouble("temperature"); val cur = a.optDouble("current_temperature")
            a.put("hvac_action", when { state == "off" -> "off"; state == "heat" && target > cur -> "heating"; state == "cool" && target < cur -> "cooling"; else -> "idle" })
        }
        set(id, e.copy(state = state, attributes = a, lastChangedMillis = System.currentTimeMillis()))
        return JSONObject()
    }

    /** Doorbell demo: the ding sensor turns on, then off again. */
    fun ringDoorbell() {
        set(DOORBELL, _entities.value.getValue(DOORBELL).copy(state = "on", lastChangedMillis = System.currentTimeMillis()))
        later(DOORBELL, 5000) { it.copy(state = "off") }
    }

    private fun later(id: String, ms: Long, f: (HaEntity) -> HaEntity) {
        scope.launch { delay(ms); _entities.value[id]?.let { set(id, f(it)) } }
    }

    private fun set(id: String, e: HaEntity) = _entities.update { it + (id to e) }

    override fun subscribe(message: JSONObject): Flow<JSONObject> = emptyFlow()
    override fun sendBinary(handlerId: Int, data: ByteArray) {}
    override suspend fun fetchBytes(path: String): ByteArray =
        if (path.startsWith("/api/camera_proxy/")) cameraImage ?: throw HaException("not_found", "No demo snapshot")
        else throw HaException("not_found", path)

    companion object {
        const val DOORBELL = "binary_sensor.front_door_ding"
        const val COMMUTE_SENSOR = "sensor.commute_to_work"

        private val AREAS = listOf("bedroom" to "Bedroom", "living_room" to "Living Room", "kitchen" to "Kitchen", "hallway" to "Hallway")
        private val ENTITY_AREAS = mapOf(
            "climate.thermostat" to "hallway",
            "light.bedside_lamp" to "bedroom", "light.ceiling" to "bedroom", "fan.air_purifier" to "bedroom",
            "light.floor_lamp" to "living_room", "light.tv_backlight" to "living_room", "switch.speakers" to "living_room",
            "light.pendants" to "kitchen", "light.under_cabinet" to "kitchen", "switch.coffee_maker" to "kitchen",
            "scene.good_morning" to null, "scene.movie" to null, "scene.sleep" to null, "scene.away" to null,
            "lock.front_door" to null, "cover.garage_door" to null,
            DOORBELL to null, "camera.front_door" to null, COMMUTE_SENSOR to null,
        )

        /** Design order for the demo scenes (HA has no entity order; real homes use `home.room.<area>.scenes`). */
        fun demoDefaults(areaId: String, slot: Slot): List<String>? =
            if (slot == Slot.SCENES) listOf("scene.good_morning", "scene.movie", "scene.sleep", "scene.away") else null

        private fun ent(id: String, state: String, vararg attrs: Pair<String, Any>): Pair<String, HaEntity> {
            val a = JSONObject()
            attrs.forEach { (k, v) -> a.put(k, v) }
            return id to HaEntity(id, state, a)
        }

        private fun modes(vararg m: String) = JSONArray().apply { m.forEach { put(it) } }

        private fun initial(): Map<String, HaEntity> {
            val now = OffsetDateTime.now()
            return mapOf(
                ent("climate.thermostat", "heat", "friendly_name" to "Thermostat", "temperature" to 68, "current_temperature" to 66,
                    "hvac_action" to "heating", "min_temp" to 50, "max_temp" to 90, "target_temp_step" to 1,
                    "hvac_modes" to modes("heat", "cool", "heat_cool", "off")),
                ent("light.bedside_lamp", "on", "friendly_name" to "Bedside lamp", "brightness" to 153, "supported_color_modes" to modes("brightness")),
                ent("light.ceiling", "off", "friendly_name" to "Ceiling", "supported_color_modes" to modes("onoff")),
                ent("fan.air_purifier", "on", "friendly_name" to "Air purifier", "preset_mode" to "auto", "percentage" to 25),
                ent("light.floor_lamp", "on", "friendly_name" to "Floor lamp", "brightness" to 89, "supported_color_modes" to modes("color_temp")),
                ent("light.tv_backlight", "on", "friendly_name" to "TV light", "supported_color_modes" to modes("onoff")),
                ent("switch.speakers", "off", "friendly_name" to "Speakers"),
                ent("light.pendants", "on", "friendly_name" to "Pendants", "brightness" to 204, "supported_color_modes" to modes("brightness")),
                ent("light.under_cabinet", "off", "friendly_name" to "Under-cabinet", "supported_color_modes" to modes("onoff")),
                ent("switch.coffee_maker", "on", "friendly_name" to "Coffee maker"),
                ent("scene.good_morning", now.minusHours(15).toString(), "friendly_name" to "Good Morning"),
                ent("scene.movie", now.minusHours(4).toString(), "friendly_name" to "Movie"),
                ent("scene.sleep", now.minusMinutes(20).toString(), "friendly_name" to "Sleep"),
                ent("scene.away", "unknown", "friendly_name" to "Away"),
                ent("lock.front_door", "locked", "friendly_name" to "Front door"),
                ent("cover.garage_door", "closed", "friendly_name" to "Garage", "device_class" to "garage"),
                ent(DOORBELL, "off", "friendly_name" to "Front door ding", "device_class" to "occupancy"),
                ent("camera.front_door", "idle", "friendly_name" to "Front door", "entity_picture" to "/api/camera_proxy/camera.front_door?token=demo"),
                ent(COMMUTE_SENSOR, "24", "friendly_name" to "Commute to work", "unit_of_measurement" to "min",
                    "duration" to 24.0, "distance" to 31.2, "route" to "Hwy 17", "origin" to "zone.home", "destination" to "zone.work"),
            )
        }
    }
}
