package com.echobedside.feature.assistant.llm

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Server-sent events, line by line: `event:` / `data:` lines, a blank line ends an event. */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()

    /** Feed one line (without its newline). Returns (event, data) when [line] completes an event. */
    fun feed(line: String): Pair<String, String>? {
        if (line.isEmpty()) {
            if (data.isEmpty() && event == null) return null
            val out = (event ?: "message") to data.toString()
            event = null; data.setLength(0)
            return out
        }
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}

/** Something went wrong talking to Claude. [code] is one of AssistReducer.friendly's codes. */
class ClaudeException(val code: String, message: String, val retryable: Boolean = false, val status: Int = 0) : Exception(message)

/** Callbacks while a response streams in (called on the network thread). */
interface StreamListener {
    fun onText(delta: String) {}
    fun onToolUse(id: String, name: String) {}
}

/** One finished assistant message. [content] is passed back to the API unchanged. */
class ClaudeMessage(val content: JSONArray, val stopReason: String?, val usage: JSONObject?) {
    val text: String get() = buildString {
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") append(b.optString("text"))
        }
    }

    /** tool_use blocks; [ToolUse.input] is null when the streamed JSON didn't parse. */
    val toolUses: List<ToolUse> get() = (0 until content.length()).map { content.getJSONObject(it) }
        .filter { it.optString("type") == "tool_use" }
        .map { ToolUse(it.getString("id"), it.getString("name"), if (it.has(INVALID)) null else it.optJSONObject("input")) }

    /** The content to send back: our private marker removed, invalid inputs replaced by {}. */
    fun contentForHistory(): JSONArray {
        val out = JSONArray()
        for (i in 0 until content.length()) {
            val b = JSONObject(content.getJSONObject(i).toString())
            if (b.has(INVALID)) { b.remove(INVALID); b.put("input", JSONObject()) }
            out.put(b)
        }
        return out
    }

    data class ToolUse(val id: String, val name: String, val input: JSONObject?)

    companion object { internal const val INVALID = "__invalid_json" }
}

/**
 * Builds a [ClaudeMessage] from the Messages API stream events
 * (message_start, content_block_start/delta/stop, message_delta, message_stop, ping, error).
 * Thinking blocks (with their signatures) are kept as-is so they can be sent back.
 */
class MessageAccumulator(private val listener: StreamListener = object : StreamListener {}) {
    private val blocks = ArrayList<JSONObject>()
    private val partialJson = HashMap<Int, StringBuilder>()
    private var stopReason: String? = null
    private var usage: JSONObject? = null
    var done = false; private set

    fun onEvent(type: String, data: String) {
        if (type == "ping") return
        val obj = try { JSONObject(data) } catch (e: JSONException) { return }
        when (obj.optString("type", type)) {
            "message_start" -> obj.optJSONObject("message")?.optJSONObject("usage")?.let { usage = it }
            "content_block_start" -> {
                val i = obj.getInt("index")
                val b = JSONObject(obj.getJSONObject("content_block").toString())
                while (blocks.size <= i) blocks.add(JSONObject())
                blocks[i] = b
                if (b.optString("type") == "tool_use") {
                    partialJson[i] = StringBuilder()
                    listener.onToolUse(b.optString("id"), b.optString("name"))
                }
            }
            "content_block_delta" -> {
                val i = obj.getInt("index")
                val b = blocks.getOrNull(i) ?: return
                val d = obj.getJSONObject("delta")
                when (d.optString("type")) {
                    "text_delta" -> {
                        val t = d.optString("text")
                        b.put("text", b.optString("text") + t)
                        listener.onText(t)
                    }
                    "thinking_delta" -> b.put("thinking", b.optString("thinking") + d.optString("thinking"))
                    "signature_delta" -> b.put("signature", b.optString("signature") + d.optString("signature"))
                    "input_json_delta" -> partialJson.getOrPut(i) { StringBuilder() }.append(d.optString("partial_json"))
                    "citations_delta" -> {}
                }
            }
            "content_block_stop" -> finishBlock(obj.getInt("index"))
            "message_delta" -> {
                obj.optJSONObject("delta")?.let { d -> if (!d.isNull("stop_reason")) stopReason = d.optString("stop_reason") }
                obj.optJSONObject("usage")?.let { u -> usage = merge(usage, u) }
            }
            "message_stop" -> done = true
            "error" -> {
                val e = obj.optJSONObject("error")
                val et = e?.optString("type") ?: "api_error"
                throw ClaudeException(
                    if (et == "overloaded_error" || et == "rate_limit_error") "busy" else "unknown",
                    "stream error: $et", retryable = et == "overloaded_error" || et == "api_error" || et == "rate_limit_error",
                )
            }
        }
    }

    private fun finishBlock(i: Int) {
        val b = blocks.getOrNull(i) ?: return
        if (b.optString("type") != "tool_use") return
        val raw = partialJson.remove(i)?.toString().orEmpty().trim()
        if (raw.isEmpty()) {
            if (!b.has("input")) b.put("input", JSONObject())
            return
        }
        try {
            b.put("input", JSONObject(raw))
        } catch (e: JSONException) {
            b.put("input", JSONObject()); b.put(ClaudeMessage.INVALID, true)
        }
    }

    fun result(): ClaudeMessage {
        // Blocks still open (stream cut at a stop event) are finished as they are.
        partialJson.keys.toList().forEach(::finishBlock)
        return ClaudeMessage(JSONArray(blocks.filter { it.length() > 0 }), stopReason, usage)
    }

    private fun merge(a: JSONObject?, b: JSONObject): JSONObject {
        val out = JSONObject(a?.toString() ?: "{}")
        for (k in b.keys()) out.put(k, b.get(k))
        return out
    }
}
