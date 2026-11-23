package app.vesper.feature.alarm.engine

import app.vesper.core.model.Alarm
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.CalendarEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveHolidayTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val MIN = 60_000L
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun at(s: String) = LocalDateTime.parse(s).atZone(la).toInstant().toEpochMilli()
    private fun local(ms: Long) = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), la).toLocalDateTime().toString()
    private fun d(s: String) = LocalDate.parse(s)

    private fun timed(title: String, start: String, location: String? = "Somewhere", profile: String? = null, minutes: Long = 60) =
        CalendarEvent(title + start, title, "", at(start), at(start) + minutes * MIN, false, 0, location, profile)

    private fun allDay(title: String, from: String, days: Int = 1, profile: String? = null) =
        CalendarEvent(title + from, title, "", d(from).atStartOfDay(la).toInstant().toEpochMilli(),
            d(from).plusDays(days.toLong()).atStartOfDay(la).toInstant().toEpochMilli(), true, 0, null, profile)

    // --- US federal holidays ---

    @Test fun federalHolidays2026() {
        assertEquals("New Year's Day", Holidays.federalName(d("2026-01-01")))
        assertEquals("Martin Luther King Jr. Day", Holidays.federalName(d("2026-01-19")))
        assertEquals("Presidents Day", Holidays.federalName(d("2026-02-16")))
        assertEquals("Memorial Day", Holidays.federalName(d("2026-05-25")))
        assertEquals("Juneteenth", Holidays.federalName(d("2026-06-19")))
        assertEquals("Labor Day", Holidays.federalName(d("2026-09-07")))
        assertEquals("Columbus Day", Holidays.federalName(d("2026-10-12")))
        assertEquals("Veterans Day", Holidays.federalName(d("2026-11-11")))
        assertEquals("Thanksgiving", Holidays.federalName(d("2026-11-26")))
        assertEquals("Christmas", Holidays.federalName(d("2026-12-25")))
        assertNull(Holidays.federalName(d("2026-11-25")))
        assertNull(Holidays.federalName(d("2026-11-27"))) // the day after isn't federal
    }

    @Test fun saturdayHolidayIsObservedOnFriday() {
        // Jul 4, 2026 is a Saturday.
        assertEquals("Independence Day (observed)", Holidays.federalName(d("2026-07-03")))
        assertNull(Holidays.federalName(d("2026-07-04")))
    }

    @Test fun sundayHolidayIsObservedOnMonday() {
        // Jul 4, 2027 is a Sunday; Dec 25, 2022 was a Sunday.
        assertEquals("Independence Day (observed)", Holidays.federalName(d("2027-07-05")))
        assertNull(Holidays.federalName(d("2027-07-04")))
        assertEquals("Christmas (observed)", Holidays.federalName(d("2022-12-26")))
    }

    @Test fun newYearObservedInThePreviousYear() {
        // Jan 1, 2028 is a Saturday: observed Friday Dec 31, 2027.
        assertEquals("New Year's Day (observed)", Holidays.federalName(d("2027-12-31")))
        assertNull(Holidays.federalName(d("2028-01-01")))
        assertNull(Holidays.federalName(d("2028-12-31")))
    }

    @Test fun dayOffTitles() {
        fun hit(t: String) = Holidays.isDayOffEvent(allDay(t, "2026-11-23"))
        listOf("Vacation", "vacation in Maui", "Day off", "PTO", "pto - Sam", "OOO", "Maya OOO", "Company holiday", "Holidays").forEach { assertTrue(it, hit(it)) }
        listOf("Photo day", "Pottery", "Birthday", "Hotel", "Offsite").forEach { assertFalse(it, hit(it)) }
        // A timed "Holiday party" isn't a day off.
        assertFalse(Holidays.isDayOffEvent(timed("Holiday party", "2026-11-23T18:00")))
    }

    @Test fun multiDayEventCoversEveryDayAndEndIsExclusive() {
        val days = Holidays.dayOffDays(listOf(allDay("Vacation", "2026-11-23", days = 3)), la)
        assertEquals(setOf(d("2026-11-23"), d("2026-11-24"), d("2026-11-25")), days.keys)
    }

    // --- skipping in the occurrence maths ---

    private fun rules(dayOffs: Map<LocalDate, String> = emptyMap()) =
        OccurrenceRules(skipName = { Holidays.federalName(it) ?: dayOffs[it] })

    @Test fun thanksgivingIsSkipped() {
        val a = Alarm("w", 7, 0, weekdays, skipHolidays = true)
        val o = nextOccurrenceAfter(a, at("2026-11-25T07:01"), la, rules())
        assertEquals("2026-11-27T07:00", local(o.triggerMillis))
        assertEquals("Skipping Thanksgiving", o.reason)
    }

    @Test fun withoutTheToggleNothingIsSkipped() {
        val a = Alarm("w", 7, 0, weekdays)
        val o = nextOccurrenceAfter(a, at("2026-11-25T07:01"), la, rules())
        assertEquals("2026-11-26T07:00", local(o.triggerMillis))
        assertNull(o.reason)
    }

    @Test fun oneShotAlarmsAreNeverSkipped() {
        val a = Alarm("o", 7, 0, skipHolidays = true)
        val o = nextOccurrenceAfter(a, at("2026-11-25T08:00"), la, rules())
        assertEquals("2026-11-26T07:00", local(o.triggerMillis))
        assertNull(o.reason)
    }

    @Test fun noReasonWhenTheSkippedDayIsntBetween() {
        // Already past Thanksgiving's time: the next is Friday and nothing was skipped.
        val a = Alarm("w", 7, 0, weekdays, skipHolidays = true)
        val o = nextOccurrenceAfter(a, at("2026-11-26T09:00"), la, rules())
        assertEquals("2026-11-27T07:00", local(o.triggerMillis))
        assertNull(o.reason)
    }

    @Test fun allDayVacationSkipsEveryDayOfIt() {
        val off = Holidays.dayOffDays(listOf(allDay("Vacation", "2026-11-23", days = 3)), la)
        val a = Alarm("w", 7, 0, weekdays, skipHolidays = true)
        val o = nextOccurrenceAfter(a, at("2026-11-22T20:00"), la, rules(off))
        // Mon-Wed are vacation, Thursday is Thanksgiving: Friday rings, the first skip is named.
        assertEquals("2026-11-27T07:00", local(o.triggerMillis))
        assertEquals("Skipping Vacation", o.reason)
    }

    @Test fun observedHolidayAcrossTheNewYear() {
        val a = Alarm("w", 7, 0, weekdays, skipHolidays = true)
        val o = nextOccurrenceAfter(a, at("2027-12-30T07:01"), la, rules())
        assertEquals("2028-01-03T07:00", local(o.triggerMillis))
        assertEquals("Skipping New Year's Day (observed)", o.reason)
    }

    @Test fun skippingKeepsDstRules() {
        // Every day at 2:30, skipping holidays: Mar 8, 2026 is the spring-forward gap, not a holiday.
        val a = Alarm("d", 2, 30, DayOfWeek.values().toSet(), skipHolidays = true)
        assertEquals("2026-03-08T03:30", local(nextOccurrenceAfter(a, at("2026-03-08T01:00"), la, rules()).triggerMillis))
        // Veterans Day Wed Nov 11 skipped, then the fall-back day Nov 1 isn't touched (it's before).
        val w = Alarm("w", 1, 30, weekdays, skipHolidays = true)
        val o = nextOccurrenceAfter(w, at("2026-11-10T02:00"), la, rules())
        assertEquals("2026-11-12T01:30", local(o.triggerMillis))
        assertEquals("Skipping Veterans Day", o.reason)
        // Fall-back Sunday Nov 1 for a daily alarm at 1:30 still rings once, at the first 1:30.
        val first = nextOccurrenceAfter(Alarm("d", 1, 30, DayOfWeek.values().toSet(), skipHolidays = true), at("2026-11-01T00:00"), la, rules()).triggerMillis
        assertEquals("2026-11-01T01:30", local(first))
        assertEquals("2026-11-02T01:30", local(nextOccurrenceAfter(Alarm("d", 1, 30, DayOfWeek.values().toSet(), skipHolidays = true), first, la, rules()).triggerMillis))
    }

    @Test fun holidayOnDstDay() {
        // Presidents Day is a Monday far from DST, but the 25 h fall-back day can be a skipped holiday for
        // a Sunday alarm that skips: no federal holiday on Nov 1, so it rings; a spring-forward Monday doesn't exist.
        val sun = Alarm("s", 7, 0, setOf(DayOfWeek.SUNDAY), skipHolidays = true)
        assertEquals("2026-11-01T07:00", local(nextOccurrenceAfter(sun, at("2026-10-31T12:00"), la, rules()).triggerMillis))
    }

    @Test fun engineSkipsTheHolidayAndSaysWhy() {
        val e = AlarmEngine(AlarmData(dayOffs = mapOf("2026-11-24" to "Day off")), { la })
        val now = at("2026-11-23T08:00")
        e.upsert(Alarm("w", 7, 0, weekdays, skipHolidays = true), now)
        var ev = e.evaluate(now)
        assertEquals("2026-11-25T07:00", local(ev.next!!.triggerAtMillis)) // Tue is a day off
        assertEquals("Skipping Day off", ev.next!!.reason)
        assertEquals(ev.next!!.triggerAtMillis, ev.alarmClockAtMillis)
        // Wed rings; Thanksgiving doesn't, and Friday is next.
        ev = e.evaluate(at("2026-11-25T07:00"))
        assertTrue(ev.phase is AlarmPhase.Ringing)
        e.stop(at("2026-11-25T07:01")); e.dismissGoodMorning()
        ev = e.evaluate(at("2026-11-26T06:00"))
        assertEquals("2026-11-27T07:00", local(ev.next!!.triggerAtMillis))
        assertEquals("Skipping Thanksgiving", ev.next!!.reason)
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-26T07:00") + 1000).phase)
    }

    // --- leave-by planning ---

    private val lb = Alarm("l", 7, 0, weekdays, leaveBy = true, readyMinutes = 45)

    @Test fun eventChoice() {
        val day = d("2026-11-24")
        val events = listOf(
            allDay("Conference", "2026-11-24"),
            timed("No place", "2026-11-24T08:00", location = null),
            timed("Lunch", "2026-11-24T12:00"),
            timed("Dentist", "2026-11-24T09:00", profile = "maya"),
            timed("Standup", "2026-11-24T08:30", profile = "sam"),
            timed("Tomorrow", "2026-11-25T08:00"),
        )
        assertEquals("Standup", LeaveBy.eventFor(lb, day, events, la)!!.title) // shared alarm: everyone's
        assertEquals("Dentist", LeaveBy.eventFor(lb.copy(profileId = "maya"), day, events, la)!!.title) // sam's Standup is not hers
        assertEquals("Lunch", LeaveBy.eventFor(lb.copy(profileId = "maya"), day, events - events[3], la)!!.title)
        assertNull(LeaveBy.eventFor(lb, d("2026-11-26"), events, la))
    }

    @Test fun planMovesEarlierOnlyWhenNeeded() {
        val day = d("2026-11-24")
        // 9:00 event, 25 min drive, 45 min ready: leave 8:35, up by 7:50: after 7:00, so no change.
        assertNull(LeaveBy.plan(lb, day, timed("Dentist", "2026-11-24T09:00"), 25, la))
        // 8:00 event: up by 6:50, ten minutes earlier.
        val s = LeaveBy.plan(lb, day, timed("Dentist", "2026-11-24T08:00"), 25, la)!!
        assertEquals(10, s.earlierMin)
        assertEquals("Earlier for Dentist 8:00 · 25 min drive", LeaveBy.reason(s) { ms -> local(ms).substring(11).trimStart('0') })
        // Exactly enough time: no shift.
        assertNull(LeaveBy.plan(lb, day, timed("Dentist", "2026-11-24T08:10"), 25, la))
    }

    @Test fun planIsCappedAtTwoHoursAndNeedsATravelTime() {
        val day = d("2026-11-24")
        val e = timed("Flight", "2026-11-24T07:00")
        assertEquals(120, LeaveBy.plan(lb.copy(readyMinutes = 90), day, e, 60, la)!!.earlierMin)
        assertNull(LeaveBy.plan(lb, day, e, null, la))
    }

    @Test fun readyMinutesChangeTheTime() {
        val day = d("2026-11-24")
        val e = timed("Class", "2026-11-24T08:30")
        // 8:30 - 30 drive - 60 ready = 7:00 exactly: no change. 90 ready: 30 min earlier.
        assertNull(LeaveBy.plan(lb.copy(readyMinutes = 60), day, e, 30, la))
        assertEquals(30, LeaveBy.plan(lb.copy(readyMinutes = 90), day, e, 30, la)!!.earlierMin)
    }

    @Test fun longTitlesAreCut() {
        val r = LeaveBy.reason(LeaveShift(5, "A very long meeting title that goes on", at("2026-11-24T08:00"), 12)) { "8:00" }
        assertEquals("Earlier for A very long meeting tit… 8:00 · 12 min drive", r)
    }

    private fun engineWith(shift: LeaveShift?, date: String = "2026-11-24"): AlarmEngine {
        val shifts = if (shift == null) emptyMap() else mapOf(LeaveBy.key("l", d(date)) to shift)
        return AlarmEngine(AlarmData(shifts = shifts), { la }, timeText = { ms -> local(ms).substring(12) })
    }

    @Test fun engineRingsAtTheShiftedTimeWithAReason() {
        val shift = LeaveShift(10, "Dentist", at("2026-11-24T08:00"), 25)
        val e = engineWith(shift)
        val now = at("2026-11-23T22:00")
        e.upsert(lb, now)
        val ev = e.evaluate(now)
        assertEquals("2026-11-24T06:50", local(ev.next!!.triggerAtMillis))
        assertEquals("Earlier for Dentist 8:00 · 25 min drive", ev.next!!.reason)
        assertEquals(ev.next!!.triggerAtMillis, ev.alarmClockAtMillis) // the system alarm follows
        assertEquals(at("2026-11-24T06:20"), ev.wakeAtMillis) // 30 min of sunrise before the adjusted time
        assertNotNull(e.evaluate(at("2026-11-24T06:20")).phase as? AlarmPhase.Sunrise)
        // 6:50 rings.
        assertTrue(e.evaluate(at("2026-11-24T06:50")).phase is AlarmPhase.Ringing)
    }

    @Test fun theSetTimeDoesntRingAgainAfterTheEarlyOne() {
        val e = engineWith(LeaveShift(10, "Dentist", at("2026-11-24T08:00"), 25))
        e.upsert(lb, at("2026-11-23T22:00"))
        e.evaluate(at("2026-11-24T06:50"))
        e.stop(at("2026-11-24T06:51"))
        e.dismissGoodMorning()
        val ev = e.evaluate(at("2026-11-24T06:55"))
        assertEquals("2026-11-25T07:00", local(ev.next!!.triggerAtMillis))
        assertNull(ev.next!!.reason)
        assertEquals(AlarmPhase.Idle, e.evaluate(at("2026-11-24T07:00")).phase)
    }

    @Test fun noShiftMeansTheSetTimeAndNoReason() {
        val e = engineWith(null)
        e.upsert(lb, at("2026-11-23T22:00"))
        val ev = e.evaluate(at("2026-11-23T22:00"))
        assertEquals("2026-11-24T07:00", local(ev.next!!.triggerAtMillis))
        assertNull(ev.next!!.reason)
    }

    @Test fun shiftsOnlyApplyToLeaveByAlarms() {
        val e = engineWith(LeaveShift(10, "Dentist", at("2026-11-24T08:00"), 25))
        e.upsert(lb.copy(leaveBy = false), at("2026-11-23T22:00"))
        assertEquals("2026-11-24T07:00", local(e.evaluate(at("2026-11-23T22:00")).next!!.triggerAtMillis))
    }

    @Test fun shiftAcrossDst() {
        // Mar 9, 2026 7:00 PDT (clocks went forward on the 8th); 8 h before 7:00 is fine, 20 min earlier is 6:40.
        val a = lb.copy(id = "l")
        val shift = LeaveShift(20, "Early", at("2026-03-09T08:00"), 15)
        val o = nextOccurrenceAfter(a, at("2026-03-08T12:00"), la, OccurrenceRules(shift = { al, date -> shift.takeIf { al.id == "l" && date == d("2026-03-09") } }))
        assertEquals("2026-03-09T06:40", local(o.triggerMillis))
    }

    // --- the planner (calendar + travel → shifts) ---

    private fun plan(
        events: List<CalendarEvent>, now: String, alarms: List<Alarm> = listOf(lb), old: Map<String, LeaveShift> = emptyMap(),
        known: Boolean = true, travel: (String, Long) -> Int? = { _, _ -> 25 },
    ) = runBlocking { LeavePlanner.shifts(alarms, events, old, at(now), la, known) { l, t -> travel(l, t) } }

    @Test fun plannerFillsTodayAndTomorrow() {
        val asked = mutableListOf<Pair<String, String>>()
        val out = plan(listOf(timed("Dentist", "2026-11-24T08:00", "12 Main St")), "2026-11-23T20:00") { loc, t -> asked += loc to local(t); 25 }
        assertEquals(setOf(LeaveBy.key("l", d("2026-11-24"))), out.keys)
        assertEquals(10, out.values.single().earlierMin)
        assertEquals(listOf("12 Main St" to "2026-11-24T07:00"), asked) // an hour before the event
    }

    @Test fun plannerKeepsThePastAndDropsAMovedEvent() {
        val key = LeaveBy.key("l", d("2026-11-24"))
        val old = mapOf(key to LeaveShift(10, "Dentist", at("2026-11-24T08:00"), 25))
        // The event was deleted and the morning hasn't come: the plan goes away.
        assertTrue(plan(emptyList(), "2026-11-23T22:00", old = old).isEmpty())
        // The calendar isn't loaded yet: don't conclude anything.
        assertEquals(old, plan(emptyList(), "2026-11-23T22:00", old = old, known = false))
        // That morning's early ring already happened: the shift stays so the set time doesn't ring again.
        assertEquals(old, plan(emptyList(), "2026-11-24T06:55", old = old))
        // Days long gone are pruned.
        val ancient = mapOf(LeaveBy.key("l", d("2026-11-10")) to old.values.single())
        assertTrue(plan(emptyList(), "2026-11-23T22:00", old = ancient).isEmpty())
    }

    @Test fun plannerNullTravelRingsAtTheSetTime() {
        val events = listOf(timed("Dentist", "2026-11-24T08:00"))
        assertTrue(plan(events, "2026-11-23T20:00") { _, _ -> null }.isEmpty())
        // ...but a good earlier estimate for the same event isn't thrown away by one failed lookup.
        val old = mapOf(LeaveBy.key("l", d("2026-11-24")) to LeaveShift(10, "Dentist", at("2026-11-24T08:00"), 25))
        assertEquals(old, plan(events, "2026-11-23T20:00", old = old) { _, _ -> null })
    }

    @Test fun plannerIgnoresOffDaysAndOtherAlarms() {
        val events = listOf(timed("Dentist", "2026-11-28T08:00"), timed("Dentist", "2026-11-26T07:30"))
        // Saturday isn't a weekday alarm day.
        assertTrue(plan(events, "2026-11-27T20:00").isEmpty())
        // Thanksgiving is skipped for a skip-holidays alarm: no travel lookup needed.
        assertTrue(plan(events, "2026-11-25T20:00", alarms = listOf(lb.copy(skipHolidays = true))) { _, _ -> error("asked") }.isEmpty())
        // A plain alarm is left alone.
        assertTrue(plan(events, "2026-11-25T20:00", alarms = listOf(lb.copy(leaveBy = false))).isEmpty())
    }

    @Test fun plannerWontMoveAnAlarmIntoThePast() {
        val events = listOf(timed("Dentist", "2026-11-24T07:30"))
        // 7:30 - 25 - 45 = 6:20: it's 6:40, that ring time is gone; the alarm rings at 7:00 as set.
        assertTrue(plan(events, "2026-11-24T06:40").isEmpty())
    }

    @Test fun dayOffsFollowTheCalendarWindow() {
        val now = at("2026-11-23T20:00")
        val out = LeavePlanner.dayOffs(mapOf("2026-11-24" to "Old", "2026-12-24" to "Winter break", "2026-10-01" to "Ancient"),
            listOf(allDay("PTO", "2026-11-24")), now, la, true)
        assertEquals(mapOf("2026-11-24" to "PTO", "2026-12-24" to "Winter break"), out)
        // Not loaded yet: keep what we knew.
        assertEquals(mapOf("2026-11-24" to "Old", "2026-12-24" to "Winter break"),
            LeavePlanner.dayOffs(mapOf("2026-11-24" to "Old", "2026-12-24" to "Winter break"), emptyList(), now, la, false))
        // Loaded and empty: the event is gone.
        assertEquals(mapOf("2026-12-24" to "Winter break"),
            LeavePlanner.dayOffs(mapOf("2026-11-24" to "Old", "2026-12-24" to "Winter break"), emptyList(), now, la, true))
    }

    @Test fun refreshTimes() {
        val a = listOf(lb)
        // The evening before, the next refresh is the overnight one; after it, 3 h before 7:00 is 4:00.
        assertEquals(at("2026-11-24T00:05"), LeavePlanner.nextRefreshAt(a, at("2026-11-23T20:00"), la))
        assertEquals(at("2026-11-24T04:00"), LeavePlanner.nextRefreshAt(a, at("2026-11-24T01:00"), la))
        // Without leave-by alarms only the overnight one.
        assertEquals(at("2026-11-24T00:05"), LeavePlanner.nextRefreshAt(emptyList(), at("2026-11-23T20:00"), la))
    }

    // --- persistence ---

    @Test fun codecRoundTripsNewFields() {
        val a = Alarm("x", 6, 15, weekdays, leaveBy = true, readyMinutes = 60, skipHolidays = true)
        val data = AlarmData(listOf(a), shifts = mapOf("x|2026-11-24" to LeaveShift(10, "Dentist", 12345L, 25)), dayOffs = mapOf("2026-11-25" to "PTO"))
        val back = Codec.alarmsFromJson(Codec.alarmsToJson(data))
        assertEquals(data, back)
        // An old file without the fields reads as the defaults.
        val old = Codec.alarmFromJson(org.json.JSONObject("{\"id\":\"o\",\"hour\":7,\"minute\":0}"))!!
        assertFalse(old.leaveBy); assertFalse(old.skipHolidays); assertEquals(45, old.readyMinutes)
    }
}
