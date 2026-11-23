package app.vesper.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reminder times from words (pure; unit-tested). Used for the assistant's `when` / `at` inputs and
 * for reminders typed on the settings page ("tomorrow 8am call the dentist").
 *
 * Understands: "in 20 minutes", "in an hour and a half", "in 2 hours", "45 minutes from now",
 * "at 7pm", "7:30", "19:00", "noon", "at 7" (the next 7 o'clock), "tonight", "this evening",
 * "tomorrow morning", "tomorrow at 8", "on friday at 6pm", "nov 24 9am", "the 3rd of december",
 * "2026-11-24 19:00". A day without a time is 9 AM; morning 8, afternoon 2 PM, evening 6 PM, night 8 PM.
 */
object ReminderTime {
    const val MORNING = 8
    const val AFTERNOON = 14
    const val EVENING = 18
    const val NIGHT = 20
    /** A day with no time ("tomorrow"): 9 AM. */
    const val DAY_DEFAULT = 9

    /** [atMillis] and what's left of the phrase once the time words are taken out ("take out the trash"). */
    data class Parsed(val atMillis: Long, val text: String)

    private const val AMT = "(?:\\d+(?:\\.\\d+)?|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|twenty[- ]five|thirty|forty|forty[- ]five|fifty|sixty|ninety|half an?|a couple of|a couple|couple of|a few|few)"
    private const val UNIT = "(?:minutes?|mins?|min|hours?|hrs?|hr|h|m|days?|weeks?)"
    private const val PAIR = "$AMT\\s*$UNIT\\b"
    private val PAIR_RE = Regex("($AMT)\\s*($UNIT)\\b")
    private val REL = Regex("\\bin\\s+$PAIR(?:\\s*(?:and\\s+)?(?:a half\\b|$PAIR))*")
    private val REL_FROM_NOW = Regex("\\b$PAIR(?:\\s*(?:and\\s+)?(?:a half\\b|$PAIR))*\\s+from now\\b")

    private val ISO = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[t ](\\d{1,2}):(\\d{2}))?\\b")
    private val DAY_AFTER = Regex("\\b(?:the\\s+)?day after tomorrow\\b")
    private val TOMORROW = Regex("\\b(?:tomorrow|tmrw|tmr)\\b")
    private val TONIGHT = Regex("\\btonight\\b")
    private val TODAY = Regex("\\b(?:today|later today)\\b")
    private val THIS_PART = Regex("\\bthis (morning|afternoon|evening)\\b")
    private val WEEKDAY = Regex("\\b(?:on\\s+|next\\s+|this\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\\b")
    private const val MONTHS = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
    private val MONTH_DAY = Regex("\\b(?:on\\s+)?$MONTHS\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")
    private val DAY_MONTH = Regex("\\b(?:on\\s+)?(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTHS(?=\\s|$)")
    private val PART = Regex("\\b(?:in the |at |this |the )?(morning|afternoon|evening|night)\\b")
    private val NOON = Regex("\\b(?:at\\s+|by\\s+)?(noon|midday|midnight)\\b")
    private val HM = Regex("\\b(?:at\\s+|by\\s+|around\\s+)?(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?(?=\\s|$|[,.!?])")
    private val H_AMPM = Regex("\\b(?:at\\s+|by\\s+|around\\s+)?(\\d{1,2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?=\\s|$|[,.!?])")
    private val H_AT = Regex("\\b(?:at|by|around)\\s+(\\d{1,2})(?:\\s*o'?clock)?\\b")
    private val H_OCLOCK = Regex("\\b(\\d{1,2})\\s*o'?clock\\b")

    private val NUMBERS = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "eleven" to 11.0, "twelve" to 12.0, "fifteen" to 15.0,
        "twenty" to 20.0, "twenty five" to 25.0, "twenty-five" to 25.0, "thirty" to 30.0, "forty" to 40.0, "forty five" to 45.0,
        "forty-five" to 45.0, "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0, "half a" to 0.5, "half an" to 0.5,
        "a couple of" to 2.0, "a couple" to 2.0, "couple of" to 2.0, "a few" to 3.0, "few" to 3.0,
    )

    /** Milliseconds in a relative phrase like "an hour and a half" / "1 hour 30 minutes". */
    internal fun durationMs(chunk: String): Long {
        var total = 0.0
        var lastUnit = 0L
        for (m in PAIR_RE.findAll(chunk)) {
            val amt = m.groupValues[1].let { it.toDoubleOrNull() ?: NUMBERS[it.replace(Regex("\\s+"), " ")] ?: 1.0 }
            val unit = unitMs(m.groupValues[2])
            total += amt * unit
            lastUnit = unit
        }
        if (Regex("\\ba half\\b").containsMatchIn(chunk.replace(Regex("half an?\\s"), ""))) total += 0.5 * lastUnit
        return total.roundToLong()
    }

    private fun unitMs(u: String): Long = when {
        u.startsWith("w") -> 7 * 86_400_000L
        u.startsWith("d") -> 86_400_000L
        u.startsWith("h") -> 3_600_000L
        else -> 60_000L
    }

    /**
     * Finds a time in [input]. Null if there's none. A time of day with no day is the next one
     * ("at 7am" at 9am is tomorrow); an explicit day in the past (e.g. "this morning" in the afternoon)
     * is returned as is, so the caller can say it has passed.
     */
    fun parse(input: String, now: Long, zone: ZoneId): Parsed? {
        val original = input.replace(Regex("\\s+"), " ").trim()
        if (original.isEmpty()) return null
        val lower = original.lowercase(Locale.ROOT).let { if (it.length == original.length) it else original }
        val buf = StringBuilder(lower)
        val keep = BooleanArray(original.length) { true }

        fun take(re: Regex): MatchResult? {
            // match a snapshot: a MatchResult reads its groups from the input lazily, and buf changes below
            val m = re.find(buf.toString()) ?: return null
            for (i in m.range) { buf.setCharAt(i, ' '); keep[i] = false }
            return m
        }

        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZ.toLocalDate()

        // Relative: "in 20 minutes", "an hour from now"
        val rel = take(REL) ?: take(REL_FROM_NOW)
        if (rel != null) {
            val ms = durationMs(rel.value)
            if (ms <= 0) return null
            return Parsed(now + ms, textOf(original, keep))
        }

        var date: LocalDate? = null
        var weekday: DayOfWeek? = null
        var part: Int? = null
        var hour: Int? = null
        var minute = 0
        var ampm: String? = null

        take(ISO)?.let { m ->
            date = try { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (e: Exception) { null }
            if (m.groupValues[4].isNotEmpty()) { hour = m.groupValues[4].toInt(); minute = m.groupValues[5].toInt(); ampm = "24" }
        }
        if (date == null) when {
            take(DAY_AFTER) != null -> date = today.plusDays(2)
            take(TOMORROW) != null -> date = today.plusDays(1)
            take(TONIGHT) != null -> { date = today; part = NIGHT }
            take(TODAY) != null -> date = today
        }
        if (date == null) take(THIS_PART)?.let { m -> date = today; part = partHour(m.groupValues[1]) }
        if (date == null) take(MONTH_DAY)?.let { m -> date = monthDay(today, month(m.groupValues[1]), m.groupValues[2].toInt()) }
        if (date == null) take(DAY_MONTH)?.let { m -> date = monthDay(today, month(m.groupValues[2]), m.groupValues[1].toInt()) }
        if (date == null) take(WEEKDAY)?.let { m -> weekday = DayOfWeek.valueOf(m.groupValues[1].uppercase(Locale.ROOT)) }

        if (hour == null) take(NOON)?.let { m -> hour = if (m.groupValues[1] == "midnight") 0 else 12; ampm = "24" }
        if (hour == null) take(HM)?.let { m ->
            hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt()
            // "07:30" / "19:00" are 24-hour; "7:30" may be either
            ampm = m.groupValues[3].ifEmpty { if (m.groupValues[1].startsWith("0") && m.groupValues[1].length == 2) "24" else null }
        }
        if (hour == null) take(H_AMPM)?.let { m -> hour = m.groupValues[1].toInt(); ampm = m.groupValues[2] }
        if (hour == null) (take(H_AT) ?: take(H_OCLOCK))?.let { m -> hour = m.groupValues[1].toInt() }
        if (part == null) take(PART)?.let { m -> part = partHour(m.groupValues[1]) }

        if (date == null && weekday == null && hour == null && part == null) return null
        if (hour != null && (hour!! > 23 || minute > 59)) return null

        val dayGiven = date != null || weekday != null
        // Hour of day, 0..23
        var h: Int
        var flexible = false // a bare "at 7" with no day: the next 7 o'clock (AM or PM)
        when {
            hour == null -> h = part ?: DAY_DEFAULT
            ampm == "24" -> h = hour!!
            ampm != null -> h = hour!!.let { v -> if (v > 12) return null; val pm = ampm!!.startsWith("p"); (v % 12) + if (pm) 12 else 0 }
            hour!! > 12 || hour == 0 -> h = hour!!
            part != null -> h = hour!!.let { v -> if (part!! >= 12) (v % 12) + 12 else v % 12 }
            dayGiven -> h = hour!!.let { v -> if (v in 1..6) v + 12 else v }
            else -> { h = hour!!; flexible = true }
        }

        val day: LocalDate = date ?: weekday?.let { wd ->
            val ahead = ((wd.value - today.dayOfWeek.value) + 7) % 7
            today.plusDays(ahead.toLong())
        } ?: today

        var at = ZonedDateTime.of(day, LocalTime.of(h % 24, minute), zone)
        if (flexible) {
            // the next of h and h+12 after now
            val am = at.withHour(h % 12)
            val pm = at.withHour(h % 12 + 12)
            at = listOf(am, pm, am.plusDays(1)).first { it.toInstant().toEpochMilli() > now }
        } else if (!dayGiven && at.toInstant().toEpochMilli() <= now) {
            at = at.plusDays(1)
        } else if (weekday != null && date == null && at.toInstant().toEpochMilli() <= now) {
            at = at.plusDays(7)
        }
        return Parsed(at.toInstant().toEpochMilli(), textOf(original, keep))
    }

    /**
     * The assistant's `at`: a local ISO date-time ("2026-11-24T19:00", seconds optional, a space
     * works too), a time of day ("19:00", the next one), or words ([parse]). Null if not a time.
     */
    fun at(spec: String, now: Long, zone: ZoneId): Long? {
        val s = spec.trim()
        if (s.isEmpty()) return null
        try {
            return LocalDateTime.parse(s.replace(' ', 'T').removeSuffix("Z")).atZone(zone).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) { /* not a full date-time */ }
        Regex("^(\\d{1,2}):(\\d{2})(?::\\d{2})?$").matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return null
            var t = ZonedDateTime.of(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), LocalTime.of(h, min), zone)
            if (t.toInstant().toEpochMilli() <= now) t = t.plusDays(1)
            return t.toInstant().toEpochMilli()
        }
        return parse(s, now, zone)?.atMillis
    }

    /** The reminder's words without "remind me to …" and leftover joiners. */
    fun cleanText(s: String): String {
        var t = s.replace(Regex("\\s+"), " ").trim().trim(',', '.', '!', '?', ';', ':', '-').trim()
        val lead = Regex("^(?:(?:hey|ok|okay|please)\\s+)*(?:(?:can you|could you|would you|will you)\\s+)?(?:please\\s+)?(?:remind (?:me|us)|set (?:a |me a )?reminder|reminder|add (?:a )?reminder)(?:\\s+(?:to|that|about|for))?\\s*", RegexOption.IGNORE_CASE)
        t = t.replace(lead, "")
        t = t.replace(Regex("^(?:to|that|about|for)\\s+", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(?:at|on|by|in|for|around)$", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("\\s+(?:please)$", RegexOption.IGNORE_CASE), "")
        return t.trim().trim(',', '.', '!', '?', ';', ':', '-').trim()
    }

    private fun textOf(original: String, keep: BooleanArray): String {
        val sb = StringBuilder()
        for (i in original.indices) sb.append(if (keep[i]) original[i] else ' ')
        return cleanText(sb.toString())
    }

    private fun partHour(p: String) = when (p) { "morning" -> MORNING; "afternoon" -> AFTERNOON; "evening" -> EVENING; else -> NIGHT }

    private fun month(s: String): Int = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .indexOf(s.take(3)) + 1

    /** That day this year, or next year if it has passed. */
    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? = try {
        val d = LocalDate.of(today.year, month, day)
        if (d.isBefore(today)) d.plusYears(1) else d
    } catch (e: Exception) { null }

    // ---- showing times ----

    /** "7:00 PM" (today), "Tomorrow 8:00 AM", "Wed 9:00 AM" (this week), "Nov 30 9:00 AM"; 24-hour with [h24]. */
    fun describe(atMillis: Long, now: Long, zone: ZoneId, h24: Boolean = false): String {
        val t = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = clock(t.toLocalTime(), h24)
        val d = t.toLocalDate()
        return when {
            d == today -> clock
            d == today.plusDays(1) -> "Tomorrow $clock"
            d == today.minusDays(1) -> "Yesterday $clock"
            d.isAfter(today) && d.isBefore(today.plusDays(7)) -> "${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)} $clock"
            else -> "${d.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))} $clock"
        }
    }

    /** "7:00 PM" / "19:00". */
    fun clock(t: LocalTime, h24: Boolean = false): String =
        if (h24) "%d:%02d".format(t.hour, t.minute)
        else "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour < 12) "AM" else "PM")

    /** A day-independent label ("Tue Nov 24 · 7:00 PM") that doesn't change meaning at midnight. */
    fun absolute(atMillis: Long, zone: ZoneId, h24: Boolean = false): String {
        val t = Instant.ofEpochMilli(atMillis).atZone(zone)
        return "${t.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))} · ${clock(t.toLocalTime(), h24)}"
    }

    /** Local ISO for the assistant ("2026-11-24T19:00"). */
    fun iso(atMillis: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDateTime().withSecond(0).withNano(0).toString()
}
