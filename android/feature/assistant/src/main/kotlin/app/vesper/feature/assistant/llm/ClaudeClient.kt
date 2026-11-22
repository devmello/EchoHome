package app.vesper.feature.assistant.llm

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** One streamed Messages API request. Behind an interface so the agent is tested without HTTP. */
fun interface ClaudeApi {
    suspend fun stream(body: JSONObject, listener: StreamListener): ClaudeMessage
}

/**
 * The Anthropic Messages API over raw HTTP (OkHttp; there's no official SDK for Android/Kotlin
 * that fits the app's size budget). Always `stream: true`, parsed with [SseParser] and
 * [MessageAccumulator].
 *
 * Privacy: never logs the key, the request body, or any text from the response.
 */
class ClaudeClient(
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.anthropic.com",
    http: OkHttpClient? = null,
) : ClaudeApi {
    private val http = http ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // between bytes; pings arrive well within this
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun stream(body: JSONObject, listener: StreamListener): ClaudeMessage = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) throw ClaudeException("no-key", "no Anthropic API key")
        body.put("stream", true)
        val req = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = http.newCall(req)
        val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw ClaudeException("unknown", "empty body")
                val sse = SseParser()
                val acc = MessageAccumulator(listener)
                while (!acc.done) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val ev = sse.feed(line) ?: continue
                    acc.onEvent(ev.first, ev.second)
                }
                acc.result()
            }
        } catch (e: ClaudeException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ClaudeException("offline", "network: ${e.javaClass.simpleName}", retryable = true)
        } finally {
            cancel?.dispose()
        }
    }

    private fun httpError(status: Int, body: String): ClaudeException {
        // Only the error *type* is kept: the message could quote the request.
        val type = try { JSONObject(body).optJSONObject("error")?.optString("type") } catch (e: Exception) { null } ?: "http"
        return when {
            status == 401 || status == 403 -> ClaudeException("auth", "HTTP $status $type", status = status)
            status == 429 || status == 529 || status >= 500 -> ClaudeException("busy", "HTTP $status $type", retryable = true, status = status)
            else -> ClaudeException("unknown", "HTTP $status $type", status = status)
        }
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        private val JSON = "application/json".toMediaType()
    }
}
