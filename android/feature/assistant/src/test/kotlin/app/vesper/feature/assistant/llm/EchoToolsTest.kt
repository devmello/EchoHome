package app.vesper.feature.assistant.llm

import android.os.Bundle
import app.vesper.core.model.Alarm
import app.vesper.core.model.AirQuality
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.CalendarProvider
import app.vesper.core.model.HourForecast
import app.vesper.core.model.Navigator
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.Nowcast
import app.vesper.core.model.NightMode
import app.vesper.core.model.PhoneStatus
import app.vesper.core.model.TravelEstimator
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.core.model.VesperTimer
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.WeatherProvider
import app.vesper.core.model.Secrets
import app.vesper.feature.assistant.EngineTest
import app.vesper.feature.assistant.pipeline.ResultCard
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EchoToolsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings())
    private var wallNow = 1_000_000_000_000L
    private var elapsedNow = 0L
    private val tools = VesperTools({ services }, main = Dispatchers.Unconfined, zone = { ZoneId.of("UTC") }, elapsed = { elapsedNow }, wall = { wallNow })

    private class Nav : Navigator {
        val shown = ArrayList<String>()
        override val current: StateFlow<String> = MutableStateFlow("assistant")
        override fun show(screenId: String, args: Bundle?) { shown += screenId }
        override fun home() {}
    }

    private fun call(name: String, input: JSONObject = JSONObject()) = runBlocking { tools.run(name, input) }

    @Test fun `every spec has a handler`() {
        // An unknown-tool error would mean a spec nobody dispatches.
        for (s in tools.specs()) {
            val r = try { call(s.name, JSONObject()) } catch (e: org.json.JSONException) { continue } // missing required input: it was dispatched
            assertFalse("${s.name} is not dispatched", r.content.optString("error") == "UNKNOWN_TOOL")
        }
    }

    @Test fun `next alarm says why it rings early`() {
        val a = Alarm("a1", 7, 0, label = "Work", leaveBy = true)
        services.alarms.upsert(a)
        val r = call("next_alarm")
        assertFalse(r.isError)
        assertEquals("a1", r.content.getString("alarm_id"))
        assertNull(r.content.optString("reason").ifEmpty { null })
        // An alarm store that reports a reason:
        val withReason = object : app.vesper.core.model.AlarmStore by services.alarms {
            override val next = MutableStateFlow<NextAlarm?>(NextAlarm(a, wallNow + 25 * 60_000L, "Earlier for Dentist 9:00 · 25 min drive"))
        }
        services.alarms = withReason
        val r2 = call("next_alarm")
        assertEquals("Earlier for Dentist 9:00 · 25 min drive", r2.content.getString("reason"))
        assertEquals(25L, r2.content.getLong("in_minutes"))
        assertEquals("Earlier for Dentist 9:00 · 25 min drive", call("list_alarms").content.getJSONObject("next").getString("reason"))
    }

    @Test fun `no alarm is not an error`() {
        val r = call("next_alarm")
        assertFalse(r.isError)
        assertTrue(r.content.isNull("next"))
    }

    @Test fun `set alarm with leave-by and holidays, update, enable, delete needs confirmation`() {
        val r = call("set_alarm", JSONObject().put("hour", 6).put("minute", 30).put("leave_by", true).put("ready_minutes", 30).put("skip_holidays", true))
        val id = r.content.getJSONObject("alarm").getString("alarm_id")
        val a = services.alarms.alarms.value.first { it.id == id }
        assertTrue(a.leaveBy); assertEquals(30, a.readyMinutes); assertTrue(a.skipHolidays)
        assertEquals("6:30 AM · leave-by", (r.card as ResultCard.Action).state)

        call("update_alarm", JSONObject().put("alarm_id", id).put("label", "Gym").put("skip_holidays", false))
        val u = services.alarms.alarms.value.first { it.id == id }
        assertEquals("Gym", u.label); assertFalse(u.skipHolidays); assertTrue(u.leaveBy)

        call("set_alarm_enabled", JSONObject().put("alarm_id", id).put("enabled", false))
        assertFalse(services.alarms.alarms.value.first { it.id == id }.enabled)
        assertTrue(call("set_alarm_enabled", JSONObject().put("alarm_id", "nope").put("enabled", true)).isError)

        val refused = call("delete_alarm", JSONObject().put("alarm_id", id).put("confirmed", false))
        assertTrue(refused.isError)
        assertEquals("NEEDS_CONFIRMATION", refused.content.getString("error"))
        assertTrue(services.alarms.alarms.value.any { it.id == id })
        assertFalse(call("delete_alarm", JSONObject().put("alarm_id", id).put("confirmed", true)).isError)
        assertFalse(services.alarms.alarms.value.any { it.id == id })
    }

    @Test fun `pause and resume timers`() {
        call("set_timer", JSONObject().put("duration_seconds", 600).put("label", "Pasta"))
        val id = services.timers.timers.value.single().id
        assertTrue(call("resume_timer").isError) // not paused
        val p = call("pause_timer", JSONObject().put("label", "pasta"))
        assertFalse(p.isError)
        assertEquals(VesperTimer.State.PAUSED, services.timers.timers.value.single().state)
        assertTrue(call("pause_timer").isError) // already paused
        assertFalse(call("resume_timer", JSONObject().put("timer_id", id)).isError)
        assertEquals(VesperTimer.State.RUNNING, services.timers.timers.value.single().state)
        assertTrue(call("pause_timer", JSONObject().put("label", "Nope")).isError)
    }

    @Test fun `echo controls go to system controls`() {
        call("set_do_not_disturb", JSONObject().put("on", true))
        call("set_night_mode", JSONObject().put("mode", "on"))
        call("set_camera", JSONObject().put("enabled", false))
        val st = services.system.state.value
        assertTrue(st.doNotDisturb)
        assertEquals(NightMode.ON, st.nightMode)
        assertTrue(st.cameraDisabled)
        val d = call("get_display").content
        assertTrue(d.getBoolean("do_not_disturb")); assertEquals("on", d.getString("night_mode")); assertFalse(d.getBoolean("camera_enabled"))
        call("set_camera", JSONObject().put("enabled", true))
        assertFalse(services.system.state.value.cameraDisabled)
    }

    @Test fun `screens and profiles`() {
        val nav = Nav()
        services.navigator = nav
        assertFalse(call("open_screen", JSONObject().put("screen", "now_playing")).isError)
        call("open_screen", JSONObject().put("screen", "weather"))
        call("open_screen", JSONObject().put("screen", "timers"))
        assertEquals(listOf("nowplaying", "standby", "timers"), nav.shown)
        assertTrue(call("open_screen", JSONObject().put("screen", "alarm")).isError)
        // every offered screen maps to an id
        for (k in EchoTools.SCREENS.keys) assertFalse(call("open_screen", JSONObject().put("screen", k)).isError)

        assertFalse(call("switch_profile", JSONObject().put("profile", "maya")).isError)
        assertEquals("maya", services.profiles.active.value.id)
        val bad = call("switch_profile", JSONObject().put("profile", "Zed"))
        assertTrue(bad.isError); assertTrue(bad.content.getString("message").contains("Maya"))
        call("switch_profile", JSONObject().put("profile", "everyone"))
        assertEquals("shared", services.profiles.active.value.id)
    }

    @Test fun `calendar per person`() {
        val day = wallNow - wallNow % 86_400_000L
        fun ev(id: String, who: String?) = CalendarEvent(id, id, "", day + 3_600_000L, day + 7_200_000L, color = 0, profileId = who)
        services.calendar = object : CalendarProvider {
            override val events: StateFlow<List<CalendarEvent>> = MutableStateFlow(listOf(ev("Shared", null), ev("MayaOnly", "maya"), ev("SamOnly", "sam")))
        }
        fun titles(o: JSONObject) = (0 until o.getJSONArray("events").length()).map { o.getJSONArray("events").getJSONObject(it).getString("title") }
        // nobody active: everything
        assertEquals(setOf("Shared", "MayaOnly", "SamOnly"), titles(call("get_calendar", JSONObject().put("day", "today")).content).toSet())
        services.profiles.switchTo("maya", app.vesper.core.model.SwitchReason.MANUAL)
        assertEquals(setOf("Shared", "MayaOnly"), titles(call("get_calendar", JSONObject().put("day", "today")).content).toSet())
        assertEquals(setOf("Shared", "SamOnly"), titles(call("get_calendar", JSONObject().put("day", "today").put("person", "Sam")).content).toSet())
        assertEquals(3, titles(call("get_calendar", JSONObject().put("day", "today").put("person", "everyone")).content).size)
        assertTrue(call("get_calendar", JSONObject().put("day", "today").put("person", "Zed")).isError)
    }

    private fun weather(air: AirQuality? = null, nowcast: Nowcast? = null) = Weather(
        54, 61, 47, WeatherCondition.CLOUDY, false, hourly = emptyList<HourForecast>(), location = "Home", air = air, nowcast = nowcast)

    private fun withWeather(w: Weather?) {
        services.weather = object : WeatherProvider { override val weather: StateFlow<Weather?> = MutableStateFlow(w) }
    }

    @Test fun `air quality and rain nowcast`() {
        withWeather(null)
        assertEquals("UNAVAILABLE", call("get_air_quality").content.getString("error"))
        withWeather(weather(air = AirQuality(usAqi = 72, uvIndex = 3.24f, uvIndexMax = 7.5f)))
        val a = call("get_air_quality")
        assertEquals(72, a.content.getInt("us_aqi")); assertEquals("Moderate", a.content.getString("aqi_category"))
        assertEquals(7.5, a.content.getDouble("uv_max_today"), 1e-6)
        assertTrue(a.content.has("pollen_note"))

        assertEquals("UNAVAILABLE", call("get_rain_nowcast").content.getString("error"))
        // dry now, rain in the 4th step: starts 45 min from now
        val steps = listOf(0f, 0f, 0f, 0.6f, 0.2f, 0f, 0f, 0f)
        withWeather(weather(nowcast = Nowcast(wallNow, steps)))
        val r = call("get_rain_nowcast").content
        assertEquals(45, r.getInt("minutes_until_rain")); assertFalse(r.getBoolean("raining_now"))
        withWeather(weather(nowcast = Nowcast(wallNow, List(8) { 0f })))
        assertTrue(call("get_rain_nowcast").content.isNull("minutes_until_rain"))
        withWeather(weather(nowcast = Nowcast(wallNow - 10_000_000L, steps)))
        assertEquals("STALE", call("get_rain_nowcast").content.getString("error"))
    }

    @Test fun `travel time`() {
        assertTrue(call("get_travel_time", JSONObject().put("destination", "Dentist")).isError) // NoTravelEstimator
        var departAt = 0L
        services.travel = object : TravelEstimator {
            override suspend fun minutesTo(destination: String, departAtMillis: Long): Int? { departAt = departAtMillis; return if (destination == "Dentist") 25 else null }
        }
        val r = call("get_travel_time", JSONObject().put("destination", "Dentist").put("depart_in_minutes", 30))
        assertEquals(25, r.content.getInt("minutes"))
        assertEquals(wallNow + 30 * 60_000L, departAt)
        assertEquals("25 min from home", (r.card as ResultCard.Action).state)
        assertTrue(call("get_travel_time", JSONObject().put("destination", "Nowhere")).isError)
    }

    @Test fun `phone battery`() {
        assertEquals("NOT_PAIRED", call("get_phone_battery").content.getString("error"))
        val phone = MutableStateFlow<PhoneStatus?>(PhoneStatus("Maya's iPhone", connected = true, batteryPercent = 64, charging = true))
        services.phone = phone
        val r = call("get_phone_battery")
        assertEquals(64, r.content.getInt("battery_percent")); assertTrue(r.content.getBoolean("charging"))
        phone.value = PhoneStatus("Maya's iPhone", connected = true)
        assertFalse(call("get_phone_battery").content.has("battery_percent"))
        phone.value = PhoneStatus("Maya's iPhone", connected = false, batteryPercent = 10)
        assertFalse(call("get_phone_battery").content.has("battery_percent"))
    }

    @Test fun `note posts a banner`() {
        assertTrue(call("show_note", JSONObject().put("text", "   ")).isError)
        val r = call("show_note", JSONObject().put("text", "Call the plumber").put("title", "Reminder"))
        assertFalse(r.isError)
        val b = services.notifications.banners.value.single()
        assertEquals("Reminder", b.title); assertEquals("Call the plumber", b.body)
        assertFalse(b.critical)
        assertFalse(r.content.has("warning"))
        services.systemControls.setDoNotDisturb(true)
        assertTrue(call("show_note", JSONObject().put("text", "Hi")).content.has("warning"))
    }
}
