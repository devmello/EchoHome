package com.echobedside.feature.alarm.engine

import com.echobedside.core.model.Alarm
import com.echobedside.core.model.AlarmPhase
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmEngineTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val MIN = 60_000L

    private fun at(s: String, zone: ZoneId = la) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long, zone: ZoneId = la) = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDateTime().toString()

    // --- next-trigger math ---

    @Test fun oneShotLaterToday() {
        assertEquals("2026-11-22T07:00", local(nextTriggerAfter(Alarm("a", 7, 0), at("2026-11-22T06:00"), la)))
    }

    @Test fun oneShotAlreadyPassedIsTomorrow() {
        assertEquals("2026-11-23T07:00", local(nextTriggerAfter(Alarm("a", 7, 0), at("2026-11-22T07:00"), la)))
        assertEquals("2026-11-23T07:00", local(nextTriggerAfter(Alarm("a", 7, 0), at("2026-11-22T23:59"), la)))
    }

    @Test fun weekdaysSkipTheWeekend() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val a = Alarm("w", 6, 30, weekdays)
        // Fri Nov 20, 2026 after 6:30 → Mon Nov 23
        assertEquals("2026-11-23T06:30", local(nextTriggerAfter(a, at("2026-11-20T07:00"), la)))
        // Sun Nov 22 → Mon
        assertEquals("2026-11-23T06:30", local(nextTriggerAfter(a, at("2026-11-22T12:00"), la)))
        // Mon before → same day
        assertEquals("2026-11-23T06:30", local(nextTriggerAfter(a, at("2026-11-23T06:29"), la)))
    }

    @Test fun singleWeekdayAWeekAway() {
        val a = Alarm("s", 9, 0, setOf(DayOfWeek.SUNDAY))
        assertEquals("2026-11-29T09:00", local(nextTriggerAfter(a, at("2026-11-22T09:00"), la)))
    }

    @Test fun dstSpringForwardGapShiftsLater() {
        // Mar 8, 2026: 2:00 → 3:00 in Los Angeles. 2:30 doesn't exist; it rings at 3:30 PDT.
        val t = nextTriggerAfter(Alarm("a", 2, 30), at("2026-03-08T01:00"), la)
        assertEquals("2026-03-08T03:30", local(t))
        assertEquals(at("2026-03-08T01:00") + 90 * MIN, t) // one hour of real time later... plus the 30 min
    }

    @Test fun dstSpringForwardNormalAlarmIsWallClock() {
        // 7:00 the morning clocks go forward: 7:00 PDT, i.e. 8 h of real time after 23:00 PST, not 9.
        val from = at("2026-03-07T23:00")
        val t = nextTriggerAfter(Alarm("a", 7, 0), from, la)
        assertEquals("2026-03-08T07:00", local(t))
        assertEquals(7 * 60 * MIN, t - from)
    }

    @Test fun dstFallBackOverlapRingsOnce() {
        // Nov 1, 2026: 2:00 PDT → 1:00 PST, so 1:30 happens twice. It rings at the first only.
        val a = Alarm("a", 1, 30)
        val first = nextTriggerAfter(a, at("2026-11-01T00:00"), la)
        assertEquals("2026-11-01T01:30", local(first))
        val second = nextTriggerAfter(a, first, la)
        assertEquals("2026-11-02T01:30", local(second))
        // 25 h day: next day's 1:30 is 25 h later
        assertEquals(25 * 60 * MIN, second - first)
    }

    @Test fun timezoneChangeIsLocalWallTime() {
        val ny = ZoneId.of("America/New_York")
        val from = at("2026-11-22T05:00", la)
        assertEquals("2026-11-22T07:00", local(nextTriggerAfter(Alarm("a", 7, 0), from, la), la))
        // Same instant, the device moved to New York (it's 8:00 there): tomorrow 7:00 NY
        assertEquals("2026-11-23T07:00", local(nextTriggerAfter(Alarm("a", 7, 0), from, ny), ny))
    }

    // --- phases ---

    private fun engine() = AlarmEngine(AlarmData(), { la })

    @Test fun nextAlarmAndWakeUps() {
        val e = engine()
        val now = at("2026-11-22T05:00")
        e.upsert(Alarm("a", 7, 0, sunrise = true, sunriseMinutes = 30), now)
        val ev = e.evaluate(now)
        assertEquals(AlarmPhase.Idle, ev.phase)
        assertEquals(at("2026-11-22T07:00"), ev.next!!.triggerAtMillis)
        assertEquals(at("2026-11-22T06:30"), ev.wakeAtMillis)
        assertEquals(at("2026-11-22T07:00"), ev.alarmClockAtMillis)
    }

    @Test fun sunriseRampThenRing() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0, sunriseMinutes = 30), at("2026-11-22T05:00"))
        val s = e.evaluate(at("2026-11-22T06:45")).phase
        assertTrue(s is AlarmPhase.Sunrise)
        assertEquals(0.5f, (s as AlarmPhase.Sunrise).progress, 0.001f)
        assertEquals(at("2026-11-22T07:00"), e.evaluate(at("2026-11-22T06:45")).wakeAtMillis)
        val r = e.evaluate(at("2026-11-22T07:00")).phase
        assertTrue(r is AlarmPhase.Ringing)
        // One-shot: switched off once it rang, so nothing is next.
        assertFalse(e.data.alarms.single().enabled)
        assertNull(e.evaluate(at("2026-11-22T07:00:01")).next?.takeIf { it.alarm.id != "a" })
    }

    @Test fun noSunriseWhenDisabled() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0, sunrise = false), at("2026-11-22T05:00"))
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T06:45")).phase)
        assertEquals(at("2026-11-22T07:00"), e.evaluate(at("2026-11-22T06:45")).wakeAtMillis)
    }

    @Test fun snoozeNineMinutesThenRingAgain() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00"))
        assertTrue(e.snooze(at("2026-11-22T07:01")))
        val sn = e.evaluate(at("2026-11-22T07:05"))
        assertTrue(sn.phase is AlarmPhase.Snoozed)
        assertEquals(at("2026-11-22T07:10"), (sn.phase as AlarmPhase.Snoozed).untilMillis)
        assertEquals(at("2026-11-22T07:10"), sn.next!!.triggerAtMillis)
        assertEquals(at("2026-11-22T07:10"), sn.alarmClockAtMillis)
        assertEquals(at("2026-11-22T07:10"), sn.wakeAtMillis)
        val again = e.evaluate(at("2026-11-22T07:10")).phase
        assertTrue(again is AlarmPhase.Ringing)
        assertEquals(at("2026-11-22T07:10"), (again as AlarmPhase.Ringing).sinceMillis)
    }

    @Test fun stopGoesToGoodMorningThenIdle() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00"))
        assertTrue(e.stop(at("2026-11-22T07:00:30")))
        assertTrue(e.evaluate(at("2026-11-22T07:01")).phase is AlarmPhase.GoodMorning)
        // Ring again from Good morning = snooze
        assertTrue(e.snooze(at("2026-11-22T07:02")))
        assertTrue(e.evaluate(at("2026-11-22T07:03")).phase is AlarmPhase.Snoozed)
        assertTrue(e.stop(at("2026-11-22T07:04")))
        assertTrue(e.dismissGoodMorning())
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:05")).phase)
        // Doesn't ring again for the same occurrence.
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:09")).phase)
    }

    @Test fun goodMorningTimesOut() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00"))
        e.stop(at("2026-11-22T07:01"))
        assertEquals(at("2026-11-22T08:01"), e.evaluate(at("2026-11-22T07:02")).wakeAtMillis)
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T08:01")).phase)
    }

    @Test fun autoSnoozeWhenNobodyAnswersThenGiveUp() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        var t = at("2026-11-22T07:00")
        e.evaluate(t)
        repeat(AlarmEngine.MAX_AUTO_SNOOZES) {
            assertEquals(t + AlarmEngine.AUTO_SNOOZE_MS, e.evaluate(t + 1000).wakeAtMillis)
            t += AlarmEngine.AUTO_SNOOZE_MS
            assertTrue(e.evaluate(t).phase is AlarmPhase.Snoozed)
            t += 9 * MIN
            assertTrue(e.evaluate(t).phase is AlarmPhase.Ringing)
        }
        t += AlarmEngine.AUTO_SNOOZE_MS
        assertEquals(AlarmPhase.Idle, e.evaluate(t).phase)
    }

    @Test fun stopDuringSunriseSkipsThatMorning() {
        val e = engine()
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        e.upsert(Alarm("a", 7, 0, weekdays), at("2026-11-22T05:00"))
        assertTrue(e.evaluate(at("2026-11-23T06:50")).phase is AlarmPhase.Sunrise)
        assertTrue(e.stop(at("2026-11-23T06:50")))
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-23T06:51")).phase)
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-23T07:00")).phase)
        assertEquals(at("2026-11-24T07:00"), e.evaluate(at("2026-11-23T07:01")).next!!.triggerAtMillis)
        assertTrue(e.data.alarms.single().enabled)
    }

    @Test fun repeatingAlarmStaysOnAndRingsNextDay() {
        val e = engine()
        val every = DayOfWeek.entries.toSet()
        e.upsert(Alarm("a", 7, 0, every), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00")); e.stop(at("2026-11-22T07:01")); e.dismissGoodMorning()
        val ev = e.evaluate(at("2026-11-22T07:02"))
        assertTrue(e.data.alarms.single().enabled)
        assertEquals(at("2026-11-23T07:00"), ev.next!!.triggerAtMillis)
        assertTrue(e.evaluate(at("2026-11-23T07:00")).phase is AlarmPhase.Ringing)
    }

    @Test fun settingAnAlarmForAMinuteAgoDoesNotRing() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T07:02"))
        val ev = e.evaluate(at("2026-11-22T07:02"))
        assertEquals(AlarmPhase.Idle, ev.phase)
        assertEquals(at("2026-11-23T07:00"), ev.next!!.triggerAtMillis)
    }

    @Test fun missedByAFewMinutesStillRings() {
        // e.g. the process was restarting (or the device rebooting) at 7:00.
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        assertTrue(e.evaluate(at("2026-11-22T07:04")).phase is AlarmPhase.Ringing)
    }

    @Test fun missedByLongIsSkipped() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0, DayOfWeek.entries.toSet()), at("2026-11-22T05:00"))
        val ev = e.evaluate(at("2026-11-22T07:30"))
        assertEquals(AlarmPhase.Idle, ev.phase)
        assertEquals(at("2026-11-23T07:00"), ev.next!!.triggerAtMillis)
    }

    @Test fun clockSetBackDoesNotRingTwice() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0, DayOfWeek.entries.toSet()), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00")); e.stop(at("2026-11-22T07:01")); e.dismissGoodMorning()
        // TIME_SET: the clock jumps back to 6:58, then passes 7:00 again.
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T06:58")).phase)
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:00")).phase)
    }

    @Test fun deletingTheRingingAlarmEndsIt() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-22T07:00"))
        e.delete("a")
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:00:10")).phase)
    }

    @Test fun disabledAlarmsAreIgnoredAndReEnablingDoesNotRingThePast() {
        val e = engine()
        e.upsert(Alarm("a", 7, 0, enabled = false), at("2026-11-22T05:00"))
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:00")).phase)
        assertNull(e.evaluate(at("2026-11-22T07:00")).next)
        e.setEnabled("a", true, at("2026-11-22T07:01"))
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-22T07:01")).phase)
        assertEquals(at("2026-11-23T07:00"), e.evaluate(at("2026-11-22T07:01")).next!!.triggerAtMillis)
    }

    @Test fun earliestOfSeveralAlarmsIsNext() {
        val e = engine()
        e.upsert(Alarm("late", 8, 0), at("2026-11-22T05:00"))
        e.upsert(Alarm("early", 6, 15, sunrise = false), at("2026-11-22T05:00"))
        assertEquals("early", e.evaluate(at("2026-11-22T05:00")).next!!.alarm.id)
    }

    @Test fun persistedStateRoundTrips() {
        val e = engine()
        e.upsert(Alarm("a", 7, 5, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), "Gym", sunriseMinutes = 20, snoozeMinutes = 5, profileId = "maya"), at("2026-11-22T05:00"))
        e.upsert(Alarm("b", 9, 0, label = "Weekend", enabled = false, sunrise = false), at("2026-11-22T05:00"))
        e.evaluate(at("2026-11-23T07:05"))
        e.snooze(at("2026-11-23T07:06"))
        e.setBrightnessBefore(0.25f)
        val json = Codec.alarmsToJson(e.data)
        val back = Codec.alarmsFromJson(json)
        assertEquals(e.data, back)
        val e2 = AlarmEngine(back, { la })
        val sn = e2.evaluate(at("2026-11-23T07:08")).phase
        assertTrue(sn is AlarmPhase.Snoozed)
        assertEquals(at("2026-11-23T07:11"), (sn as AlarmPhase.Snoozed).untilMillis)
    }

    @Test fun corruptFieldsAreTolerated() {
        val d = Codec.alarmsFromJson("""{"alarms":[{"id":"x","hour":6},{"hour":5}],"active":{"alarmId":"x","state":"BOGUS"}}""")
        assertEquals(1, d.alarms.size)
        assertEquals(6, d.alarms[0].hour)
        assertEquals(9, d.alarms[0].snoozeMinutes)
        assertNull(d.active)
        assertNotNull(d.handled)
    }
}
