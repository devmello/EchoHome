package com.echobedside.feature.settings.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.URLDecoder

/** One parsed HTTP/1.1 request. Header names are lower-case. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress? = null,
    val version: String = "HTTP/1.1",
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val cookies: Map<String, String> by lazy {
        val raw = header("cookie") ?: return@lazy emptyMap()
        raw.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()
    }

    /** HTTP/1.1 keeps the connection unless told otherwise; 1.0 closes unless asked to keep it. */
    val keepAlive: Boolean get() {
        val c = header("connection")?.lowercase()
        return if (version == "HTTP/1.0") c == "keep-alive" else c != "close"
    }

    fun bodyText(): String = String(body, Charsets.UTF_8)
}

class HttpException(val status: Int, message: String) : IOException(message)

/**
 * A small, strict HTTP/1.1 request parser: request line, headers, Content-Length body. No chunked
 * request bodies (browsers send Content-Length for fetch() with a string body). Limits keep a
 * misbehaving client from making the Echo allocate much.
 */
object HttpParser {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 64
    const val MAX_BODY = 64 * 1024

    /** Returns null on a clean end of stream before the first byte (an idle keep-alive closed). */
    fun parse(input: InputStream, remote: InetAddress? = null): HttpRequest? {
        var line = readLine(input) ?: return null
        // Tolerate stray CRLFs between pipelined requests (RFC 9112 §2.2).
        while (line.isEmpty()) line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) throw HttpException(400, "Bad request line")
        val (method, target, version) = parts
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw HttpException(505, "HTTP version not supported")
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw HttpException(400, "Bad method")
        if (!target.startsWith("/")) throw HttpException(400, "Bad target")

        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: throw HttpException(400, "Unexpected end of headers")
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpException(431, "Too many headers")
            val i = h.indexOf(':')
            if (i <= 0 || h[0] == ' ' || h[0] == '\t') throw HttpException(400, "Bad header")
            val name = h.substring(0, i).trim().lowercase()
            val value = h.substring(i + 1).trim()
            headers[name] = headers[name]?.let { if (name == "cookie") "$it; $value" else "$it, $value" } ?: value
        }
        if (headers["transfer-encoding"] != null) throw HttpException(411, "Chunked bodies are not supported")
        val length = headers["content-length"]?.let {
            it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw HttpException(400, "Bad Content-Length")
        } ?: 0
        if (length > MAX_BODY) throw HttpException(413, "Body too large")
        val body = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(body, off, length - off)
            if (n < 0) throw HttpException(400, "Body cut short")
            off += n
        }

        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val path = decodePath(rawPath)
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        return HttpRequest(method, path, query, headers, body, remote, version)
    }

    fun decodePath(raw: String): String {
        val p = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (e: IllegalArgumentException) {
            throw HttpException(400, "Bad path encoding")
        }
        if (p.split('/').any { it == ".." } || p.contains('\u0000') || p.contains('\\')) throw HttpException(400, "Bad path")
        return p
    }

    fun parseQuery(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate { kv ->
        val i = kv.indexOf('=')
        fun d(x: String) = try { URLDecoder.decode(x, "UTF-8") } catch (e: IllegalArgumentException) { x }
        if (i < 0) d(kv) to "" else d(kv.substring(0, i)) to d(kv.substring(i + 1))
    }

    /** Reads one line ending in LF (a CR before it is dropped). Null at end of stream with nothing read. */
    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw HttpException(400, "Line cut short")
            }
            if (b == '\n'.code) break
            if (buf.size() >= MAX_LINE) throw HttpException(431, "Line too long")
            buf.write(b)
        }
        val bytes = buf.toByteArray()
        val n = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        // Header bytes are ISO-8859-1 by spec; the path is percent-encoded UTF-8, decoded later.
        return String(bytes, 0, n, Charsets.ISO_8859_1)
    }
}

/**
 * A response. [stream] (Server-Sent Events) is written after the head instead of [body], on a
 * connection that is closed when it returns.
 */
class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val stream: ((OutputStream) -> Unit)? = null,
) {
    fun with(vararg h: Pair<String, String>) = HttpResponse(status, body, contentType, headers + h, stream)

    /** Writes the head and the body (not the body for HEAD). */
    fun writeTo(out: OutputStream, keepAlive: Boolean, head: Boolean = false) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (stream == null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive && stream == null) "keep-alive" else "close").append("\r\n")
        for ((k, v) in headers) {
            require('\r' !in v && '\n' !in v && '\r' !in k && '\n' !in k) { "Header injection" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head && stream == null) out.write(body)
        out.flush()
    }

    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun json(status: Int, json: String) = HttpResponse(status, json.toByteArray(), JSON, listOf("Cache-Control" to "no-store"))
        fun error(status: Int, message: String) = json(status, "{\"error\":${jsonString(message)}}")

        fun reason(status: Int) = when (status) {
            200 -> "OK"; 204 -> "No Content"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 409 -> "Conflict"; 410 -> "Gone"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"; 505 -> "HTTP Version Not Supported"
            else -> "Status"
        }

        /** A JSON string literal (no org.json needed for error bodies). */
        fun jsonString(s: String): String {
            val sb = StringBuilder(s.length + 2).append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            return sb.append('"').toString()
        }
    }
}
