package com.echobedside.feature.assistant.llm

import com.echobedside.feature.assistant.pipeline.ResultCard
import org.json.JSONArray
import org.json.JSONObject

/** A client tool Claude can call. [properties] is a JSON-schema `properties` object. */
class ToolSpec(
    val name: String,
    val description: String,
    val properties: JSONObject = JSONObject(),
    val required: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false))
        // Streamed inputs arrive as they're generated; we validate them ourselves (validate()).
        .put("eager_input_streaming", true)

    /** Checks [input] against this schema (required keys, types, enums, ranges). Returns the problem or null. */
    fun validate(input: JSONObject): String? {
        for (r in required) if (!input.has(r) || input.isNull(r)) return "missing '$r'"
        for (k in input.keys()) {
            val p = properties.optJSONObject(k) ?: return "unknown property '$k'"
            val v = input.get(k)
            if (v == JSONObject.NULL) continue
            val ok = when (p.optString("type")) {
                "string" -> v is String
                "integer" -> v is Int || v is Long || (v is Number && v.toDouble() % 1.0 == 0.0)
                "number" -> v is Number
                "boolean" -> v is Boolean
                "array" -> v is JSONArray
                else -> true
            }
            if (!ok) return "'$k' should be ${p.optString("type")}"
            p.optJSONArray("enum")?.let { e ->
                if (p.optString("type") == "array") {
                    val a = v as JSONArray
                    for (i in 0 until a.length()) if (!contains(e, a.get(i))) return "'$k' has an unknown value"
                } else if (!contains(e, v)) return "'$k' must be one of $e"
            }
            p.optJSONObject("items")?.optJSONArray("enum")?.let { e ->
                val a = v as JSONArray
                for (i in 0 until a.length()) if (!contains(e, a.get(i))) return "'$k' has an unknown value"
            }
            if (v is Number) {
                if (p.has("minimum") && v.toDouble() < p.getDouble("minimum")) return "'$k' is below ${p.get("minimum")}"
                if (p.has("maximum") && v.toDouble() > p.getDouble("maximum")) return "'$k' is above ${p.get("maximum")}"
            }
        }
        return null
    }

    private fun contains(a: JSONArray, v: Any): Boolean = (0 until a.length()).any { a.get(it) == v }
}

/** What a tool call returned: [content] goes back to Claude, [card] (if any) shows under the answer. */
class ToolOutcome(val content: JSONObject, val isError: Boolean = false, val card: ResultCard? = null) {
    companion object {
        fun error(code: String, message: String) = ToolOutcome(JSONObject().put("error", code).put("message", message), isError = true)
    }
}

/** The tools for one turn and how to run them. */
interface ToolHost {
    fun specs(): List<ToolSpec>
    suspend fun run(name: String, input: JSONObject): ToolOutcome
}

/** JSON-schema helpers for the specs. */
internal object Schema {
    fun str(desc: String, enum: List<String>? = null) = JSONObject().put("type", "string").put("description", desc)
        .also { o -> enum?.let { o.put("enum", JSONArray(it)) } }
    fun int(desc: String, min: Int? = null, max: Int? = null) = JSONObject().put("type", "integer").put("description", desc)
        .also { o -> min?.let { o.put("minimum", it) }; max?.let { o.put("maximum", it) } }
    fun num(desc: String, min: Double? = null, max: Double? = null) = JSONObject().put("type", "number").put("description", desc)
        .also { o -> min?.let { o.put("minimum", it) }; max?.let { o.put("maximum", it) } }
    fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)
    fun strArray(desc: String, enum: List<String>) = JSONObject().put("type", "array").put("description", desc)
        .put("items", JSONObject().put("type", "string").put("enum", JSONArray(enum)))
    fun props(vararg p: Pair<String, JSONObject>) = JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v) } }
}
