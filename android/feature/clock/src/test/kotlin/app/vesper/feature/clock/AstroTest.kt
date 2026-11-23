package app.vesper.feature.clock

import app.vesper.core.model.AirQuality
import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.NextAlarm
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
    ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()

class MoonMathTest {
    @Test fun fullMoons() {
        // 2025-12-04 23:14 UTC, 2026-01-03 10:03 UTC, 2026-02-01 22:09 UTC
        for (t in listOf(utc(2025, 12, 4, 23, 14), utc(2026, 1, 3, 10, 3), utc(2026, 2, 1, 22, 9))) {
            val p = MoonMath.phase(t)
            assertTrue("full moon ${p.illumination}", p.illumination > 0.995)
            assertEquals("Full moon", p.name)
            // 12 h either side it's still ≥ 98 %
            assertTrue(MoonMath.phase(t - 12 * 3_600_000L).illumination > 0.98)
            assertTrue(MoonMath.phase(t + 12 * 3_600_000L).illumination > 0.98)
        }
    }

    @Test fun newMoons() {
        // 2026-01-18 19:52 UTC, 2026-02-17 12:01 UTC
        for (t in listOf(utc(2026, 1, 18, 19, 52), utc(2026, 2, 17, 12, 1))) {
            val p = MoonMath.phase(t)
            assertTrue("new moon ${p.illumination}", p.illumination < 0.005)
            assertEquals("New moon", p.name)
        }
    }

    @Test fun quartersAndDirection() {
        val fq = MoonMath.phase(utc(2026, 1, 26, 4, 47)) // first quarter
        assertEquals(0.5, fq.illumination, 0.03)
        assertTrue(fq.waxing)
        assertEquals("First quarter", fq.name)
        val lq = MoonMath.phase(utc(2026, 1, 10, 15, 48)) // last quarter
        assertEquals(0.5, lq.illumination, 0.03)
        assertFalse(lq.waxing)
        assertEquals("Last quarter", lq.name)
    }

    @Test fun namesAcrossACycle() {
        val start = utc(2026, 1, 18, 19, 52) // new
        assertEquals("Waxing crescent", MoonMath.phase(start + 4 * 86_400_000L).name)
        assertEquals("Waxing gibbous", MoonMath.phase(start + 10 * 86_400_000L).name)
        assertEquals("Waning gibbous", MoonMath.phase(start + 18 * 86_400_000L).name)
        assertEquals("Waning crescent", MoonMath.phase(start + 25 * 86_400_000L).name)
        // three days after new ≈ 8 %
        val p = MoonMath.phase(start + 3 * 86_400_000L)
        assertTrue("${p.illumination}", p.illumination in 0.05..0.14)
    }
}

class SunTimesTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")
    private fun minutes(millis: Long): Int {
        val z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), la.toZoneId())
        return z.hour * 60 + z.minute
    }
    private fun noon(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 12, 0, 0, 0, la.toZoneId()).toInstant().toEpochMilli()

    @Test fun sanFranciscoWinterSolstice() {
        // 2025-12-21: sunrise ~7:19, sunset ~4:52 PM (PST)
        val (rise, set) = SunMath.sunriseSunset(noon(2025, 12, 21), 37.7749, -122.4194, la)!!
        assertEquals((7 * 60 + 19).toDouble(), minutes(rise).toDouble(), 5.0)
        assertEquals((16 * 60 + 52).toDouble(), minutes(set).toDouble(), 5.0)
    }

    @Test fun sanFranciscoSummerSolstice() {
        // 2026-06-21: sunrise ~5:47, sunset ~8:33 PM (PDT)
        val (rise, set) = SunMath.sunriseSunset(noon(2026, 6, 21), 37.7749, -122.4194, la)!!
        assertEquals((5 * 60 + 47).toDouble(), minutes(rise).toDouble(), 5.0)
        assertEquals((20 * 60 + 33).toDouble(), minutes(set).toDouble(), 5.0)
    }

    @Test fun showsTodayUntilSunsetThenTomorrow() {
        val lat = 37.7749; val lon = -122.4194
        val morning = ZonedDateTime.of(2026, 3, 10, 3, 0, 0, 0, la.toZoneId()).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 3, 10, 22, 0, 0, 0, la.toZoneId()).toInstant().toEpochMilli()
        val a = SunTimes.next(morning, lat, lon, la, null)!!
        val b = SunTimes.next(evening, lat, lon, la, null)!!
        assertTrue(a.first > morning && a.first < morning + 6 * 3_600_000L) // today's sunrise ahead
        assertTrue(b.first > evening)                                        // tomorrow's sunrise ahead
        assertEquals(24 * 60.0, (b.first - a.first) / 60_000.0, 4.0)         // a day apart, within a few minutes
    }

    @Test fun weatherReportedTimesWinBeforeSunset() {
        val now = utc(2026, 3, 10, 3, 0)
        val reported = (now + 3_600_000L) to (now + 10 * 3_600_000L)
        assertEquals(reported, SunTimes.next(now, 37.0, -122.0, la, reported))
    }

    @Test fun polarNightHasNoTimes() {
        assertNull(SunMath.sunriseSunset(utc(2026, 12, 21, 12, 0), 78.0, 15.0, TimeZone.getTimeZone("UTC")))
    }
}

