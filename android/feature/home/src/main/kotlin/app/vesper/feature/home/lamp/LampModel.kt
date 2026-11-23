package app.vesper.feature.home.lamp

import app.vesper.core.model.HaEntity
import app.vesper.feature.home.ha.Registry
import app.vesper.feature.home.ha.optDoubleOrNull
import app.vesper.feature.home.ha.optStringOrNull
import app.vesper.feature.home.home.RoomMapper
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Lamp screen (design/Lamp.dc.html): one light in detail. Pure Kotlin, no Android, so the HA
 * mapping is JVM-tested (LampModelTest).
 *
 * What a light can do comes from `supported_color_modes` (HA ≥ 2021.5); older integrations fall back
 * to `supported_features` (1 brightness, 2 colour temperature, 16 colour).
 */
enum class LightKind { ONOFF, DIM, TEMP, COLOR }

data class LightDetail(
    val entityId: String,
    val name: String,
    val on: Boolean,
    /** 0..100; 0 when off. */
    val pct: Int,
    val available: Boolean,
    val kind: LightKind,
    /** Set while the light shows a white (color_mode color_temp, or a white-only light that reports a temperature). */
    val kelvin: Int?,
    val minKelvin: Int,
    val maxKelvin: Int,
    /** Set while the light shows a colour (color_mode hs / xy / rgb*), 0xRRGGBB. */
    val rgb: Int?,
    val modes: Set<String>,
) {
    val dims: Boolean get() = kind != LightKind.ONOFF
    /** Colour lights take color_temp_kelvin too: HA converts it when the light has no color_temp mode. */
    val whites: Boolean get() = kind == LightKind.TEMP || kind == LightKind.COLOR
    val colors: Boolean get() = kind == LightKind.COLOR

    /** 0..1 along the warm ↔ cool track, null when the light isn't showing a white. */
    val kelvinFraction: Float? get() = kelvin?.let { ((it - minKelvin).toFloat() / (maxKelvin - minKelvin).coerceAtLeast(1)).coerceIn(0f, 1f) }

    companion object {
        val COLOR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")
        const val DEFAULT_MIN_K = 2000
        const val DEFAULT_MAX_K = 6500

        fun from(e: HaEntity): LightDetail {
            val a = e.attributes
            val on = e.state == "on"
            val modes = a.optJSONArray("supported_color_modes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
                ?: legacyModes(a.optInt("supported_features"), a)
            val kind = when {
                modes.any { it in COLOR_MODES } -> LightKind.COLOR
                "color_temp" in modes -> LightKind.TEMP
                modes.any { it != "onoff" } -> LightKind.DIM
                else -> LightKind.ONOFF
            }
            val b = a.optDoubleOrNull("brightness")
            val pct = when {
                !on -> 0
                kind == LightKind.ONOFF -> 100
                else -> b?.let { (it / 255.0 * 100).roundToInt().coerceIn(1, 100) } ?: 100
            }
            val (minK, maxK) = kelvinRange(a)
            val mode = a.optStringOrNull("color_mode")
            val kelvin = if (on && (mode == "color_temp" || (mode == null && kind == LightKind.TEMP))) readKelvin(a) else null
            val rgb = if (on && mode in COLOR_MODES) readRgb(a) else null
            return LightDetail(e.entityId, e.friendlyName, on, pct, e.state != "unavailable" && e.state != "unknown", kind,
                kelvin?.coerceIn(minK, maxK), minK, maxK, rgb, modes)
        }

        private fun legacyModes(features: Int, a: JSONObject): Set<String> {
            val out = HashSet<String>()
            if (features and 16 != 0) out += "hs"
            if (features and 2 != 0) out += "color_temp"
            if (out.isEmpty() && (features and 1 != 0 || a.has("brightness"))) out += "brightness"
            if (out.isEmpty()) out += "onoff"
            return out
        }

        fun kelvinRange(a: JSONObject): Pair<Int, Int> {
            val minK = a.optDoubleOrNull("min_color_temp_kelvin")?.toInt()
                ?: a.optDoubleOrNull("max_mireds")?.let { (1_000_000 / it).roundToInt() } ?: DEFAULT_MIN_K
            val maxK = a.optDoubleOrNull("max_color_temp_kelvin")?.toInt()
                ?: a.optDoubleOrNull("min_mireds")?.let { (1_000_000 / it).roundToInt() } ?: DEFAULT_MAX_K
            return if (maxK > minK) minK to maxK else DEFAULT_MIN_K to DEFAULT_MAX_K
        }

        private fun readKelvin(a: JSONObject): Int? =
            a.optDoubleOrNull("color_temp_kelvin")?.roundToInt() ?: a.optDoubleOrNull("color_temp")?.let { (1_000_000 / it).roundToInt() }

        private fun readRgb(a: JSONObject): Int? {
            a.optJSONArray("rgb_color")?.takeIf { it.length() >= 3 }?.let { return rgbOf(it.optInt(0), it.optInt(1), it.optInt(2)) }
            a.optJSONArray("hs_color")?.takeIf { it.length() >= 2 }?.let { return LightColors.hsToRgb(it.optDouble(0).toFloat(), it.optDouble(1).toFloat() / 100f) }
            return null
        }
    }
}

fun rgbOf(r: Int, g: Int, b: Int): Int = (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** One HA service call (the lamp screen's actions, as data so they're testable). */
data class ServiceCall(val domain: String, val service: String, val entityId: String, val data: JSONObject? = null) {
    override fun toString() = "$domain.$service $entityId ${data ?: ""}"
}

/** A one-tap look: brightness plus a white (ignored on lights that only dim). */
data class LampPreset(val id: String, val name: String, val pct: Int, val kelvin: Int, val dot: Int) {
    fun detail(light: LightDetail?): String = if (light?.whites == true) "$pct% · ${light.clampKelvin(kelvin)} K" else "$pct%"

    /** The light looks like this preset now (brightness within 2 %, white within 100 K). */
    fun matches(l: LightDetail): Boolean {
        if (!l.on || abs(l.pct - pct) > 2) return false
        if (!l.whites) return true
        val k = l.kelvin ?: return false
        return abs(k - l.clampKelvin(kelvin)) <= 100
    }

    companion object {
        val ALL = listOf(
            LampPreset("reading", "Reading", 100, 4000, 0xFFFF9F0A.toInt()),
            LampPreset("relax", "Relax", 50, 2700, 0xFFBF5AF2.toInt()),
            LampPreset("night", "Night light", 5, 2200, 0xFF5E5CE6.toInt()),
        )
    }
}

fun LightDetail.clampKelvin(k: Int) = k.coerceIn(minKelvin, maxKelvin)

/**
 * The lamp screen's actions → HA services (`light.turn_on` with brightness_pct, color_temp_kelvin,
 * rgb_color; `light.turn_off`), and the state HA is expected to report afterwards ([expected]), used
 * for the optimistic UI and by the demo home.
 */
object LightCalls {
    fun brightness(l: LightDetail, pct: Int): ServiceCall =
        if (pct <= 0) ServiceCall("light", "turn_off", l.entityId)
        else if (!l.dims) ServiceCall("light", "turn_on", l.entityId)
        else ServiceCall("light", "turn_on", l.entityId, JSONObject().put("brightness_pct", pct.coerceIn(1, 100)))

    fun power(l: LightDetail, on: Boolean) = ServiceCall("light", if (on) "turn_on" else "turn_off", l.entityId)

    /** Null when the light has no whites. Rounded to 50 K and clamped to the light's range. */
    fun kelvin(l: LightDetail, kelvin: Int): ServiceCall? {
        if (!l.whites) return null
        val k = l.clampKelvin(((kelvin / 50.0).roundToInt() * 50))
        return ServiceCall("light", "turn_on", l.entityId, JSONObject().put("color_temp_kelvin", k))
    }

    /** Null when the light has no colour. HA converts rgb_color to the light's own mode (hs / xy). */
    fun color(l: LightDetail, rgb: Int): ServiceCall? {
        if (!l.colors) return null
        val arr = JSONArray().put((rgb shr 16) and 255).put((rgb shr 8) and 255).put(rgb and 255)
        return ServiceCall("light", "turn_on", l.entityId, JSONObject().put("rgb_color", arr))
    }

    fun preset(l: LightDetail, p: LampPreset): ServiceCall {
        if (!l.dims) return ServiceCall("light", "turn_on", l.entityId)
        val data = JSONObject().put("brightness_pct", p.pct)
        if (l.whites) data.put("color_temp_kelvin", l.clampKelvin(p.kelvin))
        return ServiceCall("light", "turn_on", l.entityId, data)
    }

    /** The room list's switches: lights and anything else with turn_on / turn_off. */
    fun toggle(e: HaEntity): ServiceCall = ServiceCall(e.domain, if (e.state == "on") "turn_off" else "turn_on", e.entityId)

    /**
     * What [e] should look like once HA has run [call] (a light's turn_on / turn_off / toggle, or any
     * domain's turn_on / turn_off). Brightness from brightness_pct; color_temp_kelvin switches the
     * light to color_mode color_temp; rgb_color to its colour mode, as HA does.
     */
    fun expected(e: HaEntity, call: ServiceCall): HaEntity {
        val a = JSONObject(e.attributes.toString())
        val on = when (call.service) { "turn_on" -> true; "turn_off" -> false; "toggle" -> e.state != "on"; else -> return e }
        if (e.domain != "light") return e.copy(state = if (on) "on" else "off")
        if (!on) {
            for (k in LIVE_ATTRS) a.put(k, JSONObject.NULL)
            return e.copy(state = "off", attributes = a)
        }
        val d = call.data
        val light = LightDetail.from(e)
        d?.optDoubleOrNull("brightness_pct")?.let { a.put("brightness", (it.coerceIn(0.0, 100.0) * 255 / 100).roundToInt()) }
        if (light.dims && a.optDoubleOrNull("brightness") == null) a.put("brightness", 255)
        val k = d?.optDoubleOrNull("color_temp_kelvin")?.roundToInt()
        val rgb = d?.optJSONArray("rgb_color")
        when {
            k != null -> {
                val kk = light.clampKelvin(k)
                a.put("color_mode", if ("color_temp" in light.modes) "color_temp" else bestColorMode(light.modes))
                a.put("color_temp_kelvin", kk)
                a.put("color_temp", (1_000_000.0 / kk).roundToInt())
                val c = LightColors.kelvinDisplay(kk)
                a.put("rgb_color", JSONArray().put((c shr 16) and 255).put((c shr 8) and 255).put(c and 255))
                a.put("hs_color", JSONObject.NULL)
            }
            rgb != null && rgb.length() >= 3 -> {
                val c = rgbOf(rgb.optInt(0), rgb.optInt(1), rgb.optInt(2))
                a.put("color_mode", bestColorMode(light.modes))
                a.put("rgb_color", JSONArray().put(rgb.optInt(0)).put(rgb.optInt(1)).put(rgb.optInt(2)))
                val (h, s) = LightColors.rgbToHs(c)
                a.put("hs_color", JSONArray().put(h.toDouble()).put((s * 100).toDouble()))
                a.put("color_temp_kelvin", JSONObject.NULL)
                a.put("color_temp", JSONObject.NULL)
            }
            a.optStringOrNull("color_mode") == null -> a.put("color_mode", defaultMode(light))
        }
        return e.copy(state = "on", attributes = a)
    }

    private val LIVE_ATTRS = listOf("brightness", "color_mode", "color_temp_kelvin", "color_temp", "rgb_color", "hs_color", "xy_color")

    private fun bestColorMode(modes: Set<String>) = listOf("rgb", "hs", "xy", "rgbww", "rgbw").firstOrNull { it in modes } ?: "hs"

    private fun defaultMode(l: LightDetail) = when (l.kind) {
        LightKind.COLOR, LightKind.TEMP -> if ("color_temp" in l.modes) "color_temp" else bestColorMode(l.modes)
        LightKind.DIM -> if ("white" in l.modes) "white" else "brightness"
        LightKind.ONOFF -> "onoff"
    }
}

/** Colour maths for the screen: the designed warm ↔ cool whites, swatches, names. */
object LightColors {
    /** Display colours along the white track (Lamp.dc.html), not a physical blackbody: the cool end reads blue. */
    private val K_STOPS = intArrayOf(2000, 2700, 3500, 4500, 5500, 6500)
    private val K_COLORS = intArrayOf(0xFF8F2E, 0xFFAA50, 0xFFCB8C, 0xFFE9CC, 0xFFFBF5, 0xD9E7FF)

    fun kelvinDisplay(k: Int): Int {
        if (k <= K_STOPS[0]) return K_COLORS[0]
        for (i in 1 until K_STOPS.size) if (k <= K_STOPS[i]) {
            val t = (k - K_STOPS[i - 1]).toFloat() / (K_STOPS[i] - K_STOPS[i - 1])
            return lerpRgb(K_COLORS[i - 1], K_COLORS[i], t)
        }
        return K_COLORS.last()
    }

    data class Swatch(val name: String, val rgb: Int = 0, val kelvin: Int = 0)

    /** Colour lights: the Apple system colours. */
    val COLORS = listOf(
        Swatch("Red", 0xFF453A), Swatch("Orange", 0xFF9F0A), Swatch("Yellow", 0xFFD60A), Swatch("Green", 0x30D158),
        Swatch("Cyan", 0x64D2FF), Swatch("Blue", 0x0A84FF), Swatch("Purple", 0xBF5AF2),
    )

    /** White-only lights: five whites instead of colours. */
    val WHITES = listOf(
        Swatch("Candlelight", kelvin = 2200), Swatch("Warm white", kelvin = 2700), Swatch("Soft white", kelvin = 3500),
        Swatch("Neutral", kelvin = 4500), Swatch("Daylight", kelvin = 6500),
    )

    /** Brightness steps for lights that only dim. */
    val STEPS = intArrayOf(10, 25, 50, 75, 100)

    fun swatchColor(s: Swatch) = if (s.kelvin > 0) kelvinDisplay(s.kelvin) else s.rgb

    /** Index of the swatch the light shows now, or -1. Colours match by hue (HA reports them back renormalised). */
    fun selectedSwatch(l: LightDetail, list: List<Swatch>): Int {
        if (!l.on) return -1
        return list.indexOfFirst { s ->
            if (s.kelvin > 0) l.kelvin != null && abs(l.kelvin - l.clampKelvin(s.kelvin)) <= 100
            else l.rgb != null && sameColor(l.rgb, s.rgb)
        }
    }

    fun sameColor(a: Int, b: Int): Boolean {
        val (ha, sa) = rgbToHs(a); val (hb, sb) = rgbToHs(b)
        val dh = abs(ha - hb).let { if (it > 180) 360 - it else it }
        return dh <= 12f && abs(sa - sb) <= 0.2f
    }

    /** "Warm white" / "Purple": the colour tile's title. */
    fun name(l: LightDetail): String = when {
        !l.on -> "Off"
        l.rgb != null -> COLORS.firstOrNull { sameColor(l.rgb, it.rgb) }?.name ?: "Colour"
        l.kelvin != null -> whiteName(l.kelvin)
        l.dims -> "Brightness"
        else -> "On"
    }

    fun whiteName(k: Int) = when {
        k < 2450 -> "Candlelight"
        k < 3100 -> "Warm white"
        k < 4000 -> "Soft white"
        k < 5000 -> "Neutral white"
        k < 6000 -> "Daylight"
        else -> "Cool white"
    }

    /** What the light's own colour is, for the slider fill: its colour, its white, or the warm default. */
    fun fill(l: LightDetail): Int = l.rgb ?: l.kelvin?.let(::kelvinDisplay) ?: kelvinDisplay(2700)

    fun hsToRgb(hue: Float, sat: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = sat.coerceIn(0f, 1f)
        val c = s; val x = c * (1 - abs(h % 2 - 1)); val m = 1 - c
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return rgbOf(((r + m) * 255).roundToInt(), ((g + m) * 255).roundToInt(), ((b + m) * 255).roundToInt())
    }

    /** Hue 0..360, saturation 0..1 (HSV). */
    fun rgbToHs(c: Int): Pair<Float, Float> {
        val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return h to (if (max == 0f) 0f else d / max)
    }

    /** Relative luminance 0..1 (for dark or white ink on a fill). */
    fun luminance(c: Int): Float {
        fun ch(v: Int): Float { val s = v / 255f; return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat() }
        return 0.2126f * ch((c shr 16) and 255) + 0.7152f * ch((c shr 8) and 255) + 0.0722f * ch(c and 255)
    }

    fun lerpRgb(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).roundToInt().coerceIn(0, 255)) shl s
        return ch(16) or ch(8) or ch(0)
    }
}

