package app.vesper.feature.update

import app.vesper.core.model.SettingsStore
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Debug builds only (this file is in `src/debug`; `src/release` has a stub that returns null):
 * a test feed instead of GitHub, e.g. a fake release served from the Mac with
 * `python3 -m http.server`. Set with the launch extra `--es updateFeed http://<mac>:8000/latest.json`
 * (`--es updateFeed -` clears it). Plain HTTP is allowed only to that feed's host and port, through
 * this tiny client: the app's network security config stays HTTPS-only.
 */
internal object DebugFeed {
    const val KEY = "update.debug.feedUrl"

    fun overrideUrl(settings: SettingsStore): String? = settings.getString(KEY, "").takeIf { it.startsWith("http://") || it.startsWith("https://") }

    fun set(settings: SettingsStore, url: String?) {
        if (url.isNullOrBlank() || url == "-") settings.remove(KEY) else settings.putString(KEY, url)
    }

    /** A fetcher for the override feed: plain HTTP to its own host:port, GitHub rules for anything else. */
    fun fetcher(feedUrl: String, fallback: Fetcher): Fetcher {
        val feed = URI(feedUrl)
        return Fetcher { url, headers ->
            val u = URI(url)
            if (u.scheme == "http" && u.host == feed.host && port(u) == port(feed)) PlainHttp.get(u, headers) else fallback.get(url, headers)
        }
    }

    private fun port(u: URI) = if (u.port > 0) u.port else 80

    /** HTTP/1.0 GET, no redirects, enough for `python3 -m http.server`. */
    private object PlainHttp {
        fun get(u: URI, headers: Map<String, String>): HttpResult {
            val s = Socket()
            s.connect(InetSocketAddress(u.host, port(u)), 10_000)
            s.soTimeout = 30_000
            val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            val req = StringBuilder("GET $path HTTP/1.0\r\nHost: ${u.host}:${port(u)}\r\nUser-Agent: ${GitHubFetcher.USER_AGENT}\r\n")
            for ((k, v) in headers) req.append(k).append(": ").append(v).append("\r\n")
            req.append("\r\n")
            s.getOutputStream().write(req.toString().toByteArray())
            val input = BufferedInputStream(s.getInputStream())
            val status = line(input) ?: throw IOException("empty response")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status: $status")
            val map = HashMap<String, String>()
            while (true) {
                val l = line(input) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) map[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            return HttpResult(code, { map[it.lowercase()] }, input, { try { s.close() } catch (_: IOException) {} })
        }

        private fun line(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }
    }
}
