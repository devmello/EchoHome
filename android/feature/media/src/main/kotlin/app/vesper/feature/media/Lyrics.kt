package app.vesper.feature.media

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/** One synced lyric line: shown from [timeMs] until the next line's time. Blank [text] = an instrumental gap. */
internal class LyricLine(val timeMs: Long, val text: String, val words: List<Word>? = null)

/** A word (or syllable) of a line: sung from [startMs]; [from]..[to] are char offsets into the line's text. */
internal class Word(val startMs: Long, val from: Int, val to: Int)

/** What LRCLIB knows about one track. */
internal sealed class Lyrics {
    class Synced(val lines: List<LyricLine>) : Lyrics()
    class Plain(val lines: List<String>) : Lyrics()
    object None : Lyrics()
}

/** Word start times for lines that only have a line stamp. */
internal object WordTiming {
    /** Fraction of the gap to the next line that a line's words may use. */
    const val SPAN = 0.9f

    fun syllables(w: String): Int {
        var n = 0; var inV = false
        for (c in w.lowercase()) {
            val v = c in "aeiouy"
            if (v && !inV) n++
            inV = v
        }
        // Silent trailing "e" ("love", "time"), not "-le" / a lone "e".
        if (n > 1 && w.length > 2 && w.endsWith("e", true) && !w.endsWith("le", true) && w[w.length - 2].lowercaseChar() !in "aeiouy") n--
        return maxOf(1, n)
    }

    /** End of the sung part of a line starting at [startMs] with the next line at [nextMs]. */
    fun sungEnd(startMs: Long, nextMs: Long, text: String): Long {
        val gap = maxOf(0L, nextMs - startMs)
        // 0.9 of the gap, and never more than ~0.6 s per syllable (a long instrumental gap doesn't stretch the line).
        val syl = text.split(' ').filter { it.isNotEmpty() }.sumOf { syllables(it) }
        val cap = maxOf(2500L, syl * 600L)
        return startMs + minOf((gap * SPAN).toLong(), cap)
    }

    /** Words of [text] with estimated starts inside [startMs, sungEnd): weighted by syllables, plus a small floor per word. */
    fun estimate(text: String, startMs: Long, nextMs: Long): List<Word> {
        val spans = ArrayList<IntArray>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            var j = i
            while (j < text.length && text[j] != ' ') j++
            spans.add(intArrayOf(i, j)); i = j
        }
        if (spans.isEmpty()) return emptyList()
        val weights = spans.map { syllables(text.substring(it[0], it[1])) + 0.3 }
        val total = weights.sum()
        val span = sungEnd(startMs, nextMs, text) - startMs
        var acc = 0.0
        return spans.mapIndexed { k, sp ->
            val w = Word(startMs + (span * acc / total).toLong(), sp[0], sp[1])
            acc += weights[k]; w
        }
    }
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
            val (text, words) = splitWords(s)
            for (t in times) out.add(LyricLine(t, text, words))
        }
        if (offset != 0L) return out.map { l ->
            LyricLine((l.timeMs - offset).coerceAtLeast(0), l.text, l.words?.map { Word((it.startMs - offset).coerceAtLeast(0), it.from, it.to) })
        }.sortedBy { it.timeMs }
        return out.sortedBy { it.timeMs } // stable
    }

    /**
     * Enhanced LRC (A2): `<mm:ss.xx>Hello <mm:ss.xx>world`. Returns the plain text (single spaces) and,
     * when the line carries word stamps, the words with their start times (never going backwards).
     * Text before the first stamp starts with the line.
     */
    internal fun splitWords(src: String): Pair<String, List<Word>?> {
        val tags = WORD_TAG.findAll(src).toList()
        if (tags.isEmpty()) return src.trim().replace(WS, " ") to null
        val sb = StringBuilder()
        val words = ArrayList<Word>()
        var last = 0L
        fun seg(raw: String, t: Long?) {
            if (raw.isEmpty()) return
            if (raw.first().isWhitespace() && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            val core = raw.trim().replace(WS, " ")
            if (core.isNotEmpty()) {
                val from = sb.length
                sb.append(core)
                last = maxOf(last, t ?: last)
                words.add(Word(last, from, sb.length))
            }
            if (raw.last().isWhitespace() && sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
        }
        var pos = 0
        var pending: Long? = null
        for (m in tags) {
            seg(src.substring(pos, m.range.first), pending)
            pending = time(m.value.substring(1, m.value.length - 1))
            pos = m.range.last + 1
        }
        seg(src.substring(pos), pending)
        val text = sb.toString().trim()
        return text to words.filter { it.from < text.length }.map { Word(it.startMs, it.from, minOf(it.to, text.length)) }.ifEmpty { null }
    }

    private val WS = Regex("""\s+""")

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
