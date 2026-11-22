package app.vesper.feature.assistant.pipeline

/**
 * Turns the answer as shown ("Your alarm is set for 6:40 AM, 58°.") into words the on-device
 * voice can say. The Piper voice reads phonemes from a word lexicon (sherpa-onnx 2.x has no
 * espeak-ng), which has no digits or symbols, so times, numbers, %, ° and AM/PM are spelled out.
 */
object SpeechText {
    private val TIME = Regex("""\b(\d{1,2}):(\d{2})(?:\s*([AaPp])\.?\s?[Mm]\.?\b)?""")
    private val AMPM = Regex("""\b(\d{1,2})\s*([AaPp])\.?\s?[Mm]\.?\b""")
    private val DECIMAL = Regex("""(-?)\b(\d+)\.(\d+)\b""")
    private val NUMBER = Regex("""(-?)\b(\d{1,9})\b""")
    private val ORDINAL = Regex("""\b(\d{1,2})(st|nd|rd|th)\b""")

    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ORD = mapOf("one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
        "nine" to "ninth", "twelve" to "twelfth")

    fun forSpeech(text: String): String {
        var s = text.replace(Regex("""[*_#`]+"""), "").replace('’', '\'').replace('‘', '\'')
        s = TIME.replace(s) { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            val mm = when { min == 0 -> if (m.groupValues[3].isEmpty()) "o'clock" else ""; min < 10 -> "oh ${words(min)}"; else -> words(min) }
            listOf(words(h), mm, ampm(m.groupValues[3])).filter { it.isNotEmpty() }.joinToString(" ")
        }
        s = AMPM.replace(s) { m -> words(m.groupValues[1].toInt()) + " " + ampm(m.groupValues[2]) }
        s = ORDINAL.replace(s) { m -> ordinal(words(m.groupValues[1].toInt())) }
        s = s.replace("%", " percent").replace("°F", " degrees").replace("°C", " degrees Celsius").replace("°", " degrees")
        s = DECIMAL.replace(s) { m -> (if (m.groupValues[1].isNotEmpty()) "minus " else "") +
            words(m.groupValues[2].toLong()) + " point " + m.groupValues[3].map { ONES[it - '0'] }.joinToString(" ") }
        s = NUMBER.replace(s) { m -> (if (m.groupValues[1].isNotEmpty()) "minus " else "") + words(m.groupValues[2].toLong()) }
        return s.replace("&", " and ").replace(Regex("""\s+"""), " ").replace(" ,", ",").replace(" .", ".").trim()
    }

    private fun ampm(x: String) = when (x.lowercase()) { "a" -> "a m"; "p" -> "p m"; else -> "" }

    private fun ordinal(w: String): String {
        val last = w.substringAfterLast(' ')
        val head = w.dropLast(last.length)
        val o = ORD[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return head + o
    }

    fun words(n: Int): String = words(n.toLong())

    fun words(n: Long): String = when {
        n < 0 -> "minus " + words(-n)
        n < 20 -> ONES[n.toInt()]
        n < 100 -> TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        n < 1000 -> ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + words(n % 100) else ""
        n in 1100..9999 && n % 100 != 0L && n / 100 % 10 != 0L -> words(n / 100) + " " + words(n % 100) // years: 2026 stays "two thousand twenty six"
        n < 1_000_000 -> words(n / 1000) + " thousand" + if (n % 1000 != 0L) " " + words(n % 1000) else ""
        else -> words(n / 1_000_000) + " million" + if (n % 1_000_000 != 0L) " " + words(n % 1_000_000) else ""
    }
}
