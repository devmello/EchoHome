package app.vesper.feature.clock

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WidgetModelTest {
    private class MemSettings : SettingsStore {
        val map = HashMap<String, Any>()
        private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val changes: SharedFlow<String> = _changes
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value; _changes.tryEmit(key) }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private val zone: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis

    // ---------------------------------------------------------------- order and stacks

    @Test fun orderHasEveryWidgetOnceWithStableIds() {
        val ids = WidgetStacks.ORDER.map { it.id }
        assertEquals(listOf("clock", "weather", "calendar", "alarm", "timers", "nowplaying", "air", "sunmoon"), ids)
        assertEquals(ids, WidgetStacks.options().map { it.first })
        for (k in WidgetKind.entries) assertEquals(k, WidgetKind.byId(k.id))
        assertEquals(WidgetKind.AIR, WidgetKind.byId(" Air "))
        assertNull(WidgetKind.byId("nope"))
    }

    @Test fun stepWrapsAroundBothEnds() {
        assertEquals(WidgetKind.WEATHER, WidgetStacks.step(WidgetKind.CLOCK, 1))
        assertEquals(WidgetKind.SUN_MOON, WidgetStacks.step(WidgetKind.CLOCK, -1))
        assertEquals(WidgetKind.CLOCK, WidgetStacks.step(WidgetKind.SUN_MOON, 1))
        var k = WidgetKind.TIMERS
        repeat(WidgetStacks.ORDER.size) { k = WidgetStacks.step(k, 1) }
        assertEquals(WidgetKind.TIMERS, k)
        assertEquals(3, WidgetStacks.wrap(-5, 8))
        assertEquals(0, WidgetStacks.wrap(16, 8))
    }

    @Test fun parseFallsBackToTheDefault() {
        assertEquals(WidgetKind.CLOCK, WidgetStacks.parse(null, WidgetKind.CLOCK))
        assertEquals(WidgetKind.WEATHER, WidgetStacks.parse("", WidgetKind.WEATHER))
        assertEquals(WidgetKind.WEATHER, WidgetStacks.parse("garbage", WidgetKind.WEATHER))
        assertEquals(WidgetKind.TIMERS, WidgetStacks.parse("timers", WidgetKind.WEATHER))
    }

    @Test fun stacksDefaultToClockAndWeatherAndPersistEachOnItsOwn() {
        val s = MemSettings()
        val left = WidgetStackState(s, WidgetStacks.KEY_LEFT, WidgetStacks.DEFAULT_LEFT)
        val right = WidgetStackState(s, WidgetStacks.KEY_RIGHT, WidgetStacks.DEFAULT_RIGHT)
        assertEquals(WidgetKind.CLOCK, left.current)
        assertEquals(WidgetKind.WEATHER, right.current)

        assertEquals(WidgetKind.SUN_MOON, left.move(-1)) // wraps
        assertEquals("sunmoon", s.getString("clock.widgets.left", ""))
        assertEquals(WidgetKind.CALENDAR, right.move(1))
        assertEquals("calendar", s.getString("clock.widgets.right", ""))
        assertEquals(WidgetKind.SUN_MOON, left.current)

        // a fresh state (process restart) reads what was stored
        assertEquals(WidgetKind.SUN_MOON, WidgetStackState(s, WidgetStacks.KEY_LEFT, WidgetStacks.DEFAULT_LEFT).current)
        assertEquals(WidgetKind.CALENDAR, WidgetStackState(s, WidgetStacks.KEY_RIGHT, WidgetStacks.DEFAULT_RIGHT).current)
    }

    @Test fun reloadFollowsTheSettingsPage() {
        val s = MemSettings()
        val left = WidgetStackState(s, WidgetStacks.KEY_LEFT, WidgetStacks.DEFAULT_LEFT)
        assertFalse(left.reload())
        s.putString(WidgetStacks.KEY_LEFT, "nowplaying")
        assertTrue(left.reload())
        assertEquals(WidgetKind.NOW_PLAYING, left.current)
        assertFalse(left.reload())
        s.putString(WidgetStacks.KEY_LEFT, "bogus") // unknown → default
        assertTrue(left.reload())
        assertEquals(WidgetKind.CLOCK, left.current)
    }

    // ---------------------------------------------------------------- gesture

    @Test fun onlyAClearlyVerticalDragIsClaimed() {
        val slop = 16f
        assertEquals(StackGesture.Axis.UNDECIDED, StackGesture.decide(5f, 10f, slop))
        assertEquals(StackGesture.Axis.VERTICAL, StackGesture.decide(4f, -30f, slop))
        assertEquals(StackGesture.Axis.VERTICAL, StackGesture.decide(20f, 30f, slop))
        // diagonal: neither (the pager wants 1.5× wider, we want 1.2× taller)
        assertEquals(StackGesture.Axis.UNDECIDED, StackGesture.decide(20f, 22f, slop))
        assertEquals(StackGesture.Axis.HORIZONTAL, StackGesture.decide(30f, 10f, slop))
        assertEquals(StackGesture.Axis.HORIZONTAL, StackGesture.decide(-40f, 40f, slop))
    }

    @Test fun settleCommitsPastAQuarterOrOnAFling() {
        val h = 432f; val fling = 400f
        assertEquals(0, StackGesture.settle(-60f, 0f, h, fling))
        assertEquals(1, StackGesture.settle(-120f, 0f, h, fling))
        assertEquals(-1, StackGesture.settle(120f, 0f, h, fling))
        assertEquals(1, StackGesture.settle(-30f, -900f, h, fling))
        assertEquals(-1, StackGesture.settle(30f, 900f, h, fling))
        // a fling against the drag goes back
        assertEquals(0, StackGesture.settle(-200f, 900f, h, fling))
        assertEquals(0, StackGesture.settle(200f, -900f, h, fling))
    }

    // ---------------------------------------------------------------- data

    private fun alarm(id: String, h: Int, m: Int, profile: String? = null, enabled: Boolean = true) =
        Alarm(id, h, m, label = id, enabled = enabled, profileId = profile)

    @Test fun sharedSeesTheStoresNextAlarmWithItsReason() {
        val now = at(2026, 11, 22, 22, 0)
        val a = alarm("wake", 7, 0)
        val next = NextAlarm(a, at(2026, 11, 23, 6, 35), "Earlier for Dentist 9:00 · 25 min drive")
        val p = WidgetData.alarmFor(null, listOf(a), next, now, zone)!!
        assertEquals("wake", p.alarm.id)
        assertEquals(at(2026, 11, 23, 6, 35), p.triggerAtMillis)
        assertEquals("Earlier for Dentist 9:00 · 25 min drive", p.reason)
        assertNull(WidgetData.alarmFor(null, listOf(a), null, now, zone))
    }

    @Test fun aPersonSeesTheirOwnPlusSharedAlarms() {
        val now = at(2026, 11, 22, 22, 0)
        val sam = alarm("sam", 5, 30, "sam")
        val maya = alarm("maya", 6, 15, "maya")
        val shared = alarm("shared", 7, 0)
        val all = listOf(sam, maya, shared)
        // the store's next is Sam's: Maya gets her own instead
        val next = NextAlarm(sam, at(2026, 11, 23, 5, 30))
        val p = WidgetData.alarmFor("maya", all, next, now, zone)!!
        assertEquals("maya", p.alarm.id)
        assertEquals(at(2026, 11, 23, 6, 15), p.triggerAtMillis)
        assertNull(p.reason)
        // Maya's alarm off: the shared one
        val q = WidgetData.alarmFor("maya", listOf(sam, maya.copy(enabled = false), shared), next, now, zone)!!
        assertEquals("shared", q.alarm.id)
        // the store's next is shared and earliest: used, with its reason
        val n2 = NextAlarm(shared, at(2026, 11, 23, 6, 0), "Earlier")
        val r = WidgetData.alarmFor("maya", listOf(shared), n2, now, zone)!!
        assertEquals(at(2026, 11, 23, 6, 0), r.triggerAtMillis)
        assertEquals("Earlier", r.reason)
    }

    private fun ev(id: String, s: Long, e: Long, profile: String? = null, allDay: Boolean = false) =
        CalendarEvent(id, id, "", s, e, allDay, 0xFF0A84FF.toInt(), profileId = profile)

    @Test fun calendarShowsTodaysRemainingEventsForThePerson() {
        val now = at(2026, 11, 22, 12, 0)
        val events = listOf(
            ev("past", at(2026, 11, 22, 9, 0), at(2026, 11, 22, 10, 0)),
            ev("now", at(2026, 11, 22, 11, 30), at(2026, 11, 22, 12, 30)),
            ev("sam", at(2026, 11, 22, 14, 0), at(2026, 11, 22, 15, 0), "sam"),
            ev("maya", at(2026, 11, 22, 16, 0), at(2026, 11, 22, 17, 0), "maya"),
            ev("holiday", at(2026, 11, 22, 0, 0), at(2026, 11, 23, 0, 0), allDay = true),
            ev("tmrw", at(2026, 11, 23, 9, 30), at(2026, 11, 23, 10, 0)),
        )
        assertEquals(listOf("holiday", "now", "sam"), WidgetData.today(events, now, zone = zone).map { it.id })
        val maya = WidgetData.visible("maya", events)
        assertEquals(listOf("holiday", "now", "maya"), WidgetData.today(maya, now, zone = zone).map { it.id })
        assertEquals("tmrw", WidgetData.firstTomorrow(events, now, zone)!!.id)
        assertTrue(WidgetData.today(events, at(2026, 11, 22, 23, 59), zone = zone).map { it.id } == listOf("holiday"))
    }

    @Test fun countdownRoundsUpToWholeSeconds() {
        assertEquals("0:00", WidgetData.countdown(0))
        assertEquals("0:01", WidgetData.countdown(1))
        assertEquals("0:01", WidgetData.countdown(1000))
        assertEquals("4:05", WidgetData.countdown(244_100))
        assertEquals("10:00", WidgetData.countdown(600_000))
        assertEquals("1:02:09", WidgetData.countdown(3_729_000))
    }

    @Test fun relativeTimes() {
        assertEquals("now", WidgetData.inText(0))
        assertEquals("in 1 min", WidgetData.inText(1_000))
        assertEquals("in 45 min", WidgetData.inText(45 * 60_000L))
        assertEquals("in 3 h", WidgetData.inText(3 * 3_600_000L))
        assertEquals("in 7 h 12 min", WidgetData.inText(7 * 3_600_000L + 12 * 60_000L))
    }

    @Test fun uvAndAqiScales() {
        assertEquals("Low", WidgetData.uvCategory(2.9f))
        assertEquals("Moderate", WidgetData.uvCategory(3f))
        assertEquals("High", WidgetData.uvCategory(7.5f))
        assertEquals("Very high", WidgetData.uvCategory(10f))
        assertEquals("Extreme", WidgetData.uvCategory(11f))
        assertEquals(0xFF30D158.toInt(), WidgetData.aqiColor(50))
        assertEquals(0xFFFFD60A.toInt(), WidgetData.aqiColor(51))
        assertEquals(0xFFBF5AF2.toInt(), WidgetData.aqiColor(300))
    }

    @Test fun nextFullOrNewMoon() {
        assertEquals("Full moon" to 0, WidgetData.nextMoonEvent(180.0))
        assertEquals("Full moon" to 2, WidgetData.nextMoonEvent(155.0))
        assertEquals("New moon" to 1, WidgetData.nextMoonEvent(350.0))
        assertEquals("Full moon", WidgetData.nextMoonEvent(10.0).first)
        assertEquals("New moon", WidgetData.nextMoonEvent(200.0).first)
        assertEquals(14, WidgetData.nextMoonEvent(10.0).second) // 170° at ~12.19°/day
    }
}
