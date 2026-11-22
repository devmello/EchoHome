package app.vesper.feature.home.ha

import app.vesper.core.model.HomeAssistant
import org.json.JSONArray
import org.json.JSONObject

/** One Home Assistant area (`config/area_registry/list`). */
data class Area(val id: String, val name: String)

/**
 * What the Home screen needs from HA's registries: the areas, which area each entity is in
 * (its own area, else its device's), and which entities to leave out (hidden, disabled, or
 * config/diagnostic entities like "Restart" buttons and signal-strength sensors).
 */
data class Registry(
    val areas: List<Area>,
    val entityArea: Map<String, String>,
    val excluded: Set<String>,
) {
    fun areaOf(entityId: String): String? = entityArea[entityId]

    companion object {
        val EMPTY = Registry(emptyList(), emptyMap(), emptySet())

        fun parse(areas: JSONArray?, devices: JSONArray?, entities: JSONArray?): Registry {
            val areaList = ArrayList<Area>()
            areas?.forEachObject { a ->
                val id = a.optString("area_id")
                if (id.isNotEmpty()) areaList += Area(id, a.optString("name", id))
            }
            val deviceArea = HashMap<String, String>()
            devices?.forEachObject { d ->
                val id = d.optString("id")
                val area = d.optStringOrNull("area_id")
                if (id.isNotEmpty() && area != null) deviceArea[id] = area
            }
            val entityArea = HashMap<String, String>()
            val excluded = HashSet<String>()
            entities?.forEachObject { e ->
                val id = e.optString("entity_id")
                if (id.isEmpty()) return@forEachObject
                if (e.optStringOrNull("hidden_by") != null || e.optStringOrNull("disabled_by") != null ||
                    e.optStringOrNull("entity_category") != null) excluded += id
                val area = e.optStringOrNull("area_id") ?: e.optStringOrNull("device_id")?.let { deviceArea[it] }
                if (area != null) entityArea[id] = area
            }
            return Registry(areaList, entityArea, excluded)
        }

        /** Loads the three registries over the WebSocket. Throws HaException. */
        suspend fun load(ha: HomeAssistant): Registry {
            fun cmd(type: String) = JSONObject().put("type", type)
            val areas = ha.command(cmd("config/area_registry/list")).optJSONArray("result")
            val devices = ha.command(cmd("config/device_registry/list")).optJSONArray("result")
            val entities = ha.command(cmd("config/entity_registry/list")).optJSONArray("result")
            return parse(areas, devices, entities)
        }
    }
}

internal inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}

/** optString returns "null" for JSON null; this returns Kotlin null for null, missing or empty. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).ifEmpty { null }

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }
