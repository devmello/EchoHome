package com.echobedside.feature.home.home

import com.echobedside.core.model.HaEntity
import com.echobedside.feature.home.ha.Registry
import com.echobedside.feature.home.ha.optDoubleOrNull
import com.echobedside.feature.home.ha.optStringOrNull
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * The Home screen has fixed slots (Home.dc.html): one thermostat, one lamp slider, two toggles,
 * four scenes and two "secure" tiles (lock, garage). A [Room] says which entity fills each slot.
 */
enum class Slot(val key: String, val capacity: Int) {
    CLIMATE("climate", 1), LAMP("lamp", 1), TOGGLES("toggles", 2), SCENES("scenes", 4), SECURE("secure", 2);
}

data class Room(
    val id: String,
    val name: String,
    val climate: String?,
    val lamp: String?,
    val toggles: List<String>,
    val scenes: List<String>,
    val secure: List<String>,
) {
    /** Short label for the switcher capsule: "Living Room" → "Living". */
    val label: String get() = name.removeSuffix(" Room").removeSuffix(" room").ifEmpty { name }
}

/**
 * Auto-picks entities per room from HA's areas, with per-room overrides from settings.
 *
 * Rules:
 *  - A room is an area with at least one light, switch, fan, input_boolean or climate entity.
 *  - Lamp slider: the area's first dimmable light, by name.
 *  - Toggles: the area's other lights / switches / fans / input_booleans, up to 2.
 *  - Thermostat, scenes and lock/garage fall back to house-wide entities: the area's own first,
 *    then ones with no area (or any area) so every room shows the house thermostat and front door.
 *  - Overrides: `home.room.<area>.<slot>` = comma-separated entity ids ("" = leave the slot empty).
 */
object RoomMapper {
    private val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "input_boolean")
    private val COVER_CLASSES = setOf("garage", "gate", "door")

    fun rooms(
        entities: Map<String, HaEntity>,
        registry: Registry,
        override: (areaId: String, slot: Slot) -> List<String>? = { _, _ -> null },
        order: List<String> = emptyList(),
    ): List<Room> {
        val pinned = registry.areas.flatMap { a -> Slot.values().flatMap { override(a.id, it).orEmpty() } }.toSet()
        val usable = entities.values.filter { it.entityId !in registry.excluded || it.entityId in pinned }
            .sortedBy { it.friendlyName.lowercase() }
        val byArea = usable.groupBy { registry.areaOf(it.entityId) }

        val houseClimate = usable.filter { it.domain == "climate" }
        val houseScenes = usable.filter { it.domain == "scene" }
        val houseSecure = usable.filter { isLock(it) } + usable.filter { isGarage(it) }

        val rooms = registry.areas.mapNotNull { area ->
            val mine = byArea[area.id].orEmpty()
            // A thermostat alone makes a room only when rooms have their own (with one house
            // thermostat every room borrows it, so a "Hallway" with just that isn't a room).
            val hasControls = mine.any { it.domain in TOGGLE_DOMAINS || (it.domain == "climate" && houseClimate.size > 1) }
            // An area without lights/switches still becomes a room if its lamp/toggles are pinned.
            val anyOverride = override(area.id, Slot.LAMP) != null || override(area.id, Slot.TOGGLES) != null
            if (!hasControls && !anyOverride) return@mapNotNull null

            val lamp = mine.firstOrNull { isDimmable(it) }?.entityId
            val toggles = mine.filter { it.domain in TOGGLE_DOMAINS && it.entityId != lamp }.map { it.entityId }
            val climate = (mine.filter { it.domain == "climate" } + houseClimate).firstOrNull()?.entityId
            val scenes = (mine.filter { it.domain == "scene" } + houseScenes.filter { registry.areaOf(it.entityId) == null } + houseScenes)
                .map { it.entityId }.distinct()
            val secure = (mine.filter { isLock(it) || isGarage(it) } + houseSecure).map { it.entityId }.distinct()

            fun pick(slot: Slot, auto: List<String>): List<String> =
                (override(area.id, slot)?.filter { it.isNotBlank() } ?: auto).take(slot.capacity)

            Room(
                id = area.id,
                name = area.name,
                climate = pick(Slot.CLIMATE, listOfNotNull(climate)).firstOrNull(),
                lamp = pick(Slot.LAMP, listOfNotNull(lamp)).firstOrNull(),
                toggles = pick(Slot.TOGGLES, toggles),
                scenes = pick(Slot.SCENES, scenes),
                secure = pick(Slot.SECURE, secure),
            )
        }
        if (order.isEmpty()) return rooms
        // `home.rooms` order first; rooms it doesn't list follow in name order.
        val rank = order.withIndex().associate { it.value to it.index }
        return rooms.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    fun isDimmable(e: HaEntity): Boolean {
        if (e.domain != "light") return false
        val modes = e.attributes.optJSONArray("supported_color_modes")
        if (modes != null && modes.length() > 0) {
            for (i in 0 until modes.length()) if (modes.optString(i) != "onoff") return true
            return false
        }
        // Older integrations: SUPPORT_BRIGHTNESS = 1, or a brightness attribute.
        return e.attributes.has("brightness") || (e.attributes.optInt("supported_features") and 1) != 0
    }

    fun isLock(e: HaEntity) = e.domain == "lock"
    fun isGarage(e: HaEntity) = e.domain == "cover" && e.attributes.optString("device_class") in COVER_CLASSES
}

