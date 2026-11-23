package app.vesper.feature.home

import android.os.Bundle
import app.vesper.core.model.Alarm
import app.vesper.core.model.AirQuality
import app.vesper.core.model.Banner
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.DefaultSystemStateStore
import app.vesper.core.model.Navigator
import app.vesper.core.model.Nowcast
import app.vesper.core.model.Presence
import app.vesper.core.model.Profile
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.fake.FakeAlarmStore
import app.vesper.feature.home.alerts.AlertSettings
import app.vesper.feature.home.brief.BriefBuilder
import app.vesper.feature.home.brief.BriefRules
import app.vesper.feature.home.brief.BriefWatcher
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() { scope.cancel() }

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDate.of(2026, 11, day).atTime(h, m).atZone(la).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    // ---- trigger rules ----

    @Test fun presenceTriggersBetweenFiveAndElevenAfterAThreeHourAbsence() {
        val last = at(21, 23) // last seen 23:00 the night before
        fun go(now: Long, lastPresent: Long = last, shown: String? = null, idle: Boolean = true, enabled: Boolean = true) =
            BriefRules.fromPresence(now, la, lastPresent, shown, idle, enabled)
        assertTrue(go(at(22, 6, 30)))
        assertTrue(go(at(22, 5, 0)))
        assertTrue(go(at(22, 10, 59)))
        assertFalse(go(at(22, 4, 59)))
        assertFalse(go(at(22, 11, 0)))
        assertFalse(go(at(22, 6, 30), lastPresent = at(22, 3, 45))) // only 2 h 45 min away
        assertTrue(go(at(22, 6, 30), lastPresent = at(22, 3, 30))) // exactly 3 h
        assertFalse(go(at(22, 6, 30), lastPresent = 0)) // never seen anyone: can't tell it was a night
        assertFalse(go(at(22, 6, 30), shown = "2026-11-22")) // once a day
        assertTrue(go(at(22, 6, 30), shown = "2026-11-21"))
        assertFalse(go(at(22, 6, 30), idle = false)) // an alarm is ramping / ringing
        assertFalse(go(at(22, 6, 30), enabled = false))
    }

    @Test fun alarmTriggerAfterGoodMorningOncePerDay() {
        assertTrue(BriefRules.fromAlarm(at(22, 6, 45), la, null))
        assertTrue(BriefRules.fromAlarm(at(22, 4, 30), la, "2026-11-21"))
        assertFalse(BriefRules.fromAlarm(at(22, 6, 45), la, "2026-11-22"))
        assertFalse(BriefRules.fromAlarm(at(22, 15, 0), la, null)) // an afternoon alarm isn't a morning
        assertFalse(BriefRules.fromAlarm(at(22, 6, 45), la, null, enabled = false))
    }

    private class Nav : Navigator {
        val shown = CopyOnWriteArrayList<String>()
        override val current: StateFlow<String> = MutableStateFlow("standby")
        override fun show(screenId: String, args: Bundle?) { shown += screenId }
        override fun home() {}
    }

    private fun watcher(settings: MemSettings, system: DefaultSystemStateStore, alarms: FakeAlarmStore, nav: Nav, clock: () -> Long) =
        BriefWatcher(settings, { system }, { alarms }, { nav }, scope, clock = clock, zone = { la }, handoverDelayMs = 20).also { it.start() }

    @Test fun watcherOpensTheBriefOnFirstPresenceOnceAndRemembersWhenSomebodyLeft() = runBlocking {
        var t = at(21, 22, 40)
        val settings = MemSettings(); val system = DefaultSystemStateStore(); val alarms = FakeAlarmStore(false); val nav = Nav()
        system.update { it.copy(presence = Presence.PRESENT) } // somebody is there in the evening
        watcher(settings, system, alarms, nav) { t }
        delay(150)
        t = at(21, 23, 0)
        system.update { it.copy(presence = Presence.EMPTY) } // goes to sleep / leaves: remembered
        delay(100)
        assertEquals(t.toString(), settings.getString(AlertSettings.BRIEF_LAST_PRESENT, ""))
        t = at(22, 6, 30)
        system.update { it.copy(presence = Presence.PRESENT) }
        delay(150)
        assertEquals(listOf("brief"), nav.shown)
        assertEquals("2026-11-22", settings.getString(AlertSettings.BRIEF_LAST_DAY, ""))
        // Leaves and comes back at 7:00: already shown today.
        t = at(22, 6, 40); system.update { it.copy(presence = Presence.EMPTY) }; delay(80)
        t = at(22, 7, 0); system.update { it.copy(presence = Presence.PRESENT) }; delay(150)
        assertEquals(1, nav.shown.size)
    }

    @Test fun watcherDoesNotOpenInTheAfternoonOrAfterAShortAbsence() = runBlocking {
        var t = at(22, 14, 0)
        val settings = MemSettings(AlertSettings.BRIEF_LAST_PRESENT to at(22, 9, 0).toString())
        val system = DefaultSystemStateStore(); val alarms = FakeAlarmStore(false); val nav = Nav()
        watcher(settings, system, alarms, nav) { t }
        delay(150)
        system.update { it.copy(presence = Presence.PRESENT) }; delay(150)
        assertTrue(nav.shown.isEmpty()) // afternoon
        system.update { it.copy(presence = Presence.EMPTY) }; delay(80)
        t = at(23, 6, 0) // next morning but only seen a moment ago (the user stayed at the display)
        settings.putString(AlertSettings.BRIEF_LAST_PRESENT, at(23, 4, 0).toString())
        system.update { it.copy(presence = Presence.PRESENT) }; delay(150)
        assertTrue(nav.shown.isEmpty())
    }

    @Test fun watcherNeverOpensWhileAnAlarmRingsAndOpensAfterGoodMorning() = runBlocking {
        var t = at(22, 6, 30)
        val settings = MemSettings(AlertSettings.BRIEF_LAST_PRESENT to at(21, 23).toString())
        val system = DefaultSystemStateStore(); val alarms = FakeAlarmStore(false); val nav = Nav()
        val alarm = Alarm("a", 6, 30)
        watcher(settings, system, alarms, nav) { t }
        delay(150)
        alarms.ringNow(alarm) // ringing
        delay(50)
        system.update { it.copy(presence = Presence.PRESENT) } // the person appears to stop it
        delay(150)
        assertTrue(nav.shown.isEmpty()) // not while ringing
        alarms.stop() // → Good morning
        delay(100)
        assertTrue(nav.shown.isEmpty()) // Good morning is its own screen
        alarms.dismissGoodMorning() // Done
        delay(300)
        assertEquals(listOf("brief"), nav.shown)
    }

    @Test fun watcherIsOffWhenDisabled() = runBlocking {
        val t = at(22, 6, 30)
        val settings = MemSettings(AlertSettings.BRIEF_LAST_PRESENT to at(21, 23).toString(), AlertSettings.BRIEF to false)
        val system = DefaultSystemStateStore(); val alarms = FakeAlarmStore(false); val nav = Nav()
        watcher(settings, system, alarms, nav) { t }
        delay(150)
        system.update { it.copy(presence = Presence.PRESENT) }; delay(150)
        assertTrue(nav.shown.isEmpty())
    }

    // ---- content ----

    private fun banner(id: String, app: String, title: String, at: Long, color: Int = 0xFF30D158.toInt()) =
        Banner(id, app, color, title = title, body = "", postedAtMillis = at)

    @Test fun overnightIsGroupedByAppSinceTenPm() {
        val now = at(22, 6, 30)
        val list = listOf(
            banner("0", "Messages", "too early", at(21, 21, 59)),
            banner("1", "Messages", "Sam: See you at 9?", at(21, 22, 5)),
            banner("2", "Mail", "Order shipped", at(22, 1, 0)),
            banner("3", "Messages", "Mom", at(22, 5, 0)),
            banner("4", "Messages", "Mom", at(22, 5, 30)), // same title again: listed once
            banner("5", "Calendar", "Dentist tomorrow", at(22, 6, 0)),
            banner("6", "Slack", "later", now + 1), // from the future: ignored
        )
        val o = BriefBuilder.overnight(list, now, la)
        assertEquals(5, o.total)
        assertEquals(listOf("Messages", "Calendar", "Mail"), o.groups.map { it.app }) // by count, then name
        assertEquals(3, o.groups[0].count)
        assertEquals(listOf("Mom", "Sam: See you at 9?"), o.groups[0].titles) // newest first, de-duplicated
        assertEquals(0, o.more)
        // Before 22:00 "overnight" means last night's 22:00.
        assertEquals(at(21, 22), BriefBuilder.overnightStart(at(22, 9, 0), la))
        assertEquals(at(22, 22), BriefBuilder.overnightStart(at(22, 22, 30), la))
    }

    @Test fun manyAppsShowThreeAndCountTheRest() {
        val now = at(22, 7, 0)
        val list = (1..5).map { banner("$it", "App$it", "t", at(22, 1)) }
        val o = BriefBuilder.overnight(list, now, la)
        assertEquals(3, o.groups.size); assertEquals(2, o.more); assertEquals(5, o.total)
    }

    @Test fun greetingUsesTheActiveProfilesName() {
        assertEquals("Good morning, Maya", BriefBuilder.greeting(Profile("maya", "Maya", 0)))
        assertEquals("Good morning", BriefBuilder.greeting(Profile.SHARED))
        assertEquals("Good morning", BriefBuilder.greeting(null))
        assertEquals("Sunday, November 22", BriefBuilder.dateLine(at(22, 7), la))
    }

    private fun ev(id: String, start: Long, loc: String? = null, profile: String? = null, allDay: Boolean = false) =
        CalendarEvent(id, "Dentist", "Pacific Ave", start, start + hour, allDay, 0, loc, profile)

    @Test fun firstEventWithLeaveTimeAndTravel() {
        val now = at(22, 6, 30)
        val maya = Profile("maya", "Maya", 0)
        val events = listOf(ev("a", at(22, 6, 0)).copy(endMillis = at(22, 6, 20)), // over
            ev("allday", at(22, 0), allDay = true), ev("sam", at(22, 8, 0), profile = "sam"),
            ev("b", at(22, 9, 0), "Pacific Ave, Santa Cruz"))
        val first = BriefBuilder.firstEvent(events, now, maya)!!
        assertEquals("b", first.id)
        val e = BriefBuilder.event(first, 25, 5, la)!!
        assertEquals("9:00 AM · Pacific Ave", e.whenText)
        assertEquals("Leave by 8:30 AM · 25 min drive", e.leave)
        assertEquals(at(22, 8, 30), e.leaveAtMillis)
        assertNull(BriefBuilder.event(first, null, 5, la)!!.leave) // travel unknown: no leave line
        assertNull(BriefBuilder.event(null, null, 5, la))
        assertEquals("sam", BriefBuilder.firstEvent(events, now, Profile.SHARED)!!.id) // shared sees everyone's
    }

    @Test fun weatherCardLinesRainAirUvPollen() {
        val now = at(22, 6, 30)
        val steps = FloatArray(9).also { it[2] = 0.6f }
        val air = AirQuality(usAqi = 42, uvIndex = 0f, uvIndexMax = 6.4f, pollen = mapOf("grass" to 35.4f, "birch" to 2f))
        val w = Weather(52, 63, 46, WeatherCondition.CLEAR, false, location = "Santa Cruz", air = air, nowcast = Nowcast(now, steps.toList()))
        val b = BriefBuilder.weather(w, now)!!
        assertEquals("52°", b.temp); assertEquals("Sunny · H:63° L:46°", b.summary)
        assertEquals(listOf("Rain in 30 min", "AQI 42 · Good", "UV peak 6 · High", "Grass pollen 35"), b.chips.map { it.text })
        // Dry nowcast, raining now, no extras.
        assertEquals("Dry for the next 2 h", BriefBuilder.rainLine(w.copy(nowcast = Nowcast(now, List(9) { 0f })), now))
        assertEquals("Raining now", BriefBuilder.rainLine(w.copy(nowcast = Nowcast(now, List(9) { 1f })), now))
        assertNull(BriefBuilder.rainLine(w.copy(nowcast = null), now))
        assertEquals(emptyList<String>(), BriefBuilder.weather(w.copy(air = null, nowcast = null), now)!!.chips.map { it.text })
        assertNull(BriefBuilder.pollenLine(AirQuality(pollen = mapOf("grass" to 0.4f)))) // below 1 grain: nothing
        assertNull(BriefBuilder.weather(null, now))
    }

    @Test fun buildPutsItAllTogether() {
        val now = at(22, 6, 30)
        val d = BriefBuilder.build(now, la, Profile("maya", "Maya", 0), null, listOf(ev("b", at(22, 9, 0), "Downtown")), 20, 5,
            listOf(banner("1", "Mail", "Hi", at(22, 2))))
        assertEquals("Good morning, Maya", d.greeting)
        assertNull(d.weather)
        assertEquals("Leave by 8:35 AM · 20 min drive", d.event!!.leave)
        assertEquals(1, d.overnight.total)
        assertTrue(BriefBuilder.demo(now, la).demo)
    }
}
