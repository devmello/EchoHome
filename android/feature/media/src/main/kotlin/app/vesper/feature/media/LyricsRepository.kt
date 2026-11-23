package app.vesper.feature.media

import android.content.Context
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Lyrics from LRCLIB (keyless), cached per track: 24 entries in memory, a small disk cache
 * (`cacheDir/lyrics`, at most [MAX_FILES] files / [MAX_BYTES]) and negative entries ("no lyrics")
 * for [NEGATIVE_TTL_MS]. Blocking: call from a background dispatcher.
 */
internal class LyricsRepository(private val dir: File, private val http: OkHttpClient = client()) {
    companion object {
        const val MAX_FILES = 200
        const val MAX_BYTES = 2L * 1024 * 1024
        const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000
        const val BASE = "https://lrclib.net/api"
        const val USER_AGENT = "Vesper/0.1 (smart display)"

        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .build()

        @Volatile private var instance: LyricsRepository? = null
        fun get(context: Context): LyricsRepository = instance ?: synchronized(this) {
            instance ?: LyricsRepository(File(context.cacheDir, "lyrics")).also { instance = it }
        }
    }

    /** Overridable for tests (a MockWebServer URL). */
    var base = BASE
    private val memory = LruCache<String, Lyrics>(24)

    /** The cached value for the track (memory, then disk), or null when it has to be fetched. */
    fun cached(key: String): Lyrics? {
        memory.get(key)?.let { return it }
        val f = file(key)
        if (!f.isFile) return null
        val text = try { f.readText() } catch (_: Exception) { return null }
        val v = decode(text, f.lastModified()) ?: run { f.delete(); return null }
        memory.put(key, v)
        return v
    }

    /**
     * Fetches the lyrics, or null on a network error (not cached, so it's retried next time).
     * A clean "not found" is cached as [Lyrics.None].
     */
    fun fetch(key: String, title: String, artist: String, album: String, durationMs: Long): Lyrics? {
        val sec = Math.round(durationMs / 1000.0)
        val get = StringBuilder("$base/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
        if (album.isNotBlank()) get.append("&album_name=${enc(album)}")
        if (sec > 0) get.append("&duration=$sec")
        val first = request(get.toString()) ?: return null
        var result: Lyrics? = if (first.code == 200) parseOrNull { LrcLib.fromRecord(JSONObject(first.body)) } else null
        if (result == null) {
            val second = request("$base/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
            if (second.code == 200) result = parseOrNull { LrcLib.fromSearch(JSONArray(second.body), sec) }
        }
        val value = result ?: Lyrics.None
        put(key, value)
        return value
    }

    fun put(key: String, v: Lyrics) {
        memory.put(key, v)
        try {
            dir.mkdirs()
            file(key).writeText(encode(v))
            prune()
        } catch (_: Exception) {}
    }

    private inline fun parseOrNull(f: () -> Lyrics?): Lyrics? = try { f() } catch (_: Exception) { null }

    private class Reply(val code: Int, val body: String)

    private fun request(url: String): Reply? = try {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 404 -> Reply(404, "")
                r.isSuccessful -> Reply(r.code, r.body?.string().orEmpty())
                else -> null
            }
        }
    } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun file(key: String) = File(dir, Integer.toHexString(key.hashCode()) + "_" + key.length)

    private fun encode(v: Lyrics): String = when (v) {
        is Lyrics.Synced -> "S\n" + v.lines.joinToString("\n") { "${it.timeMs}\t${it.text}" }
        is Lyrics.Plain -> "P\n" + v.lines.joinToString("\n")
        Lyrics.None -> "N\n"
    }

    private fun decode(s: String, modified: Long): Lyrics? {
        val body = s.substringAfter('\n', "")
        return when (s.firstOrNull()) {
            'S' -> Lyrics.Synced(body.lines().mapNotNull { l ->
                val tab = l.indexOf('\t'); val t = if (tab > 0) l.substring(0, tab).toLongOrNull() else null
                t?.let { LyricLine(it, l.substring(tab + 1)) }
            }).takeIf { it.lines.isNotEmpty() }
            'P' -> Lyrics.Plain(body.lines())
            'N' -> if (System.currentTimeMillis() - modified < NEGATIVE_TTL_MS) Lyrics.None else null
            else -> null
        }
    }

    private fun prune() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        for (f in files) {
            if (count <= MAX_FILES && total <= MAX_BYTES) break
            total -= f.length(); count--; f.delete()
        }
    }
}
