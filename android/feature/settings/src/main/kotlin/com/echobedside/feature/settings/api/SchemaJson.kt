package com.echobedside.feature.settings.api

import com.echobedside.core.model.SecretStore
import com.echobedside.core.model.Secrets
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.core.model.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page's view of the schema. SECRET values are never serialised: only `secret` =
 * "set" (set on the Echo), "builtin" (compiled in from secrets.properties) or "unset".
 */
object SchemaJson {
    fun schema(
        sections: List<SettingsSection>,
        settings: SettingsStore,
        secrets: SecretStore,
        effective: Secrets,
        deviceName: String,
    ): JSONObject {
        val arr = JSONArray()
        for (s in sections.sortedBy { it.order }) {
            val specs = JSONArray()
            for (spec in s.settings) specs.put(spec(spec, settings, secrets, effective))
            arr.put(JSONObject().put("id", s.id).put("title", s.title).put("order", s.order)
                .putOpt("help", s.help).put("settings", specs))
        }
        return JSONObject().put("device", JSONObject().put("name", deviceName)).put("sections", arr)
    }

    fun spec(spec: SettingSpec, settings: SettingsStore, secrets: SecretStore, effective: Secrets): JSONObject {
        val o = JSONObject()
            .put("key", spec.key).put("title", spec.title).put("type", spec.type.name)
            .putOpt("help", spec.help).put("advanced", spec.advanced)
        spec.min?.let { o.put("min", it.toDouble()) }
        spec.max?.let { o.put("max", it.toDouble()) }
        spec.step?.let { o.put("step", it.toDouble()) }
        when (spec.type) {
            SettingType.SECRET -> {
                o.put("secret", secretState(spec.key, secrets, effective))
                val h = hints(spec)
                if (h.isNotEmpty()) o.put("hints", JSONObject(h as Map<*, *>))
            }
            SettingType.ACTION -> {}
            else -> {
                o.put("default", spec.default)
                if (spec.type == SettingType.CHOICE) {
                    o.put("options", JSONArray().apply { for ((v, l) in spec.options) put(JSONArray().put(v).put(l)) })
                }
                o.put("value", Reader.value(spec, settings) ?: JSONObject.NULL)
            }
        }
        return o
    }

    fun secretState(key: String, store: SecretStore, effective: Secrets): String = when {
        store.isSet(key) -> "set"
        builtIn(key, effective) -> "builtin"
        else -> "unset"
    }

    /** True when [effective] holds a value for [key] (from the store or compiled in). */
    fun builtIn(key: String, s: Secrets): Boolean = when (key) {
        Secrets.HA_URL -> s.haUrl.isNotBlank()
        Secrets.HA_TOKEN -> s.haToken.isNotBlank()
        Secrets.IMMICH_URL -> s.immichUrl.isNotBlank()
        Secrets.IMMICH_API_KEY -> s.immichApiKey.isNotBlank()
        Secrets.ANTHROPIC_API_KEY -> s.anthropicApiKey.isNotBlank()
        Secrets.STOCKS_API_KEY -> s.stocksApiKey.isNotBlank()
        Secrets.CALENDAR_ICS_URLS -> s.calendarIcsUrls.isNotEmpty()
        else -> false
    }

    /**
     * Rendering hints for a SECRET, carried in [SettingSpec.options] until the contract has fields
     * for them (contract request K-1): ("multiline" to placeholder), ("placeholder" to text).
     */
    fun hints(spec: SettingSpec): Map<String, String> =
        if (spec.type != SettingType.SECRET) emptyMap()
        else spec.options.filter { it.first == "multiline" || it.first == "placeholder" }.toMap()

    /** One SSE "change" event body for [key] (a LOCATION's ".place" maps to its spec). */
    fun change(specFor: (String) -> SettingSpec?, key: String, settings: SettingsStore, secrets: SecretStore, effective: Secrets): JSONObject? {
        val spec = specFor(key) ?: key.removeSuffix(".place").takeIf { it != key }?.let(specFor)?.takeIf { it.type == SettingType.LOCATION }
            ?: return null
        return when (spec.type) {
            SettingType.ACTION -> null
            SettingType.SECRET -> JSONObject().put("key", spec.key).put("secret", secretState(spec.key, secrets, effective))
            else -> JSONObject().put("key", spec.key).put("value", Reader.value(spec, settings) ?: JSONObject.NULL)
        }
    }
}
