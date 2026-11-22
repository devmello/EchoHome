package app.vesper.feature.settings.api

import app.vesper.core.model.SecretStore
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/** One store write produced by [Validator]. */
sealed class Write {
    abstract val key: String
    data class Str(override val key: String, val value: String) : Write()
    data class Bool(override val key: String, val value: Boolean) : Write()
    data class IntV(override val key: String, val value: Int) : Write()
    data class FloatV(override val key: String, val value: Float) : Write()
    data class Remove(override val key: String) : Write()
    /** null clears the runtime secret (back to the compiled-in one, if any). */
    data class Secret(override val key: String, val value: String?) : Write()
}

class ValidationException(message: String) : Exception(message)

/**
 * Checks a JSON value from the page against its [SettingSpec] and turns it into store writes.
 * Store format per type (what features read back):
 * BOOLEAN putBoolean · INT putInt · FLOAT putFloat · TEXT/CHOICE/TIME/COLOR putString ·
 * TEXT_LIST comma-joined string · LOCATION "lat,lon" + "<key>.place" · SECRET [SecretStore].
 */
object Validator {
    const val MAX_TEXT = 500
    const val MAX_SECRET = 8 * 1024
    const val MAX_LIST = 50
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

    fun validate(spec: SettingSpec, v: Any?): List<Write> {
        val k = spec.key
        val isNull = v == null || v == JSONObject.NULL
        return when (spec.type) {
            SettingType.TEXT -> {
                val s = string(v, spec).trim()
                if (s.length > MAX_TEXT) fail("${spec.title}: at most $MAX_TEXT characters")
                if (s.any { it < ' ' }) fail("${spec.title}: one line only")
                listOf(Write.Str(k, s))
            }
            SettingType.BOOLEAN -> listOf(Write.Bool(k, when (v) {
                is Boolean -> v
                "true" -> true; "false" -> false
                else -> fail("${spec.title}: expected on or off")
            }))
            SettingType.INT -> {
                val n = number(v, spec)
                if (n != Math.rint(n)) fail("${spec.title}: whole numbers only")
                range(n, spec)
                listOf(Write.IntV(k, n.toInt()))
            }
            SettingType.FLOAT -> {
                val n = number(v, spec)
                range(n, spec)
                listOf(Write.FloatV(k, n.toFloat()))
            }
            SettingType.CHOICE -> {
                val s = string(v, spec)
                if (spec.options.none { it.first == s }) fail("${spec.title}: not one of the choices")
                listOf(Write.Str(k, s))
            }
            SettingType.TEXT_LIST -> {
                val items = when (v) {
                    is JSONArray -> (0 until v.length()).map { i -> (v.opt(i) as? String) ?: fail("${spec.title}: text items only") }
                    is String -> v.split(',', '\n')
                    else -> fail("${spec.title}: expected a list")
                }.map { it.trim() }.filter { it.isNotEmpty() }
                if (items.size > MAX_LIST) fail("${spec.title}: at most $MAX_LIST items")
                for (it in items) {
                    if (',' in it) fail("${spec.title}: an item can't contain a comma")
                    if (it.length > MAX_TEXT || it.any { c -> c < ' ' }) fail("${spec.title}: item too long")
                }
                listOf(Write.Str(k, items.joinToString(",")))
            }
            SettingType.TIME -> {
                val s = string(v, spec)
                if (!TIME.matches(s)) fail("${spec.title}: expected a time like 07:30")
                listOf(Write.Str(k, s))
            }
            SettingType.COLOR -> {
                // Empty / null: back to the feature's default (calendar.color.<n>: "Empty: the default palette").
                if (isNull || v == "") return listOf(Write.Remove(k))
                val s = string(v, spec)
                if (!COLOR.matches(s)) fail("${spec.title}: expected a colour like #FF9F0A")
                listOf(Write.Str(k, s.uppercase()))
            }
            SettingType.LOCATION -> {
                if (isNull || v == "") return listOf(Write.Remove(k), Write.Remove("$k.place"))
                val (lat, lon, place) = when (v) {
                    is JSONObject -> Triple(coord(v.opt("lat"), spec), coord(v.opt("lon"), spec), v.optString("place", "").trim())
                    is String -> v.split(',').let { p ->
                        if (p.size != 2) fail("${spec.title}: expected \"lat,lon\"")
                        Triple(coord(p[0].trim(), spec), coord(p[1].trim(), spec), "")
                    }
                    else -> fail("${spec.title}: expected a place")
                }
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) fail("${spec.title}: coordinates out of range")
                if (place.length > 200 || place.any { it < ' ' }) fail("${spec.title}: place name too long")
                listOf(Write.Str(k, "${fmt(lat)},${fmt(lon)}"), if (place.isEmpty()) Write.Remove("$k.place") else Write.Str("$k.place", place))
            }
            SettingType.SECRET -> {
                if (isNull) return listOf(Write.Secret(k, null))
                val s = (v as? String) ?: fail("${spec.title}: expected text")
                if (s.length > MAX_SECRET) fail("${spec.title}: too long")
                val value = if (SchemaJson.hints(spec).containsKey("multiline")) Secrets.splitUrls(s).joinToString(",")
                else s.trim().also { if (it.any { c -> c < ' ' }) fail("${spec.title}: one line only") }
                listOf(Write.Secret(k, value.ifEmpty { null }))
            }
            SettingType.ACTION -> fail("${spec.title} is a button, not a value")
        }
    }

    private fun fmt(d: Double) = "%.5f".format(java.util.Locale.ROOT, d).trimEnd('0').trimEnd('.')

    private fun string(v: Any?, spec: SettingSpec): String = (v as? String) ?: fail("${spec.title}: expected text")

    private fun number(v: Any?, spec: SettingSpec): Double {
        val n = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: fail("${spec.title}: expected a number")
        if (n.isNaN() || n.isInfinite()) fail("${spec.title}: expected a number")
        return n
    }

    private fun coord(v: Any?, spec: SettingSpec): Double = number(v, spec)

    private fun range(n: Double, spec: SettingSpec) {
        spec.min?.let { if (n < it - 1e-6) fail("${spec.title}: at least ${trim(it)}") }
        spec.max?.let { if (n > it + 1e-6) fail("${spec.title}: at most ${trim(it)}") }
    }

    private fun trim(f: Float) = if (f == Math.round(f).toFloat()) Math.round(f).toString() else f.toString()

    private fun fail(msg: String): Nothing = throw ValidationException(msg)
}