// ---- Tile states: what each tile draws, derived from an entity ----

data class ThermostatTile(
    val entityId: String,
    val name: String,
    /** Target, in HA's unit. null when off / no target. */
    val target: Double?,
    val current: Double?,
    val min: Double,
    val max: Double,
    val step: Double,
    /** hvac mode: heat, cool, heat_cool, auto, off, dry, fan_only. */
    val mode: String,
    /** hvac_action: heating, cooling, idle, off... */
    val action: String?,
    val modes: List<String>,
    /** Which attribute [target] came from: temperature / target_temp_low / target_temp_high. */
    val targetKey: String,
    val available: Boolean,
) {
    /** 0..1 along the dial (design: 415 * (target − 50) / 40 of 553). */
    val fraction: Float get() = target?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val heating: Boolean get() = action == "heating" || (action == null && mode == "heat" && target != null && current != null && target > current)
    val cooling: Boolean get() = action == "cooling"

    val modeLabel: String get() = MODE_LABELS[mode] ?: mode.replaceFirstChar { it.uppercase() }

    /** "Heating to 68°" / "Cooling to 72°" / "Holding 68°" / "Off". */
    val line: String get() = when {
        !available -> "Unavailable"
        mode == "off" || target == null -> "Off"
        heating -> "Heating to ${fmtTemp(target)}°"
        cooling -> "Cooling to ${fmtTemp(target)}°"
        else -> "Holding ${fmtTemp(target)}°"
    }

    companion object {
        val MODE_LABELS = mapOf("heat" to "Heat", "cool" to "Cool", "heat_cool" to "Auto", "auto" to "Auto",
            "off" to "Off", "dry" to "Dry", "fan_only" to "Fan")

        fun from(e: HaEntity): ThermostatTile {
            val a = e.attributes
            val action = a.optStringOrNull("hvac_action")
            val key = when {
                a.optDoubleOrNull("temperature") != null -> "temperature"
                action == "cooling" && a.optDoubleOrNull("target_temp_high") != null -> "target_temp_high"
                a.optDoubleOrNull("target_temp_low") != null -> "target_temp_low"
                else -> "temperature"
            }
            val max = a.optDoubleOrNull("max_temp") ?: 90.0
            val min = a.optDoubleOrNull("min_temp") ?: if (max > 45) 50.0 else 7.0
            val step = a.optDoubleOrNull("target_temp_step") ?: if (max > 45) 1.0 else 0.5
            val modes = a.optJSONArray("hvac_modes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            return ThermostatTile(e.entityId, e.friendlyName, a.optDoubleOrNull(key), a.optDoubleOrNull("current_temperature"),
                min, max, step, e.state, action, modes, key, e.state != "unavailable" && e.state != "unknown")
        }
    }
}

