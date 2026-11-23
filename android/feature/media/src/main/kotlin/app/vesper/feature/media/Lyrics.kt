package app.vesper.feature.media

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** One synced lyric line: shown from [timeMs] until the next line's time. Blank [text] = an instrumental gap. */
internal class LyricLine(val timeMs: Long, val text: String)

/** What LRCLIB knows about one track. */
internal sealed class Lyrics {
    class Synced(val lines: List<LyricLine>) : Lyrics()
    class Plain(val lines: List<String>) : Lyrics()
    object None : Lyrics()
}

internal object Lrc {
    private val TIME = Regex("""^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")
    private val WORD_TAG = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /**
     * Parses LRC: `[mm:ss.xx]text`, several stamps per line, `[offset:±ms]` (positive = lyrics
     * come earlier, per the LRC spec), enhanced word tags `<mm:ss.xx>` are dropped, other
     * `[ar:…]` style tags are ignored. Lines come back sorted by time.
     */
    fun parse(src: String): List<LyricLine> {
        var offset = 0L
        val out = ArrayList<LyricLine>()
        for (raw in src.lineSequence()) {
            var s = raw.trim()
            if (s.isEmpty() || s[0] != '[') continue
            val times = ArrayList<Long>(2)
            while (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) break
                val tag = s.substring(1, end).trim()
                val t = time(tag)
                if (t != null) times.add(t)
                else if (tag.startsWith("offset:", ignoreCase = true)) offset = tag.substring(7).trim().removePrefix("+").toLongOrNull() ?: offset
                s = s.substring(end + 1)
                if (t == null) break // a metadata tag has no text
            }
            if (times.isEmpty()) continue
            val text = WORD_TAG.replace(s, "").trim()
            for (t in times) out.add(LyricLine(t, text))
        }
        if (offset != 0L) return out.map { LyricLine((it.timeMs - offset).coerceAtLeast(0), it.text) }.sortedBy { it.timeMs }
        return out.sortedBy { it.timeMs } // stable
    }

    private fun time(tag: String): Long? {
        val m = TIME.matchEntire(tag) ?: return null
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return (min * 60 + sec) * 1000 + ms
    }

    /** Index of the line being sung at [posMs] (the last line starting at or before it), or -1 before the first. */
    fun lineAt(lines: List<LyricLine>, posMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** When the line after [index] starts, or -1 when [index] is the last line. */
    fun nextStart(lines: List<LyricLine>, index: Int): Long =
        if (index + 1 < lines.size) lines[index + 1].timeMs else -1L

    /** Greedy word wrap to [maxW] using [width]; a word wider than the line is kept whole. */
    fun wrap(text: String, maxW: Float, width: (String) -> Float): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>(2)
        var cur = ""
        for (w in text.split(' ').filter { it.isNotEmpty() }) {
            val trial = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isNotEmpty() && width(trial) > maxW) { out.add(cur); cur = w } else cur = trial
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }
}

/** LRCLIB responses and the cache key. Pure, so it's unit-tested. */
internal object LrcLib {
    fun key(title: String, artist: String, album: String, durationMs: Long): String =
        listOf(title, artist, album, (durationMs / 1000).toString()).joinToString("|") { it.trim().lowercase(Locale.ROOT) }

    /** A `/api/get` object or one `/api/search` entry as [Lyrics]; null when it has neither kind. */
    fun fromRecord(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental", false)) return Lyrics.None
        val synced = o.optString("syncedLyrics", "")
        if (synced.isNotBlank() && synced != "null") {
            val lines = Lrc.parse(synced)
            if (lines.isNotEmpty()) return Lyrics.Synced(lines)
        }
        val plain = o.optString("plainLyrics", "")
        if (plain.isNotBlank() && plain != "null") return Lyrics.Plain(plain.lines().map { it.trim() })
        return null
    }

    /** Best `/api/search` entry: synced lyrics within a few seconds of [durationSec] first, then plain. */
    fun fromSearch(arr: JSONArray, durationSec: Long): Lyrics? {
        val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        fun close(o: JSONObject) = durationSec <= 0 || o.optDouble("duration", 0.0).let { it <= 0.0 || abs(it - durationSec) <= 4.0 }
        val parsed = entries.filter(::close).mapNotNull { fromRecord(it) }
        return parsed.firstOrNull { it is Lyrics.Synced } ?: parsed.firstOrNull { it is Lyrics.Plain }
    }
}