/** Applies [Write]s. Numbers and booleans keep the stored type if a feature already wrote another one. */
object Writer {
    fun apply(writes: List<Write>, settings: SettingsStore, secrets: SecretStore) {
        for (w in writes) when (w) {
            is Write.Str -> settings.putString(w.key, w.value)
            is Write.Bool -> if (storedAsString(settings, w.key) { settings.getBoolean(w.key, false) }) settings.putString(w.key, w.value.toString()) else settings.putBoolean(w.key, w.value)
            is Write.IntV -> if (storedAsString(settings, w.key) { settings.getInt(w.key, 0) }) settings.putString(w.key, w.value.toString()) else settings.putInt(w.key, w.value)
            is Write.FloatV -> if (storedAsString(settings, w.key) { settings.getFloat(w.key, 0f) }) settings.putString(w.key, w.value.toString()) else settings.putFloat(w.key, w.value)
            is Write.Remove -> settings.remove(w.key)
            is Write.Secret -> secrets.set(w.key, w.value)
        }
    }

    /** SharedPreferences throws ClassCastException when the key holds another type. */
    private inline fun storedAsString(s: SettingsStore, key: String, read: () -> Unit): Boolean =
        try { read(); false } catch (e: ClassCastException) { true }
}

/** Reads current values for the page, tolerating a feature that stored another type. */
object Reader {
    fun value(spec: SettingSpec, s: SettingsStore): Any? {
        val k = spec.key
        val d = spec.default
        return when (spec.type) {
            SettingType.BOOLEAN -> try { s.getBoolean(k, d.toBoolean()) } catch (e: ClassCastException) { s.getString(k, d).toBoolean() }
            SettingType.INT -> try { s.getInt(k, d.toIntOrNull() ?: 0) } catch (e: ClassCastException) {
                safeString(s, k, d).toDoubleOrNull()?.toInt() ?: d.toIntOrNull() ?: 0
            }
            SettingType.FLOAT -> try { s.getFloat(k, d.toFloatOrNull() ?: 0f).toString().toDouble() } catch (e: ClassCastException) {
                safeString(s, k, d).toDoubleOrNull() ?: d.toDoubleOrNull() ?: 0.0
            }
            SettingType.TEXT_LIST -> JSONArray(safeString(s, k, d).split(',').map { it.trim() }.filter { it.isNotEmpty() })
            SettingType.LOCATION -> {
                val raw = safeString(s, k, d)
                val p = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (p.size != 2) JSONObject.NULL
                else JSONObject().put("lat", p[0]).put("lon", p[1]).put("place", safeString(s, "$k.place", ""))
            }
            SettingType.SECRET, SettingType.ACTION -> null
            else -> safeString(s, k, d)
        }
    }

    private fun safeString(s: SettingsStore, k: String, d: String): String = try { s.getString(k, d) } catch (e: ClassCastException) {
        try { s.getInt(k, 0).toString() } catch (e2: ClassCastException) {
            try { s.getFloat(k, 0f).toString() } catch (e3: ClassCastException) { s.getBoolean(k, false).toString() }
        }
    }
}
