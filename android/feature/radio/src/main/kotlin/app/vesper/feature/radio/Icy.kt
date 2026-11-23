package app.vesper.feature.radio

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * SHOUTcast / Icecast in-stream metadata ("ICY"). With the request header `Icy-MetaData: 1` a
 * server that supports it answers `icy-metaint: N` and, after every N audio bytes, sends one
 * length byte L and L × 16 bytes of text such as `StreamTitle='Artist - Title';StreamUrl='';`.
 * [IcyStripper] removes those blocks from the audio (so the decoder never sees them) and reports
 * each title. Pure; unit-tested.
 */
class IcyStripper(private val metaInt: Int, private val onMeta: (String) -> Unit) {
    private var audioLeft = metaInt
    /** -1 = expecting the length byte; ≥ 0 = metadata bytes still to read. */
    private var metaLeft = -2
    private var meta = ByteArray(0)
    private var metaLen = 0

    init { require(metaInt > 0) }

    /** Feeds [len] stream bytes; writes the audio part to [out]. */
    fun feed(buf: ByteArray, off: Int, len: Int, out: OutputStream) {
        var i = off
        val end = off + len
        while (i < end) {
            when {
                metaLeft == -2 -> {
                    // audio
                    val n = minOf(audioLeft, end - i)
                    out.write(buf, i, n)
                    i += n
                    audioLeft -= n
                    if (audioLeft == 0) metaLeft = -1
                }
                metaLeft == -1 -> {
                    val l = (buf[i].toInt() and 0xFF) * 16
                    i++
                    if (l == 0) { audioLeft = metaInt; metaLeft = -2 } else {
                        if (meta.size < l) meta = ByteArray(l)
                        metaLen = 0
                        metaLeft = l
                    }
                }
                else -> {
                    val n = minOf(metaLeft, end - i)
                    System.arraycopy(buf, i, meta, metaLen, n)
                    metaLen += n
                    metaLeft -= n
                    i += n
                    if (metaLeft == 0) {
                        onMeta(decode(meta, metaLen))
                        audioLeft = metaInt
                        metaLeft = -2
                    }
                }
            }
        }
    }

    companion object {
        /** UTF-8 if it's valid, else Latin-1 (older servers); trailing NUL padding dropped. */
        fun decode(b: ByteArray, len: Int): String {
            var n = len
            while (n > 0 && b[n - 1].toInt() == 0) n--
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, 0, n)).toString()
            } catch (e: CharacterCodingException) {
                String(b, 0, n, Charsets.ISO_8859_1)
            }
        }
    }
}

object IcyMeta {
    private val TITLE = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL)
    /** Titles stations send between songs or for ads; shown as "no song". */
    private val NOISE = Regex("^(-|unknown|n/?a|null|advert.*|commercial.*|.*\\bad break\\b.*|station id|live|\\s*)$", RegexOption.IGNORE_CASE)

    /** The `StreamTitle` of one metadata block, trimmed; null if absent or empty / noise. */
    fun streamTitle(block: String): String? {
        val t = TITLE.find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return t.takeUnless { it.isEmpty() || NOISE.matches(it) || it.trim('-', ' ').isEmpty() }
    }

    /** "Artist - Title" → (artist, title); no separator → (null, whole). */
    fun split(title: String): Pair<String?, String> {
        for (sep in listOf(" - ", " – ", " — ", " ~ ")) {
            val i = title.indexOf(sep)
            if (i > 0 && i + sep.length < title.length) {
                val a = title.substring(0, i).trim()
                val t = title.substring(i + sep.length).trim()
                if (a.isNotEmpty() && t.isNotEmpty()) return a to t
            }
        }
        return null to title
    }
}
