package app.vesper.feature.alarm.engine

import app.vesper.core.model.CalendarEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Days an alarm with `skipHolidays` stays quiet: US federal holidays (computed locally, with the
 * observed-date rule: a Saturday holiday is observed on the Friday, a Sunday one on the Monday) and
 * days covered by an all-day calendar event titled holiday / vacation / day off / PTO / OOO.
 */
object Holidays {
    /** The holiday observed on [date] ("Thanksgiving"), or null. A shifted one reads "Independence Day (observed)". */
    fun federalName(date: LocalDate): String? {
        // The observed date of a fixed-date holiday can land in the neighbouring year (Jan 1 on a Saturday → Dec 31).
        for (year in intArrayOf(date.year, date.year + 1)) {
            for ((name, actual) in fixedDates(year)) {
                val observed = observed(actual)
                if (observed == date) return if (observed == actual) name else "$name (observed)"
            }
        }
        for ((name, d) in floatingDates(date.year)) if (d == date) return name
        return null
    }

    private fun fixedDates(year: Int): List<Pair<String, LocalDate>> = listOf(
        "New Year's Day" to LocalDate.of(year, Month.JANUARY, 1),
        "Juneteenth" to LocalDate.of(year, Month.JUNE, 19),
        "Independence Day" to LocalDate.of(year, Month.JULY, 4),
        "Veterans Day" to LocalDate.of(year, Month.NOVEMBER, 11),
        "Christmas" to LocalDate.of(year, Month.DECEMBER, 25),
    )

    private fun observed(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    private fun floatingDates(year: Int): List<Pair<String, LocalDate>> {
        fun nth(m: Month, dow: DayOfWeek, n: Int) = LocalDate.of(year, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
        return listOf(
            "Martin Luther King Jr. Day" to nth(Month.JANUARY, DayOfWeek.MONDAY, 3),
            "Presidents Day" to nth(Month.FEBRUARY, DayOfWeek.MONDAY, 3),
            "Memorial Day" to LocalDate.of(year, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
            "Labor Day" to nth(Month.SEPTEMBER, DayOfWeek.MONDAY, 1),
            "Columbus Day" to nth(Month.OCTOBER, DayOfWeek.MONDAY, 2),
            "Thanksgiving" to nth(Month.NOVEMBER, DayOfWeek.THURSDAY, 4),
        )
    }

    private val OFF_TITLE = Regex("""(?i)\b(holidays?|vacation|day off|pto|ooo)\b""")

    /** True for an all-day event that means "I'm not working": holiday, vacation, day off, PTO, OOO. */
    fun isDayOffEvent(e: CalendarEvent) = e.allDay && OFF_TITLE.containsMatchIn(e.title)

    /**
     * The local dates covered by day-off all-day events, each with the event's title (the first
     * one wins). Multi-day events cover every day; an all-day event's end is exclusive.
     */
    fun dayOffDays(events: List<CalendarEvent>, zone: ZoneId): Map<LocalDate, String> {
        val out = LinkedHashMap<LocalDate, String>()
        for (e in events) {
            if (!isDayOffEvent(e)) continue
            var d = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(e.endMillis - 1, e.startMillis)).atZone(zone).toLocalDate()
            var n = 0
            while (!d.isAfter(last) && n++ < 62) { out.putIfAbsent(d, e.title.trim()); d = d.plusDays(1) }
        }
        return out
    }
}
