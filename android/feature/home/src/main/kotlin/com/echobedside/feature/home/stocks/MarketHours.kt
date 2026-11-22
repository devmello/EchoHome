package com.echobedside.feature.home.stocks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * US equity market (NYSE / Nasdaq) regular hours: 9:30–16:00 America/New_York, Mon–Fri, minus
 * NYSE holidays; 13:00 early closes. Holidays are computed by rule, so this doesn't expire.
 */
object MarketHours {
    val ZONE: ZoneId = ZoneId.of("America/New_York")
    val OPEN: LocalTime = LocalTime.of(9, 30)
    val CLOSE: LocalTime = LocalTime.of(16, 0)
    val EARLY_CLOSE: LocalTime = LocalTime.of(13, 0)

    enum class Phase { PRE_OPEN, OPEN, AFTER_CLOSE, CLOSED_DAY }

    fun phase(now: Long): Phase {
        val t = Instant.ofEpochMilli(now).atZone(ZONE)
        val d = t.toLocalDate()
        if (!isTradingDay(d)) return Phase.CLOSED_DAY
        val time = t.toLocalTime()
        return when {
            time < OPEN -> Phase.PRE_OPEN
            time < closeTime(d) -> Phase.OPEN
            else -> Phase.AFTER_CLOSE
        }
    }

    fun isOpen(now: Long) = phase(now) == Phase.OPEN

    fun closeTime(d: LocalDate): LocalTime = if (isEarlyClose(d)) EARLY_CLOSE else CLOSE

    fun isTradingDay(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in holidays(d.year)

    /** The session whose prices are on screen: today once it opened, else the previous trading day. */
    fun lastSession(now: Long): LocalDate {
        val t = Instant.ofEpochMilli(now).atZone(ZONE)
        var d = t.toLocalDate()
        if (isTradingDay(d) && t.toLocalTime() >= OPEN) return d
        do { d = d.minusDays(1) } while (!isTradingDay(d))
        return d
    }

    /** Next open, for the refresh scheduler. */
    fun nextOpen(now: Long): Long {
        val t = Instant.ofEpochMilli(now).atZone(ZONE)
        var d = t.toLocalDate()
        if (!(isTradingDay(d) && t.toLocalTime() < OPEN)) do { d = d.plusDays(1) } while (!isTradingDay(d))
        return d.atTime(OPEN).atZone(ZONE).toInstant().toEpochMilli()
    }

    /** "MARKETS · DELAYED 15 MIN" / "MARKETS CLOSED · FRIDAY CLOSE" (Stocks.dc.html). */
    fun headline(now: Long, openText: String): String = when (phase(now)) {
        Phase.OPEN -> openText
        Phase.AFTER_CLOSE -> "MARKETS CLOSED · TODAY’S CLOSE"
        else -> "MARKETS CLOSED · " + lastSession(now).dayOfWeek.name + " CLOSE"
    }

    fun holidays(year: Int): Set<LocalDate> {
        val s = HashSet<LocalDate>()
        // New Year's Day: Sunday → Monday; Saturday → not observed (NYSE rule 7.2).
        LocalDate.of(year, 1, 1).let { if (it.dayOfWeek == DayOfWeek.SUNDAY) s += it.plusDays(1) else if (it.dayOfWeek != DayOfWeek.SATURDAY) s += it }
        s += nth(year, 1, DayOfWeek.MONDAY, 3)        // Martin Luther King Jr. Day
        s += nth(year, 2, DayOfWeek.MONDAY, 3)        // Washington's Birthday
        s += easter(year).minusDays(2)                // Good Friday
        s += LocalDate.of(year, 5, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)) // Memorial Day
        if (year >= 2022) s += observed(LocalDate.of(year, 6, 19)) // Juneteenth
        s += observed(LocalDate.of(year, 7, 4))       // Independence Day
        s += nth(year, 9, DayOfWeek.MONDAY, 1)        // Labor Day
        s += nth(year, 11, DayOfWeek.THURSDAY, 4)     // Thanksgiving
        s += observed(LocalDate.of(year, 12, 25))     // Christmas
        return s
    }

    /** 1 pm closes: the day after Thanksgiving, Christmas Eve and July 3 when they're trading days. */
    fun isEarlyClose(d: LocalDate): Boolean {
        val candidates = listOf(nth(d.year, 11, DayOfWeek.THURSDAY, 4).plusDays(1), LocalDate.of(d.year, 12, 24), LocalDate.of(d.year, 7, 3))
        return d in candidates && d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d !in holidays(d.year)
    }

    private fun observed(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.SATURDAY -> d.minusDays(1)
        DayOfWeek.SUNDAY -> d.plusDays(1)
        else -> d
    }

    private fun nth(year: Int, month: Int, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    /** Anonymous Gregorian algorithm. */
    fun easter(year: Int): LocalDate {
        val a = year % 19; val b = year / 100; val c = year % 100
        val d = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30; val i = c / 4; val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7; val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31; val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}