data class LampTile(val entityId: String, val name: String, val on: Boolean, /** 0..100 */ val pct: Int, val available: Boolean) {
    companion object {
        fun from(e: HaEntity): LampTile {
            val on = e.state == "on"
            val b = e.attributes.optDoubleOrNull("brightness")
            val pct = if (!on) 0 else b?.let { (it / 255.0 * 100).roundToInt().coerceIn(1, 100) } ?: 100
            return LampTile(e.entityId, e.friendlyName, on, pct, e.state != "unavailable")
        }
    }
}

/** Icons in Home.dc.html, plus a few for domains the design doesn't show. */
enum class TileIcon { BULB, FAN, POWER, SUN, TV, MOON, AWAY, SPARKLE, LOCK, UNLOCK, GARAGE }

data class ToggleTile(
    val entityId: String,
    val name: String,
    val on: Boolean,
    val stateText: String,
    val icon: TileIcon,
    /** Icon tile colour when on. */
    val accent: Int,
    val available: Boolean,
) {
    companion object {
        fun from(e: HaEntity): ToggleTile {
            val on = e.state == "on"
            val icon = when (e.domain) { "light" -> TileIcon.BULB; "fan" -> TileIcon.FAN; else -> TileIcon.POWER }
            val accent = when (icon) { TileIcon.FAN -> CYAN; TileIcon.POWER -> GREEN; else -> ORANGE }
            val text = when {
                e.state == "unavailable" -> "Unavailable"
                !on -> "Off"
                e.domain == "fan" -> fanText(e)
                e.domain == "light" && e.attributes.optDoubleOrNull("brightness") != null ->
                    "${(e.attributes.optDouble("brightness") / 255.0 * 100).roundToInt()}%"
                else -> "On"
            }
            return ToggleTile(e.entityId, e.friendlyName, on, text, icon, accent, e.state != "unavailable")
        }

        /** "Auto · quiet" for a purifier on its auto preset at low speed. */
        private fun fanText(e: HaEntity): String {
            val preset = e.attributes.optStringOrNull("preset_mode")?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
            val pct = e.attributes.optDoubleOrNull("percentage")
            val speed = pct?.let { if (it <= 34) "quiet" else if (it <= 67) "medium" else "high" }
            return listOfNotNull(preset, speed).joinToString(" · ").ifEmpty { "On" }
        }
    }
}

