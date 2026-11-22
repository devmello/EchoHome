package com.echobedside.feature.home

import com.echobedside.core.model.Weather
import com.echobedside.core.model.WeatherCondition
import com.echobedside.core.model.WeatherProvider
import com.echobedside.feature.home.glance.IcsCalendarProvider
import com.echobedside.feature.home.glance.Net
import com.echobedside.feature.home.glance.OpenMeteo
import com.echobedside.feature.home.glance.OpenMeteoWeatherProvider
import com.echobedside.feature.home.glance.Place
import com.echobedside.feature.home.glance.WeatherSettings
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 22)
    private val now = today.atTime(8, 0).atZone(la).toInstant().toEpochMilli()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private fun sec(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(la).toEpochSecond()

    /** A real-shaped Open-Meteo response: 48 hours from midnight, rain tomorrow from 9 AM. */
    private fun forecast(): JSONObject {
        val time = JSONArray(); val temp = JSONArray(); val code = JSONArray(); val rain = JSONArray()
        for (i in 0 until 48) {
            val d = today.plusDays((i / 24).toLong())
            time.put(sec(d, i % 24)); temp.put(50 + i % 24 / 2.0)
            val wet = i >= 24 + 9 && i <= 24 + 14
            code.put(if (wet) 61 else 2); rain.put(if (wet) 70 else 5)
        }
        val days = JSONArray().put(sec(today, 0)).put(sec(today.plusDays(1), 0)).put(sec(today.plusDays(2), 0))
        return JSONObject().put("timezone", "America/Los_Angeles")
            .put("current", JSONObject().put("temperature_2m", 54.4).put("weather_code", 3).put("is_day", 1))
            .put("hourly", JSONObject().put("time", time).put("temperature_2m", temp).put("weather_code", code).put("precipitation_probability", rain))
            .put("daily", JSONObject().put("time", days).put("weather_code", JSONArray("[2,61,0]"))
                .put("temperature_2m_max", JSONArray("[61.2,57,60]")).put("temperature_2m_min", JSONArray("[47.6,49,46]"))
                .put("sunrise", JSONArray().put(sec(today, 7)).put(sec(today.plusDays(1), 7)).put(sec(today.plusDays(2), 7)))
                .put("sunset", JSONArray().put(sec(today, 17)).put(sec(today.plusDays(1), 17)).put(sec(today.plusDays(2), 17)))
                .put("precipitation_probability_max", JSONArray("[5,80,0]")))
    }

    @Test fun parsesOpenMeteo() {
        val w = OpenMeteo.parse(forecast(), Place(36.97, -122.03, "Santa Cruz"), fahrenheit = true, now = now)
        assertEquals(54, w.temperature)
        assertEquals(61, w.high); assertEquals(48, w.low)
        assertEquals(WeatherCondition.CLOUDY, w.condition)
        assertFalse(w.isNight)
        assertEquals("°F", w.unit)
        assertEquals("Santa Cruz", w.location)
        assertEquals(24, w.hourly.size) // from the current hour, 24 steps
        assertEquals(sec(today, 8) * 1000, w.hourly.first().timeMillis) // starts at the current hour
        assertEquals(sec(today, 17) * 1000, w.sunsetMillis)
        assertEquals(sec(today.plusDays(1), 7) * 1000, w.sunriseMillis) // today's sunrise is past: the next one
        assertEquals("Rain tomorrow from 9 AM", w.tomorrowSummary)
    }

    @Test fun conditionsAndUnits() {
        assertEquals(WeatherCondition.CLEAR, OpenMeteo.condition(0))
        assertEquals(WeatherCondition.FOG, OpenMeteo.condition(45))
        assertEquals(WeatherCondition.POURING, OpenMeteo.condition(65))
        assertEquals(WeatherCondition.SNOW, OpenMeteo.condition(73))
        assertEquals(WeatherCondition.LIGHTNING, OpenMeteo.condition(95))
        assertTrue(WeatherSettings.fahrenheit(MemSettings(), Locale.US))
        assertFalse(WeatherSettings.fahrenheit(MemSettings(), Locale.UK))
        assertTrue(WeatherSettings.fahrenheit(MemSettings("weather.unit" to "F"), Locale.GERMANY))
        assertNull(WeatherSettings.place(MemSettings("weather.lat" to "36.9")))
        assertEquals(Place(36.97, -122.03, "Santa Cruz"),
            WeatherSettings.place(MemSettings("weather.lat" to "36.97", "weather.lon" to "-122.03", "weather.place" to "Santa Cruz")))
        assertEquals(-122.03, WeatherSettings.number(MemSettings("k" to -122.03f), "k")!!, 0.001) // a float setting works too
        val url = OpenMeteo.url(Place(36.97412, -122.0308, "x"), false)
        assertEquals("36.974", url.queryParameter("latitude"))
        assertEquals("celsius", url.queryParameter("temperature_unit"))
        assertEquals("unixtime", url.queryParameter("timeformat"))
    }

    private class Fake(w: Weather?) : WeatherProvider { override val weather = MutableStateFlow(w) }

    @Test fun providerMirrorsFakeUntilALocationIsSetThenFetches() = runBlocking {
        val body = forecast().toString()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody(body)
        }
        val settings = MemSettings("weather.unit" to "F")
        val sample = Weather(70, 72, 50, WeatherCondition.CLEAR, false, location = "Sample")
        val p = OpenMeteoWeatherProvider(settings, scope, Fake(sample), Net.http, server.url("/v1/forecast"))
        p.start()
        withTimeout(2000) { p.weather.first { it?.location == "Sample" } }
        assertEquals(0, server.requestCount)
        kotlinx.coroutines.delay(200) // the settings listener is up

        settings.putString("weather.lat", "36.97"); settings.putString("weather.lon", "-122.03"); settings.putString("weather.place", "Santa Cruz")
        val w = withTimeout(5000) { p.weather.first { it?.location == "Santa Cruz" } }!!
        assertEquals("°F", w.unit)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("36.970", req.requestUrl!!.queryParameter("latitude"))
        assertEquals(Net.USER_AGENT, req.getHeader("User-Agent"))
        assertEquals(1, server.requestCount) // three settings changes → one fetch
    }

    private fun ics(summary: String) = listOf("BEGIN:VCALENDAR", "X-WR-CALNAME:Work", "BEGIN:VEVENT", "UID:a",
        "DTSTART;TZID=America/Los_Angeles:20261122T093000", "DTEND;TZID=America/Los_Angeles:20261122T100000",
        "SUMMARY:$summary", "LOCATION:Downtown\\, 120 Pacific Ave", "END:VEVENT", "END:VCALENDAR").joinToString("\r\n")

    @Test fun calendarFetchesCachesAndRevalidates() = runBlocking {
        server.enqueue(MockResponse().setBody(ics("Standup")).setHeader("ETag", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        val dir = Files.createTempDirectory("ics").toFile()
        val url = server.url("/cal.ics").toString()
        val settings = MemSettings("calendar.color.0" to "#FF375F")
        val p = IcsCalendarProvider(listOf(url), dir, settings, scope, Net.http, zone = { la }, clock = { now })
        p.start()
        val ev = withTimeout(5000) { p.events.first { it.isNotEmpty() } }.single()
        assertEquals("Standup", ev.title)
        assertEquals("Downtown", ev.subtitle)
        assertEquals(0xFFFF375F.toInt(), ev.color)
        assertEquals(today.atTime(9, 30).atZone(la).toInstant().toEpochMilli(), ev.startMillis)
        assertTrue(File(dir, "ics").listFiles()!!.any { it.name.endsWith(".ics") })

        p.refresh() // second fetch sends the ETag and gets 304: the cached copy stays
        server.takeRequest(1, TimeUnit.SECONDS)
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("\"v1\"", second.getHeader("If-None-Match"))

        // A fresh provider (e.g. after a reboot, offline) reads the cache first.
        val offline = IcsCalendarProvider(listOf(url), dir, settings, CoroutineScope(SupervisorJob() + Dispatchers.IO), Net.http, zone = { la }, clock = { now })
        server.shutdown()
        offline.start()
        assertEquals("Standup", withTimeout(5000) { offline.events.first { it.isNotEmpty() } }.single().title)
        dir.deleteRecursively()
        Unit
    }

    @Test fun calendarHelpers() {
        val p = IcsCalendarProvider(listOf("x"), File("."), MemSettings(), CoroutineScope(Job()))
        assertEquals("https://p01-calendars.example.com/a.ics", p.httpUrl("webcal://p01-calendars.example.com/a.ics"))
        assertEquals(0xFF30D158.toInt(), IcsCalendarProvider.parseColor("30d158"))
        assertNull(IcsCalendarProvider.parseColor("red"))
    }
}