class PersonalLineTest {
    private val zone = TimeZone.getTimeZone("America/Los_Angeles")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 3, d, h, m, 0, 0, zone.toZoneId()).toInstant().toEpochMilli()
    private val now = at(10, 22, 0) // Tuesday 2026-03-10

    @Test fun nextTriggerOneShotAndWeekdays() {
        assertEquals(at(11, 6, 15), Personal.nextTrigger(Alarm("a", 6, 15), now, zone))
        assertEquals(at(10, 23, 0), Personal.nextTrigger(Alarm("a", 23, 0), now, zone))
        val weekdays = Alarm("w", 7, 0, days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        assertEquals(at(9, 7, 0), Personal.nextTrigger(weekdays, at(7, 20, 0), zone)) // Saturday evening → Monday
        assertEquals(at(11, 7, 0), Personal.nextTrigger(weekdays, now, zone))
        assertNull(Personal.nextTrigger(Alarm("off", 7, 0, enabled = false), now, zone))
    }

    @Test fun alarmPrefersThePersonsOwnThenShared() {
        val mine = Alarm("m", 6, 15, profileId = "maya")
        val sams = Alarm("s", 5, 0, profileId = "sam")
        val shared = Alarm("sh", 7, 0)
        assertEquals(at(11, 6, 15), Personal.alarmFor("maya", listOf(shared, sams, mine), null, now, zone))
        assertEquals(at(11, 7, 0), Personal.alarmFor("maya", listOf(shared, sams), null, now, zone))
        assertNull(Personal.alarmFor("maya", listOf(sams), null, now, zone))
        // the store's own next (leave-by may move it) wins when it's one of the candidates
        val early = NextAlarm(mine, at(11, 5, 40), "Leave by")
        assertEquals(at(11, 5, 40), Personal.alarmFor("maya", listOf(shared, mine), early, now, zone))
        // but not when it's someone else's
        val other = NextAlarm(sams, at(11, 5, 0))
        assertEquals(at(11, 6, 15), Personal.alarmFor("maya", listOf(sams, mine), other, now, zone))
    }

    private fun ev(id: String, start: Int, who: String?, allDay: Boolean = false) =
        CalendarEvent(id, id, "", at(11, start, 0), at(11, start + 1, 0), allDay = allDay, color = 0, profileId = who)

    @Test fun eventPrefersTheirCalendarThenShared() {
        val events = listOf(ev("shared-8", 8, null), ev("maya-9", 9, "maya"), ev("sam-7", 7, "sam"), ev("holiday", 0, null, allDay = true))
        assertEquals("maya-9", Personal.eventFor("maya", events, now)?.id)
        assertEquals("sam-7", Personal.eventFor("sam", events, now)?.id)
        assertEquals("shared-8", Personal.eventFor("kai", events, now)?.id) // no calendar of their own → shared
        assertNull(Personal.eventFor("maya", listOf(ev("old", 8, "maya")), at(11, 12, 0)))
        assertNotNull(Personal.eventFor("maya", listOf(ev("now", 8, "maya")), at(11, 8, 30))) // in progress
    }

    @Test fun tomorrowDetectionAndAirLine() {
        assertTrue(Personal.isTomorrowOrLater(at(11, 6, 0), now, zone))
        assertFalse(Personal.isTomorrowOrLater(at(10, 23, 0), now, zone))
        assertEquals("AQI 42 · UV 3", formatAir(AirQuality(usAqi = 42, uvIndex = 3.2f)))
        assertEquals("AQI 42", formatAir(AirQuality(usAqi = 42, uvIndex = 0f)))
        assertEquals("", formatAir(AirQuality()))
        assertEquals("", formatAir(null))
    }
}
