package app.vesper.feature.phone.ams

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Album art for tracks that come without it (AMS never has art; this ROM's AVRCP has cover art
 * off). It asks the keyless iTunes Search API (`term = artist + title`, `entity = song`, limit 1)
 * and fetches `artworkUrl100` resized to 600 px. That sends the song's title and artist to Apple,
 * so it's a setting ([enabled], on by default). Titles never go to the log.
 *
 * It asks for five results and uses the first by the same artist. On the Echo, `limit=1` for
 * "Bon Iver Holocene" returned a string-quartet cover, and podcast or video titles match random
 * songs. No match = no art (D's fallback cover).
 *
 * Decoded straight to [size] px (Now Playing's art is 324 px), cached by track (a few entries,
 * ~420 KB each), misses cached too, one request at a time.
 */
class ArtworkLookup(
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
    private val size: Int = 324,
) {
    private val cache = object : LinkedHashMap<String, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap?>) = size > MAX_ENTRIES
    }
    private val waiting = HashMap<String, MutableList<() -> Unit>>()
    private var latest: String? = null

    /**
     * The art for this track if it's cached, else null. A miss starts a lookup when enabled, and
     * [onReady] runs on the caller's (main) thread once it lands.
     */
    fun get(artist: String, title: String, onReady: () -> Unit): Bitmap? {
        if (!enabled() || title.isBlank()) return null
        val key = ItunesArt.key(artist, title)
        if (cache.containsKey(key)) return cache[key]
        latest = key
        waiting[key]?.let { it += onReady; return null }
        waiting[key] = mutableListOf(onReady)
        scope.launch {
            // A track's attributes arrive one by one: wait until they've settled, and skip a
            // half-updated (new artist, old title) pair that's already been replaced.
            delay(SETTLE_MS)
            if (latest != key) { waiting.remove(key); return@launch }
            val bmp = withContext(Dispatchers.IO) { runCatching { fetch(artist, title) }.onFailure { Log.i(TAG, "artwork lookup failed: ${it.javaClass.simpleName}") }.getOrNull() }
            cache[key] = bmp
            Log.i(TAG, "artwork lookup: ${if (bmp != null) "found" else "none"}")
            waiting.remove(key)?.forEach { it() }
        }
        return null
    }

    private fun fetch(artist: String, title: String): Bitmap? {
        // Exact artist + title first; then the lead artist and the bare title ("Lemonade" for
        // "Lemonade - Remix"), which finds tracks whose names the phone and iTunes spell differently.
        var art: String? = null
        for (url in ItunesArt.searchUrls(artist, title)) {
            val json = String(download(url, 64 * 1024) ?: continue, Charsets.UTF_8)
            art = ItunesArt.artworkUrl(json, artist, title) ?: continue
            break
        }
        if (art == null) return null
        val bytes = download(art, 2 * 1024 * 1024) ?: return null
        return decode(bytes)
    }

    private fun download(url: String, max: Int): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", "EchoShow/1")
            if (c.responseCode != 200) return null
            return c.inputStream.use { readCapped(it, max) }
        } finally { c.disconnect() }
    }

    private fun readCapped(i: InputStream, max: Int): ByteArray? {
        val o = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = i.read(buf); if (n < 0) break
            o.write(buf, 0, n)
            if (o.size() > max) return null
        }
        return o.toByteArray()
    }

    /** Decode subsampled to at least [size], then center-crop/scale to exactly [size]². */
    private fun decode(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2
        val src = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2),
            Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    companion object {
        const val TAG = "VesperPhone"
        const val MAX_ENTRIES = 6
        const val SETTLE_MS = 400L
    }
}

/** The iTunes Search API parts (pure, JVM-tested). */
object ItunesArt {
    fun key(artist: String, title: String) = artist.trim().lowercase() + "\u0000" + title.trim().lowercase()

    fun searchUrl(artist: String, title: String): String =
        "https://itunes.apple.com/search?term=" + URLEncoder.encode("${artist.trim()} ${title.trim()}".trim(), "UTF-8") + "&entity=song&limit=5"

    /** The exact query, then lead artist + bare title when that differs. */
    fun searchUrls(artist: String, title: String): List<String> {
        val first = searchUrl(artist, title)
        val second = searchUrl(artists(artist).firstOrNull() ?: artist, bareTitle(title))
        return if (second.equals(first, ignoreCase = true)) listOf(first) else listOf(first, second)
    }

    private val SPLIT = Regex("\\s*(?:,|&|\\+|/|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b)\\s*", RegexOption.IGNORE_CASE)

    /** "Internet Money, Don Toliver & Roddy Ricch" → [internet money, don toliver, roddy ricch]. */
    fun artists(s: String): List<String> = s.lowercase().split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** "Lemonade - Remix" / "Lemonade (feat. NAV)" → "Lemonade". */
    fun bareTitle(t: String): String = t.substringBefore(" - ").substringBefore(" (").substringBefore(" [").trim().ifEmpty { t.trim() }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Same artist: one name contains the other, or they share a credited artist. */
    fun sameArtist(a: String, b: String): Boolean {
        val x = a.trim().lowercase(); val y = b.trim().lowercase()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.contains(y) || y.contains(x)) return true
        val ys = artists(y).toSet()
        return artists(x).any { it in ys }
    }

    private val ARTWORK = Regex("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"")
    private val ARTIST = Regex("\"artistName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val TRACK = Regex("\"trackName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val SIZE = Regex("/\\d+x\\d+bb\\.")
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")

    /**
     * The first result by [artist] (case-insensitive, either name containing the other): its
     * artworkUrl100 resized to 600 px, https only. Null when no result matches.
     */
    fun artworkUrl(json: String, artist: String, title: String = ""): String? {
        if (artist.isBlank()) return null
        // One chunk per result object (they're flat), split on the key every result starts with.
        // A result counts only if it's by the same artist AND it's the same song: the exact title
        // ("Lemonade (Remix)" for "Lemonade - Remix") wins, else the same bare title ("Lemonade").
        // A different song by the same artist is never used: its cover would be wrong.
        var sameSong: String? = null
        val want = norm(title); val wantBare = norm(bareTitle(title))
        for (chunk in json.split("\"wrapperType\"").drop(1)) {
            val name = ARTIST.find(chunk)?.groupValues?.get(1)?.let(::unescape) ?: continue
            if (!sameArtist(artist, name)) continue
            val raw = ARTWORK.find(chunk)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
            if (!raw.startsWith("https://")) continue
            val url = if (SIZE.containsMatchIn(raw)) raw.replace(SIZE, "/600x600bb.") else raw
            if (want.isEmpty()) return url // no title to check (tests, odd sources): the artist's first
            val track = TRACK.find(chunk)?.groupValues?.get(1)?.let(::unescape) ?: continue
            if (norm(track) == want) return url
            if (sameSong == null && wantBare.isNotEmpty() && norm(bareTitle(track)) == wantBare) sameSong = url
        }
        return sameSong
    }

    private fun unescape(s: String) =
        UNICODE.replace(s.replace("\\/", "/").replace("\\\"", "\"")) { it.groupValues[1].toInt(16).toChar().toString() }
}