/** A row in the "In Bedroom" list. */
data class RoomLight(val entityId: String, val name: String, val on: Boolean, val stateText: String, val available: Boolean)

/** Everything the Lamp screen draws. */
data class LampUi(
    val light: LightDetail?,
    /** The light's area, null when it has none. */
    val roomName: String?,
    /** "Bedroom · On · 60%". */
    val line: String,
    val others: List<RoomLight>,
    /** "2 of 3 on". */
    val othersLine: String,
    /** Index into [LampPreset.ALL], or -1. */
    val activePreset: Int,
    val error: String? = null,
)

object LampPresenter {
    const val MAX_OTHERS = 4

    fun present(entityId: String?, entities: Map<String, HaEntity>, registry: Registry, error: String? = null): LampUi {
        val e = entityId?.let { entities[it] }
            ?: return LampUi(null, null, if (entityId == null) "No lights yet" else "Light not found", emptyList(), " ", -1, error)
        val l = LightDetail.from(e)
        val area = registry.areaOf(e.entityId)
        val roomName = area?.let { id -> registry.areas.firstOrNull { it.id == id }?.name }
        val others = if (area == null) emptyList() else entities.values
            .filter { it.domain == "light" && it.entityId != e.entityId && registry.areaOf(it.entityId) == area && it.entityId !in registry.excluded }
            .sortedBy { it.friendlyName.lowercase() }
            .take(MAX_OTHERS)
            .map(::roomLight)
        val state = when {
            !l.available -> "Unavailable"
            !l.on -> "Off"
            l.dims -> "On · ${l.pct}%"
            else -> "On"
        }
        return LampUi(
            light = l,
            roomName = roomName,
            line = listOfNotNull(roomName, state).joinToString(" · "),
            others = others,
            othersLine = if (others.isEmpty()) "No other lights here" else "${others.count { it.on }} of ${others.size} on",
            activePreset = if (l.dims) LampPreset.ALL.indexOfFirst { it.matches(l) } else -1,
            error = error,
        )
    }

    fun roomLight(e: HaEntity): RoomLight {
        val l = LightDetail.from(e)
        val text = when {
            !l.available -> "Unavailable"
            !l.on -> "Off"
            l.rgb != null -> "${LightColors.name(l)} · ${l.pct}%"
            l.dims -> "${l.pct}%"
            else -> "On"
        }
        return RoomLight(e.entityId, e.friendlyName, l.on, text, l.available)
    }

    /**
     * Which light to show with no `entity` argument: the first dimmable light of [roomId] (the Home
     * screen's selected room), else the first dimmable light anywhere, else any light.
     */
    fun defaultLight(entities: Map<String, HaEntity>, registry: Registry, roomId: String?): String? {
        val lights = entities.values.filter { it.domain == "light" && it.entityId !in registry.excluded }.sortedBy { it.friendlyName.lowercase() }
        return (lights.firstOrNull { registry.areaOf(it.entityId) == roomId && RoomMapper.isDimmable(it) }
            ?: lights.firstOrNull { RoomMapper.isDimmable(it) } ?: lights.firstOrNull())?.entityId
    }
}
