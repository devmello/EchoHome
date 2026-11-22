package com.echobedside.feature.assistant.pipeline

import org.json.JSONArray
import org.json.JSONObject

/**
 * Events of Home Assistant's `assist_pipeline/run` WebSocket command
 * (contributors.home-assistant.io → Core → Assist pipelines). Each `event` payload is
 * `{"type": "...", "data": {...}, "timestamp": "..."}`.
 *
 * Order: run-start → wake_word-start → wake_word-end → stt-start → stt-vad-start → stt-vad-end →
 * stt-end → intent-start → intent-progress* → intent-end → tts-start → tts-end → run-end.
 * On a failure: error, then run-end. On the run timeout: run-end, then error "timeout".
 */
sealed interface PipelineEvent {
    /** [handlerId] is `runner_data.stt_binary_handler_id`: the first byte of every audio frame. */
    data class RunStart(val handlerId: Int?, val conversationId: String?, val pipeline: String?) : PipelineEvent
    data object WakeWordStart : PipelineEvent
    /** [detected] is false when the stream ended without a wake word (run-end follows). */
    data class WakeWordEnd(val detected: Boolean, val wakeWordId: String?, val phrase: String?) : PipelineEvent
    data object SttStart : PipelineEvent
    data object SttVadStart : PipelineEvent
    data object SttVadEnd : PipelineEvent
    data class SttEnd(val text: String) : PipelineEvent
    data class IntentStart(val text: String) : PipelineEvent
    /**
     * Streaming from LLM agents (`chat_log_delta`): a piece of the answer, tool calls the agent makes
     * and their results. [ttsStreaming] = `tts_start_streaming`.
     */
    data class IntentProgress(
        val textDelta: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val toolResultId: String? = null,
        val toolResultName: String? = null,
        val ttsStreaming: Boolean = false,
    ) : PipelineEvent
    data class IntentEnd(
        val speech: String,
        /** action_done | query_answer | error */
        val responseType: String,
        val conversationId: String?,
        val continueConversation: Boolean,
        /** `data.success`: the entities an action was done on. */
        val targets: List<Target>,
        val failed: List<Target>,
        val processedLocally: Boolean,
        val errorCode: String?,
    ) : PipelineEvent
    data class TtsStart(val text: String) : PipelineEvent
    data class TtsEnd(val url: String?, val mimeType: String?) : PipelineEvent
    data object RunEnd : PipelineEvent
    data class Error(val code: String, val message: String) : PipelineEvent
    data class Unknown(val type: String) : PipelineEvent

    // Demo-only events (the scripted demos from Assistant.dc.html), never sent by HA.
    /** Partial transcript, heard so far + the rest still to come (dimmed). */
    data class DemoPartial(val heard: String, val unheard: String) : PipelineEvent
    /** Steps known in advance, shown dimmed until their tool call starts. */
    data class DemoPlan(val steps: List<String>) : PipelineEvent
}

data class ToolCall(val id: String, val name: String)

/** An entity the agent acted on (`intent_output.response.data.success[]`). */
data class Target(val name: String, val id: String, val type: String = "entity")

object PipelineEvents {
    fun parse(event: JSONObject): PipelineEvent {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: JSONObject()
        return when (type) {
            "run-start" -> {
                val runner = data.optJSONObject("runner_data")
                val handler = runner?.let { if (it.has("stt_binary_handler_id") && !it.isNull("stt_binary_handler_id")) it.optInt("stt_binary_handler_id") else null }
                PipelineEvent.RunStart(handler, data.optStringOrNull("conversation_id"), data.optStringOrNull("pipeline"))
            }
            "wake_word-start" -> PipelineEvent.WakeWordStart
            "wake_word-end" -> {
                val out = data.optJSONObject("wake_word_output")
                val id = out?.optStringOrNull("wake_word_id")
                PipelineEvent.WakeWordEnd(out != null && out.length() > 0 && id != null, id, out?.optStringOrNull("wake_word_phrase"))
            }
            "stt-start" -> PipelineEvent.SttStart
            "stt-vad-start" -> PipelineEvent.SttVadStart
            "stt-vad-end" -> PipelineEvent.SttVadEnd
            "stt-end" -> PipelineEvent.SttEnd(data.optJSONObject("stt_output")?.optString("text").orEmpty().trim())
            "intent-start" -> PipelineEvent.IntentStart(data.optString("intent_input").trim())
            "intent-progress" -> parseProgress(data)
            "intent-end" -> parseIntentEnd(data)
            "tts-start" -> PipelineEvent.TtsStart(data.optString("tts_input"))
            "tts-end" -> {
                val out = data.optJSONObject("tts_output")
                PipelineEvent.TtsEnd(out?.optStringOrNull("url"), out?.optStringOrNull("mime_type"))
            }
            "run-end" -> PipelineEvent.RunEnd
            "error" -> PipelineEvent.Error(data.optString("code", "unknown"), data.optString("message"))
            else -> PipelineEvent.Unknown(type)
        }
    }

    private fun parseProgress(data: JSONObject): PipelineEvent.IntentProgress {
        if (data.optBoolean("tts_start_streaming", false) && !data.has("chat_log_delta")) return PipelineEvent.IntentProgress(ttsStreaming = true)
        val delta = data.optJSONObject("chat_log_delta") ?: return PipelineEvent.IntentProgress()
        val role = delta.optStringOrNull("role")
        if (role == "tool_result") {
            return PipelineEvent.IntentProgress(toolResultId = delta.optStringOrNull("tool_call_id"), toolResultName = delta.optStringOrNull("tool_name"))
        }
        val calls = delta.optJSONArray("tool_calls")?.objects()?.mapNotNull { c ->
            val name = c.optStringOrNull("tool_name") ?: return@mapNotNull null
            ToolCall(c.optStringOrNull("id") ?: name, name)
        }.orEmpty()
        return PipelineEvent.IntentProgress(textDelta = delta.optStringOrNull("content"), toolCalls = calls)
    }

    private fun parseIntentEnd(data: JSONObject): PipelineEvent.IntentEnd {
        val out = data.optJSONObject("intent_output") ?: JSONObject()
        val response = out.optJSONObject("response") ?: JSONObject()
        val speech = response.optJSONObject("speech")?.let { s ->
            s.optJSONObject("plain")?.optString("speech") ?: s.optJSONObject("ssml")?.optString("speech")
        }.orEmpty().trim()
        val rd = response.optJSONObject("data") ?: JSONObject()
        fun targets(key: String) = rd.optJSONArray(key)?.objects()?.map {
            Target(it.optString("name"), it.optString("id"), it.optString("type", "entity"))
        }.orEmpty()
        return PipelineEvent.IntentEnd(
            speech = speech,
            responseType = response.optString("response_type", "action_done"),
            conversationId = out.optStringOrNull("conversation_id"),
            continueConversation = out.optBoolean("continue_conversation", false),
            targets = targets("success"),
            failed = targets("failed"),
            processedLocally = data.optBoolean("processed_locally", false),
            errorCode = rd.optStringOrNull("code"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
