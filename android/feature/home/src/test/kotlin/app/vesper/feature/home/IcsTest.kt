package app.vesper.feature.home

import app.vesper.feature.home.glance.Ics
import java.io.BufferedReader
import java.io.StringReader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IcsTest {
    private val la = ZoneId.of("America/Los_Angeles")

    private fun cal(vararg events: String) = Ics.parse(BufferedReader(StringReader(
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "X-WR-CALNAME:Family") + events.toList() + "END:VCALENDAR").joinToString("\r\n"))))

    private fun ev(vararg lines: String) = (listOf("BEGIN:VEVENT") + lines + "END:VEVENT").joinToString("\r\n")

    private fun day(d: LocalDate) = d.atStartOfDay(la).toInstant() to d.plusDays(1).atStartOfDay(la).toInstant()

    private fun starts(c: Ics.Calendar, from: LocalDate, days: Long = 1) =
        Ics.occurrences(c, from.atStartOfDay(la).toInstant(), from.plusDays(days).atStartOfDay(la).toInstant(), la)
            .map { LocalDateTime.ofInstant(it.start, la) }

    @Test fun parsesTimesZonesFoldingAndEscapes() {
        val c = cal(
            ev("UID:1", "DTSTART;TZID=America/New_York:20261122T130000", "DTEND;TZID=America/New_York:20261122T140000",
                "SUMMARY:Family call\\, weekly", "LOCATION:Seabright Ave\\, Santa Cruz\\, CA", "DESCRIPTION:Line one\\nline two"),
            ev("UID:2", "DTSTART:20261122T180000Z", "DURATION:PT1H30M", "SUMMARY:Dinner with", " Maya"),
            ev("UID:3", "DTSTART;VALUE=DATE:20261122", "DTEND;VALUE=DATE:20261123", "SUMMARY:Holiday"),
            ev("UID:4", "DTSTART;TZID=\"Pacific Standard Time\":20261122T090000", "SUMMARY:Windows zone"),
        )
        assertEquals("Family", c.name)
        val (from, to) = day(LocalDate.of(2026, 11, 22))
        val o = Ics.occurrences(c, from, to, la)
        assertEquals(4, o.size)
        val call = o.first { it.event.uid == "1" }
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 0), LocalDateTime.ofInstant(call.start, la)) // 1 PM New York = 10 AM LA
        assertEquals("Family call, weekly", call.event.summary)
        assertEquals("Seabright Ave, Santa Cruz, CA", call.event.location)
        assertEquals("Line one\nline two", call.event.description)
        val dinner = o.first { it.event.uid == "2" }
        assertEquals("Dinner withMaya", dinner.event.summary) // folded line: the leading space is dropped
        assertEquals(90L, (dinner.end.epochSecond - dinner.start.epochSecond) / 60)
        val hol = o.first { it.event.uid == "3" }
        assertTrue(hol.allDay)
        assertEquals(LocalDate.of(2026, 11, 22).atStartOfDay(la).toInstant(), hol.start)
        assertEquals(LocalDateTime.of(2026, 11, 22, 9, 0), LocalDateTime.ofInstant(o.first { it.event.uid == "4" }.start, la))
    }

    @Test fun weeklyByDayWithCountAndExdate() {
        // Mon/Wed/Fri at 9:30 from Mon Nov 2 2026, 10 times, skipping Wed Nov 11.
        val c = cal(ev("UID:w", "DTSTART;TZID=America/Los_Angeles:20261102T093000", "DTEND;TZID=America/Los_Angeles:20261102T100000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;COUNT=10", "EXDATE;TZID=America/Los_Angeles:20261111T093000", "SUMMARY:Gym"))
        val all = starts(c, LocalDate.of(2026, 11, 1), 30).map { it.toLocalDate().dayOfMonth }
        assertEquals(listOf(2, 4, 6, 9, 13, 16, 18, 20, 23), all) // 10 occurrences minus the EXDATE; COUNT includes it
    }

    @Test fun dailyUntilAcrossDst() {
        val c = cal(ev("UID:d", "DTSTART;TZID=America/Los_Angeles:20261029T070000", "RRULE:FREQ=DAILY;UNTIL=20261103T150000Z", "SUMMARY:Walk"))
        val s = starts(c, LocalDate.of(2026, 10, 28), 10)
        assertEquals(6, s.size) // Oct 29 … Nov 3 (Nov 3 7:00 PST = 15:00Z, inclusive)
        assertTrue(s.all { it.hour == 7 }) // wall-clock time kept across the Nov 1 DST change
    }

    @Test fun monthlyNthWeekdayLastFridayAndMonthDay() {
        val c = cal(
            ev("UID:m1", "DTSTART;TZID=America/Los_Angeles:20260113T190000", "RRULE:FREQ=MONTHLY;BYDAY=2TU", "SUMMARY:Book club"),
            ev("UID:m2", "DTSTART;TZID=America/Los_Angeles:20260130T170000", "RRULE:FREQ=MONTHLY;BYDAY=-1FR", "SUMMARY:Payday drinks"),
            ev("UID:m3", "DTSTART;VALUE=DATE:20260131", "RRULE:FREQ=MONTHLY", "SUMMARY:Rent"),
        )
        val nov = starts(c, LocalDate.of(2026, 11, 1), 30).map { it.toLocalDate() }
        assertTrue(LocalDate.of(2026, 11, 10) in nov) // 2nd Tuesday
        assertTrue(LocalDate.of(2026, 11, 27) in nov) // last Friday
        assertTrue(nov.none { it.dayOfMonth == 31 }) // the 31st doesn't exist in November: skipped, not clamped
        val dec = starts(c, LocalDate.of(2026, 12, 31)).map { it.toLocalDate() }
        assertEquals(listOf(LocalDate.of(2026, 12, 31)), dec)
    }

    @Test fun recurrenceIdOverridesAndCancelled() {
        val c = cal(
            ev("UID:s", "DTSTART;TZID=America/Los_Angeles:20261116T093000", "RRULE:FREQ=WEEKLY", "SUMMARY:Team sync"),
            ev("UID:s", "RECURRENCE-ID;TZID=America/Los_Angeles:20261123T093000", "DTSTART;TZID=America/Los_Angeles:20261123T110000", "SUMMARY:Team sync (moved)"),
            ev("UID:x", "DTSTART;TZID=America/Los_Angeles:20261123T120000", "STATUS:CANCELLED", "SUMMARY:Lunch"),
        )
        val o = Ics.occurrences(c, LocalDate.of(2026, 11, 23).atStartOfDay(la).toInstant(), LocalDate.of(2026, 11, 24).atStartOfDay(la).toInstant(), la)
        assertEquals(listOf("Team sync (moved)"), o.map { it.event.summary })
        assertEquals(11, LocalDateTime.ofInstant(o[0].start, la).hour)
    }

    @Test fun skipsAheadForOldUnboundedRules() {
        val c = cal(ev("UID:old", "DTSTART:20100104T170000Z", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO", "SUMMARY:Biweekly"))
        // Jan 4 2010 is a Monday; every other Monday since.
        val s = starts(c, LocalDate.of(2026, 11, 1), 28).map { it.toLocalDate() }
        val first = LocalDate.of(2010, 1, 4)
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { java.time.temporal.ChronoUnit.DAYS.between(first, it) % 14 == 0L })
    }

    @Test fun windowFilterDropsOldOneOffs() {
        val text = (listOf("BEGIN:VCALENDAR") + ev("UID:a", "DTSTART:20200101T100000Z", "SUMMARY:Old") +
            ev("UID:b", "DTSTART:20261122T100000Z", "SUMMARY:Today") + "END:VCALENDAR").joinToString("\n")
        val c = Ics.parse(BufferedReader(StringReader(text)), LocalDate.of(2026, 11, 22).atStartOfDay(ZoneOffset.UTC).toInstant(),
            LocalDate.of(2026, 11, 24).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC)
        assertEquals(listOf("Today"), c.events.map { it.summary })
    }

    @Test fun durationsAndZones() {
        assertEquals(java.time.Duration.ofDays(8), Ics.duration("P1W1D"))
        assertEquals(java.time.Duration.ofMinutes(-15), Ics.duration("-PT15M"))
        assertEquals(ZoneId.of("America/New_York"), Ics.zone("/mozilla.org/20050126_1/America/New_York"))
        assertEquals(ZoneId.of("Europe/Berlin"), Ics.zone("W. Europe Standard Time"))
    }
}
