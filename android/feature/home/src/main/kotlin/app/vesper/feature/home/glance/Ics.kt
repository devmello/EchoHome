package app.vesper.feature.home.glance

import java.io.BufferedReader
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * A small iCalendar (RFC 5545) reader for calendar subscriptions (Google's "secret address in
 * iCal format", iCloud public calendars):
 *  - VEVENT with SUMMARY, LOCATION, DESCRIPTION, UID, STATUS, DTSTART/DTEND/DURATION
 *    (UTC, TZID — IANA, Outlook/Windows names, Mozilla-prefixed — floating, and all-day dates);
 *  - RRULE FREQ=DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL, COUNT, UNTIL, BYDAY (incl. 2MO / -1FR),
 *    BYMONTHDAY, BYMONTH; EXDATE; RDATE; RECURRENCE-ID overrides; cancelled events dropped.
 * Not supported (rare in subscriptions): BYSETPOS, BYWEEKNO, BYYEARDAY, HOURLY and finer.
 */
object Ics {
    /** A DATE or DATE-TIME. [time] null = all-day date; [zone] null = floating (device zone). */
    data class Time(val date: LocalDate, val time: LocalTime?, val zone: ZoneId?) {
        val allDay get() = time == null
        fun local(): LocalDateTime = date.atTime(time ?: LocalTime.MIDNIGHT)
        fun instant(deviceZone: ZoneId): Instant = local().atZone(zone ?: deviceZone).toInstant()
    }

    data class Rule(
        val freq: String,
        val interval: Int = 1,
        val count: Int? = null,
        val until: Time? = null,
        /** (ordinal or 0, weekday), e.g. 2MO → (2, MONDAY), FR → (0, FRIDAY). */
        val byDay: List<Pair<Int, DayOfWeek>> = emptyList(),
        val byMonthDay: List<Int> = emptyList(),
        val byMonth: List<Int> = emptyList(),
        val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    )

    data class Event(
        val uid: String,
        val summary: String,
        val location: String?,
        val description: String?,
        val start: Time,
        val end: Time?,
        val duration: Duration?,
        val rule: Rule?,
        val exDates: List<Time>,
        val rDates: List<Time>,
        val recurrenceId: Time?,
        val cancelled: Boolean,
    )

    data class Calendar(val name: String?, val events: List<Event>)

    /** One concrete occurrence. Instants; all-day ones run from local midnight to midnight. */
    data class Occurrence(val event: Event, val start: Instant, val end: Instant, val allDay: Boolean)

    // ---- parsing ----

    /**
     * Streams [reader]; with [keepFrom]/[keepTo], one-off events wholly outside that range are
     * dropped while parsing, so a years-long Google feed doesn't sit in memory.
     */
    fun parse(reader: BufferedReader, keepFrom: Instant? = null, keepTo: Instant? = null, deviceZone: ZoneId = ZoneId.systemDefault()): Calendar {
        var name: String? = null
        val events = ArrayList<Event>()
        var cur: HashMap<String, MutableList<Prop>>? = null
        var depth = 0 // nesting inside VEVENT (VALARM etc.)
        unfold(reader) { line ->
            val p = prop(line) ?: return@unfold
            when {
                p.name == "BEGIN" && p.value == "VEVENT" -> { cur = HashMap(); depth = 0 }
                p.name == "BEGIN" && cur != null -> depth++
                p.name == "END" && p.value == "VEVENT" && cur != null -> {
                    toEvent(cur!!)?.let { e -> if (keep(e, keepFrom, keepTo, deviceZone)) events += e }
                    cur = null
                }
                p.name == "END" && cur != null -> depth--
                cur != null && depth == 0 -> cur!!.getOrPut(p.name) { ArrayList() }.add(p)
                cur == null && p.name == "X-WR-CALNAME" -> name = unescape(p.value)
            }
        }
        return Calendar(name, events)
    }

    private fun keep(e: Event, from: Instant?, to: Instant?, zone: ZoneId): Boolean {
        if (e.rule != null || e.rDates.isNotEmpty() || e.recurrenceId != null || from == null || to == null) return true
        val s = e.start.instant(zone)
        val end = endOf(e, e.start, zone)
        return end > from.minus(1, ChronoUnit.DAYS) && s < to.plus(1, ChronoUnit.DAYS)
    }

    internal data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private inline fun unfold(r: BufferedReader, block: (String) -> Unit) {
        var pending: StringBuilder? = null
        while (true) {
            val raw = r.readLine() ?: break
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t')) { pending?.append(raw, 1, raw.length); continue }
            pending?.let { block(it.toString()) }
            pending = StringBuilder(raw)
        }
        pending?.let { block(it.toString()) }
    }

    internal fun prop(line: String): Prop? {
        var inQuote = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuote = !inQuote else if (c == ':' && !inQuote) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = HashMap<String, String>()
        for (h in head.drop(1)) {
            val k = h.substringBefore('=').uppercase()
            params[k] = h.substringAfter('=', "").trim('"')
        }
        return Prop(head[0].uppercase().trim(), params, line.substring(colon + 1))
    }

    fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n', 'N' -> b.append('\n'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    private fun toEvent(m: Map<String, List<Prop>>): Event? {
        fun one(k: String) = m[k]?.firstOrNull()
        val start = one("DTSTART")?.let(::time) ?: return null
        return Event(
            uid = one("UID")?.value ?: "",
            summary = one("SUMMARY")?.value?.let(::unescape)?.trim().orEmpty(),
            location = one("LOCATION")?.value?.let(::unescape)?.trim()?.ifEmpty { null },
            description = one("DESCRIPTION")?.value?.let(::unescape)?.trim()?.ifEmpty { null },
            start = start,
            end = one("DTEND")?.let(::time),
            duration = one("DURATION")?.value?.let(::duration),
            rule = one("RRULE")?.value?.let { rule(it, start) },
            exDates = m["EXDATE"].orEmpty().flatMap(::times),
            rDates = m["RDATE"].orEmpty().filter { it.params["VALUE"] != "PERIOD" }.flatMap(::times),
            recurrenceId = one("RECURRENCE-ID")?.let(::time),
            cancelled = one("STATUS")?.value?.uppercase() == "CANCELLED",
        )
    }

    private fun times(p: Prop): List<Time> = p.value.split(',').mapNotNull { v -> time(p.copy(value = v.trim())) }

    internal fun time(p: Prop): Time? {
        val v = p.value.trim()
        return try {
            if (p.params["VALUE"] == "DATE" || v.length == 8) Time(LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt()), null, null)
            else {
                val d = LocalDate.of(v.substring(0, 4).toInt(), v.substring(4, 6).toInt(), v.substring(6, 8).toInt())
                val t = LocalTime.of(v.substring(9, 11).toInt(), v.substring(11, 13).toInt(), if (v.length >= 15) v.substring(13, 15).toInt() else 0)
                val zone: ZoneId? = when {
                    v.endsWith("Z") -> ZoneOffset.UTC
                    p.params["TZID"] != null -> zone(p.params.getValue("TZID"))
                    else -> null
                }
                Time(d, t, zone)
            }
        } catch (_: Exception) { null }
    }

    private val WINDOWS_ZONES = mapOf(
        "pacific standard time" to "America/Los_Angeles", "mountain standard time" to "America/Denver",
        "central standard time" to "America/Chicago", "eastern standard time" to "America/New_York",
        "us mountain standard time" to "America/Phoenix", "alaskan standard time" to "America/Anchorage",
        "hawaiian standard time" to "Pacific/Honolulu", "gmt standard time" to "Europe/London",
        "w. europe standard time" to "Europe/Berlin", "romance standard time" to "Europe/Paris",
        "central europe standard time" to "Europe/Budapest", "india standard time" to "Asia/Kolkata",
        "tokyo standard time" to "Asia/Tokyo", "aus eastern standard time" to "Australia/Sydney",
        "utc" to "UTC", "coordinated universal time" to "UTC",
    )

    /** IANA ids, "/mozilla.org/20050126_1/America/New_York", or Windows names. null if unknown (→ device zone). */
    fun zone(tzid: String): ZoneId? {
        val id = tzid.trim().trim('"')
        runCatching { return ZoneId.of(id) }
        WINDOWS_ZONES[id.lowercase()]?.let { return ZoneId.of(it) }
        val parts = id.split('/').filter { it.isNotEmpty() }
        for (n in 3 downTo 2) if (parts.size >= n) runCatching { return ZoneId.of(parts.takeLast(n).joinToString("/")) }
        return null
    }

    /** P1D, PT1H30M, P1W, -PT15M. */
    internal fun duration(s: String): Duration? {
        val v = s.trim()
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$").find(v) ?: return null
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0L
        val d = Duration.ofDays(n(2) * 7 + n(3)).plusHours(n(4)).plusMinutes(n(5)).plusSeconds(n(6))
        return if (g[1] == "-") d.negated() else d
    }

    private val DAYS = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)

    internal fun rule(s: String, start: Time): Rule? {
        val kv = s.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        val freq = kv["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        val untilZone = if (start.zone == null && start.time != null) null else start.zone
        return Rule(
            freq = freq,
            interval = kv["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            count = kv["COUNT"]?.toIntOrNull(),
            until = kv["UNTIL"]?.let { u -> time(Prop("UNTIL", emptyMap(), u))?.let { t -> if (t.zone == null && t.time != null) t.copy(zone = untilZone) else t } },
            byDay = kv["BYDAY"]?.split(',')?.mapNotNull { d ->
                val code = d.takeLast(2).uppercase()
                DAYS[code]?.let { (d.dropLast(2).toIntOrNull() ?: 0) to it }
            }.orEmpty(),
            byMonthDay = kv["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            byMonth = kv["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            weekStart = kv["WKST"]?.let { DAYS[it.uppercase()] } ?: DayOfWeek.MONDAY,
        )
    }

    // ---- expansion ----

    private fun endOf(e: Event, start: Time, zone: ZoneId): Instant {
        val s = start.instant(zone)
        val baseStart = e.start
        return when {
            e.end != null && start == baseStart -> e.end.instant(zone).let { if (it < s) s else it }
            e.end != null -> s.plus(Duration.between(baseStart.instant(zone), e.end.instant(zone)).let { if (it.isNegative) Duration.ZERO else it })
            e.duration != null -> s.plus(e.duration)
            start.allDay -> start.date.plusDays(1).atStartOfDay(start.zone ?: zone).toInstant()
            else -> s
        }
    }

    /** All occurrences overlapping [from, to), sorted by start. */
    fun occurrences(cal: Calendar, from: Instant, to: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> {
        val overrides = cal.events.filter { it.recurrenceId != null }
            .groupBy { it.uid }.mapValues { (_, l) -> l.map { it.recurrenceId!!.instant(zone) }.toSet() }
        val out = ArrayList<Occurrence>()
        for (e in cal.events) {
            if (e.cancelled) continue
            val starts: List<Time> = if (e.recurrenceId != null || (e.rule == null && e.rDates.isEmpty())) listOf(e.start)
                else expand(e, from, to, zone)
            val skip = overrides[e.uid].takeIf { e.recurrenceId == null }.orEmpty()
            val ex = e.exDates.map { it.instant(zone) }.toSet()
            val exDays = e.exDates.filter { it.allDay }.map { it.date }.toSet()
            for (st in starts) {
                val s = st.instant(zone)
                if (e.recurrenceId == null && (s in skip || s in ex || (st.allDay && st.date in exDays))) continue
                val en = endOf(e, st, zone)
                val overlaps = if (en > s) en > from && s < to else s >= from && s < to
                if (overlaps) out += Occurrence(e, s, en, st.allDay)
            }
        }
        return out.sortedWith(compareBy({ it.start }, { it.event.summary }))
    }

    /** Occurrence starts of a recurring event that could overlap [from, to). */
    fun expand(e: Event, from: Instant, to: Instant, zone: ZoneId): List<Time> {
        val r = e.rule
        val s0 = e.start
        val evZone = s0.zone ?: zone
        val span = Duration.between(s0.instant(zone), endOf(e, s0, zone))
        val out = ArrayList<Time>()
        for (d in e.rDates) out += d
        if (r == null) return out.distinct()
        val untilI = r.until?.let { u -> if (u.allDay) u.date.plusDays(1).atStartOfDay(evZone).toInstant() else u.instant(zone).plusMillis(1) }
        val lookFrom = from.minus(span).minus(1, ChronoUnit.DAYS)
        val startDate = s0.date
        var period = 0L
        if (r.count == null) {
            // Jump close to the window: whole periods before it can't matter without COUNT.
            val target = LocalDateTime.ofInstant(lookFrom, evZone).toLocalDate()
            val unit = when (r.freq) { "DAILY" -> ChronoUnit.DAYS; "WEEKLY" -> ChronoUnit.WEEKS; "MONTHLY" -> ChronoUnit.MONTHS; else -> ChronoUnit.YEARS }
            val between = unit.between(periodStart(r, startDate), target)
            if (between > 1) period = (between / r.interval - 1).coerceAtLeast(0)
        }
        var count = 0
        var guard = 0
        while (guard++ < 5000) {
            val pStart = advance(r, periodStart(r, startDate), period * r.interval)
            for (date in candidates(r, pStart, startDate)) {
                if (date < startDate) continue
                val t = Time(date, s0.time, s0.zone)
                val inst = t.instant(zone)
                if (untilI != null && inst >= untilI) return out.distinct()
                if (inst >= to) return out.distinct()
                count++
                if (r.count != null && count > r.count) return out.distinct()
                if (inst >= lookFrom) out += t
            }
            period++
        }
        return out.distinct()
    }

    private fun periodStart(r: Rule, d: LocalDate): LocalDate = when (r.freq) {
        "WEEKLY" -> d.with(TemporalAdjusters.previousOrSame(r.weekStart))
        "MONTHLY" -> d.withDayOfMonth(1)
        "YEARLY" -> d.withDayOfYear(1)
        else -> d
    }

    private fun advance(r: Rule, d: LocalDate, n: Long): LocalDate = when (r.freq) {
        "DAILY" -> d.plusDays(n)
        "WEEKLY" -> d.plusWeeks(n)
        "MONTHLY" -> d.plusMonths(n)
        else -> d.plusYears(n)
    }

    /** Candidate dates within one period, sorted. */
    private fun candidates(r: Rule, p: LocalDate, s0: LocalDate): List<LocalDate> {
        val list: List<LocalDate> = when (r.freq) {
            "DAILY" -> listOf(p).filter { d ->
                (r.byDay.isEmpty() || r.byDay.any { it.second == d.dayOfWeek }) &&
                    (r.byMonth.isEmpty() || d.monthValue in r.byMonth) &&
                    (r.byMonthDay.isEmpty() || monthDayMatches(d, r.byMonthDay))
            }
            "WEEKLY" -> {
                val days = r.byDay.map { it.second }.ifEmpty { listOf(s0.dayOfWeek) }
                (0..6).map { p.plusDays(it.toLong()) }.filter { it.dayOfWeek in days && (r.byMonth.isEmpty() || it.monthValue in r.byMonth) }
            }
            "MONTHLY" -> if (r.byMonth.isNotEmpty() && p.monthValue !in r.byMonth) emptyList() else inMonth(r, p, s0)
            else -> {
                val months = r.byMonth.ifEmpty { listOf(s0.monthValue) }
                months.flatMap { m ->
                    val mp = p.withMonth(m)
                    if (r.byDay.isEmpty() && r.byMonthDay.isEmpty()) listOfNotNull(dayOrNull(mp, s0.dayOfMonth)) else inMonth(r, mp, s0)
                }
            }
        }
        return list.distinct().sorted()
    }

    private fun inMonth(r: Rule, m: LocalDate, s0: LocalDate): List<LocalDate> {
        val len = m.lengthOfMonth()
        val all = (1..len).map { m.withDayOfMonth(it) }
        return when {
            r.byMonthDay.isNotEmpty() -> all.filter { monthDayMatches(it, r.byMonthDay) && (r.byDay.isEmpty() || r.byDay.any { b -> b.second == it.dayOfWeek }) }
            r.byDay.isNotEmpty() -> r.byDay.flatMap { (ord, dow) ->
                val matches = all.filter { it.dayOfWeek == dow }
                when {
                    ord == 0 -> matches
                    ord > 0 -> listOfNotNull(matches.getOrNull(ord - 1))
                    else -> listOfNotNull(matches.getOrNull(matches.size + ord))
                }
            }
            else -> listOfNotNull(dayOrNull(m, s0.dayOfMonth))
        }
    }

    private fun monthDayMatches(d: LocalDate, days: List<Int>): Boolean {
        val len = d.lengthOfMonth()
        return days.any { md -> if (md > 0) d.dayOfMonth == md else d.dayOfMonth == len + md + 1 }
    }

    /** Day [n] of [m]'s month, or null when the month is too short (RFC 5545: skipped, not clamped). */
    private fun dayOrNull(m: LocalDate, n: Int): LocalDate? = if (n <= m.lengthOfMonth()) m.withDayOfMonth(n) else null
}
