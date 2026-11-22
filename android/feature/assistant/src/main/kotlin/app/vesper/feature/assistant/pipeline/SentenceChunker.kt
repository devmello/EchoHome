package app.vesper.feature.assistant.pipeline

/**
 * Cuts Claude's streamed answer into pieces the voice can start on before the answer is complete:
 * whole sentences, and for the first piece also a clause (at a comma, semicolon, colon or dash
 * after [firstClauseWords] words), so the first sound comes quickly. Not thread-safe.
 */
class SentenceChunker(private val firstClauseWords: Int = 4) {
    private val buf = StringBuilder()
    private var emitted = 0

    /** Adds streamed text and returns the pieces it completed (maybe none). */
    fun add(delta: String): List<String> {
        buf.append(delta)
        val out = ArrayList<String>()
        while (true) {
            val cut = nextCut() ?: break
            val piece = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (piece.isNotEmpty()) { out += piece; emitted++ }
        }
        return out
    }

    /** The rest of the text, once the answer is complete (null when nothing is left). */
    fun flush(): String? {
        val rest = buf.toString().trim()
        buf.setLength(0)
        return rest.ifEmpty { null }?.also { emitted++ }
    }

    /** Drops text not yet emitted (e.g. Claude's words before a tool call). */
    fun reset() { buf.setLength(0) }

    /** Index just after the end of the next complete piece, or null. */
    private fun nextCut(): Int? {
        var words = 0
        var inWord = false
        for (i in buf.indices) {
            val c = buf[i]
            if (c.isWhitespace()) { inWord = false; continue }
            if (!inWord) { words++; inWord = true }
            // A boundary needs the next char to be known (space), so "9.30" and "a.m." aren't cut.
            val next = buf.getOrNull(i + 1) ?: return null
            if (!next.isWhitespace()) continue
            when (c) {
                '.', '!', '?' -> if (!abbreviation(i)) return i + 1
                ',', ';', ':' -> if (emitted == 0 && words >= firstClauseWords) return i + 1
                '—', '–' -> if (emitted == 0 && words >= firstClauseWords) return i + 1
            }
        }
        return null
    }

    /** "Dr." / "Mr." / "St." / "a.m." / single letters: a period that doesn't end a sentence. */
    private fun abbreviation(dot: Int): Boolean {
        if (buf[dot] != '.') return false
        var s = dot
        while (s > 0 && !buf[s - 1].isWhitespace()) s--
        val word = buf.substring(s, dot).lowercase()
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter()) || word.contains('.')
    }

    private companion object {
        val ABBREVIATIONS = setOf("dr", "mr", "mrs", "ms", "st", "vs", "etc", "e.g", "i.e", "approx")
    }
}