data class SceneTile(val entityId: String, val name: String, val active: Boolean, val icon: TileIcon, val dot: Int) {
    companion object {
        /** [active] = the most recently activated of the room's scenes (a scene's state is that timestamp). */
        fun from(e: HaEntity, active: Boolean): SceneTile {
            val n = e.friendlyName.lowercase()
            val (icon, dot) = when {
                "morning" in n || "wake" in n || "bright" in n -> TileIcon.SUN to ORANGE
                "movie" in n || "tv" in n || "film" in n -> TileIcon.TV to BLUE
                "sleep" in n || "night" in n || "bed" in n -> TileIcon.MOON to INDIGO
                "away" in n || "leave" in n || "goodbye" in n -> TileIcon.AWAY to GREEN
                else -> TileIcon.SPARKLE to PURPLE
            }
            return SceneTile(e.entityId, e.friendlyName, active, icon, dot)
        }

        fun activatedAt(e: HaEntity): Long =
            try { OffsetDateTime.parse(e.state).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
    }
}

data class SecureTile(
    val entityId: String,
    val name: String,
    /** Highlighted (white) when the house is *less* secure: unlocked, open. */
    val on: Boolean,
    val stateText: String,
    val icon: TileIcon,
    val isLock: Boolean,
    val available: Boolean,
) {
    companion object {
        fun from(e: HaEntity): SecureTile {
            val s = e.state
            return if (e.domain == "lock") {
                val locked = s == "locked" || s == "locking"
                SecureTile(e.entityId, e.friendlyName, !locked, when (s) {
                    "locked" -> "Locked"; "unlocked" -> "Unlocked"; "locking" -> "Locking…"; "unlocking" -> "Unlocking…"
                    "jammed" -> "Jammed"; "open" -> "Open"; else -> "Unavailable"
                }, if (locked) TileIcon.LOCK else TileIcon.UNLOCK, true, s != "unavailable")
            } else {
                val open = s == "open" || s == "opening"
                SecureTile(e.entityId, e.friendlyName, open, when (s) {
                    "open" -> "Open"; "closed" -> "Closed"; "opening" -> "Opening…"; "closing" -> "Closing…"; else -> "Unavailable"
                }, TileIcon.GARAGE, false, s != "unavailable")
            }
        }
    }
}

/** Everything the Home screen draws for the selected room. */
data class HomeUi(
    val rooms: List<Room>,
    val selected: Room?,
    val title: String,
    val line: String,
    val thermostat: ThermostatTile?,
    val lamp: LampTile?,
    val toggles: List<ToggleTile>,
    val scenes: List<SceneTile>,
    val secure: List<SecureTile>,
    /** A transient problem to show instead of [line] ("Couldn't reach Home Assistant"). */
    val error: String? = null,
)

object HomePresenter {
    fun present(rooms: List<Room>, selectedId: String?, entities: Map<String, HaEntity>, error: String? = null): HomeUi {
        val room = rooms.firstOrNull { it.id == selectedId } ?: rooms.firstOrNull()
            ?: return HomeUi(rooms, null, "Home", "No rooms yet", null, null, emptyList(), emptyList(), emptyList(), error)
        val climate = room.climate?.let { entities[it] }?.let(ThermostatTile::from)
        val scenesE = room.scenes.mapNotNull { entities[it] }
        val newest = scenesE.filter { it.domain == "scene" }.maxByOrNull { SceneTile.activatedAt(it) }
            ?.takeIf { SceneTile.activatedAt(it) > 0 }?.entityId
        val secure = room.secure.mapNotNull { entities[it] }.map(SecureTile::from)
        return HomeUi(
            rooms = rooms,
            selected = room,
            title = room.name,
            line = roomLine(climate, secure),
            thermostat = climate,
            lamp = room.lamp?.let { entities[it] }?.let(LampTile::from),
            toggles = room.toggles.mapNotNull { entities[it] }.map(ToggleTile::from),
            scenes = scenesE.map { SceneTile.from(it, it.entityId == newest) },
            secure = secure,
            error = error,
        )
    }

    /** "66° inside · Doors locked" / "66° inside · Front door unlocked". */
    fun roomLine(climate: ThermostatTile?, secure: List<SecureTile>): String {
        val parts = ArrayList<String>()
        climate?.current?.let { parts += "${fmtTemp(it)}° inside" }
        val locks = secure.filter { it.isLock && it.available }
        if (locks.isNotEmpty()) {
            val open = locks.filter { it.on }
            parts += when {
                open.isEmpty() -> "Doors locked"
                open.size == 1 -> "${open[0].name} unlocked"
                else -> "${open.size} doors unlocked"
            }
        }
        return parts.joinToString(" · ").ifEmpty { " " }
    }
}

fun fmtTemp(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

internal const val ORANGE = 0xFFFF9F0A.toInt()
internal const val CYAN = 0xFF64D2FF.toInt()
internal const val BLUE = 0xFF0A84FF.toInt()
internal const val GREEN = 0xFF30D158.toInt()
internal const val INDIGO = 0xFF5E5CE6.toInt()
internal const val PURPLE = 0xFFBF5AF2.toInt()
