package app.vesper.feature.home

import app.vesper.core.model.HomeLocation
import app.vesper.core.model.Nowcast
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.WeatherProvider
import app.vesper.feature.home.glance.Net
import app.vesper.feature.home.glance.OpenMeteo
import app.vesper.feature.home.glance.OpenMeteoAir
import app.vesper.feature.home.glance.OpenMeteoWeatherProvider
import app.vesper.feature.home.glance.Place
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Open-Meteo nowcast + air quality: parsing (real response shapes) and the provider. */
class WeatherExtrasTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    // Real response (San Francisco, Nov 2026), trimmed: minutely_15 precipitation, 8 steps.
    private val nowcastJson = """{"latitude":37.770510,"longitude":-122.42710,"timezone":"America/Los_Angeles",
        "minutely_15_units":{"time":"unixtime","precipitation":"mm"},
        "minutely_15":{"time":[1795402800,1795403700,1795404600,1795405500,1795406400,1795407300,1795408200,1795409100],
        "precipitation":[0.00,0.00,0.00,0.40,0.00,3.10,null,0.00]}}"""

    // Real air-quality response (no pollen outside Europe: the values are null).
    private val airUs = """{"latitude":37.700005,"longitude":-121.8,"current_units":{"time":"unixtime","us_aqi":"USAQI","uv_index":"","grass_pollen":"grains/m³"},
        "current":{"time":1795402800,"interval":3600,"us_aqi":30,"uv_index":0.00,"grass_pollen":null,"birch_pollen":null,"alder_pollen":null,"olive_pollen":null,"mugwort_pollen":null,"ragweed_pollen":null},
        "daily_units":{"time":"unixtime","uv_index_max":""},"daily":{"time":[1795334400],"uv_index_max":[1.15]}}"""

    private val airEu = """{"current":{"time":1795402800,"us_aqi":57,"uv_index":6.4,"grass_pollen":35.5,"birch_pollen":0.0,"alder_pollen":null,"olive_pollen":4.0,"mugwort_pollen":null,"ragweed_pollen":null},
        "daily":{"time":[1795334400],"uv_index_max":[7.2]}}"""

    @Test fun parsesTheNowcast() {
        val n = OpenMeteo.parseNowcast(JSONObject(nowcastJson))!!
        assertEquals(8, n.precipMm.size)
        assertEquals(15, n.stepMinutes)
        // Open-Meteo's value at a time is the sum of the 15 minutes before it, so step 0 starts one step earlier.
        assertEquals(1795402800_000L - 15 * 60_000L, n.startMillis)
        assertEquals(0.4f, n.precipMm[3], 0.001f)
        assertEquals(0f, n.precipMm[6], 0f) // null = dry
        // At the first timestamp: 0.4 mm lands in the step ending at +45 min, which started at +30.
        val now = 1795402800_000L
        assertEquals(30, n.minutesUntilRain(now))
        assertNull(OpenMeteo.parseNowcast(JSONObject("""{"current":{}}""")))
        assertNull(OpenMeteo.parseNowcast(JSONObject("""{"minutely_15":{"time":[],"precipitation":[]}}""")))
    }

    @Test fun parsesAirQualityWithoutPollenOutsideEurope() {
        val a = OpenMeteoAir.parse(JSONObject(airUs), now = 5L)!!
        assertEquals(30, a.usAqi)
        assertEquals("Good", a.aqiCategory)
        assertEquals(0f, a.uvIndex!!, 0f)
        assertEquals(1.15f, a.uvIndexMax!!, 0.001f)
        assertTrue(a.pollen.isEmpty()) // CAMS Europe only
        assertEquals(5L, a.updatedAtMillis)
    }

    @Test fun parsesPollenInEurope() {
        val a = OpenMeteoAir.parse(JSONObject(airEu))!!
        assertEquals(57, a.usAqi); assertEquals("Moderate", a.aqiCategory)
        assertEquals(setOf("grass", "birch", "olive"), a.pollen.keys) // nulls left out
        assertEquals(35.5f, a.pollen["grass"]!!, 0.001f)
        assertEquals(7.2f, a.uvIndexMax!!, 0.001f)
        // Nothing usable at all → null (not an AirQuality full of nulls).
        assertNull(OpenMeteoAir.parse(JSONObject("""{"current":{"us_aqi":null,"uv_index":null}}""")))
        assertNull(OpenMeteoAir.parse(JSONObject("""{"error":true,"reason":"nope"}""")))
    }

    @Test fun urlsUseOpenMeteosParameterNames() {
        val place = Place(37.77490, -122.4194, "x")
        val air = OpenMeteoAir.url(place)
        assertEquals("air-quality-api.open-meteo.com", air.host)
        assertEquals("us_aqi,uv_index,grass_pollen,birch_pollen,alder_pollen,olive_pollen,mugwort_pollen,ragweed_pollen", air.queryParameter("current"))
        assertEquals("uv_index_max", air.queryParameter("daily"))
        assertEquals("37.775", air.queryParameter("latitude"))
        val f = OpenMeteo.url(place, false)
        assertEquals("precipitation", f.queryParameter("minutely_15"))
        assertEquals("9", f.queryParameter("forecast_minutely_15"))
        val n = OpenMeteo.nowcastUrl(place)
        assertEquals("precipitation", n.queryParameter("minutely_15"))
        assertNull(n.queryParameter("hourly")) // just the small request
    }

    private class Fake(w: Weather?) : WeatherProvider { override val weather = MutableStateFlow(w) }

    private fun forecastBody(nowSec: Long): String {
        // One hour, enough for the parser: reuse GlanceTest's shape minimally.
        val t = nowSec - nowSec % 3600
        val times = (0 until 49).joinToString(",") { (t + it * 3600).toString() }
        val nums = (0 until 49).joinToString(",") { "50" }
        val zeros = (0 until 49).joinToString(",") { "0" }
        return """{"timezone":"UTC","current":{"temperature_2m":54.4,"weather_code":3,"is_day":1},
            "hourly":{"time":[$times],"temperature_2m":[$nums],"weather_code":[$zeros],"precipitation_probability":[$zeros]},
            "daily":{"time":[${t - t % 86400}],"weather_code":[0],"temperature_2m_max":[60],"temperature_2m_min":[40],"sunrise":[${t + 3600}],"sunset":[${t + 7200}],"precipitation_probability_max":[0]},
            "minutely_15":{"time":[$nowSec,${nowSec + 900}],"precipitation":[0.0,1.2]}}"""
    }

    @Test fun providerFetchesAirAndNowcastAndKeepsAirWhenItFails() = runBlocking {
        val nowSec = System.currentTimeMillis() / 1000
        var airOk = true
        val paths = java.util.concurrent.LinkedBlockingQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths.add(path + "?" + (request.requestUrl!!.queryParameter("hourly") != null))
                return when {
                    path.endsWith("/air-quality") -> if (airOk) MockResponse().setBody(airEu) else MockResponse().setResponseCode(500)
                    else -> MockResponse().setBody(forecastBody(nowSec))
                }
            }
        }
        val loc = MutableStateFlow(HomeLocation("San Francisco", 37.77, -122.42, HomeLocation.Source.MANUAL))
        var t = System.currentTimeMillis()
        val p = OpenMeteoWeatherProvider(MemSettings("weather.unit" to "F"), scope, Fake(null), loc, client = Net.http,
            base = server.url("/v1/forecast"), airBase = server.url("/v1/air-quality"), periodMs = 400, retryMs = 400, nowcastMs = 150, clock = { t })
        p.start()
        scope.launch { p.weather.collect { } } // somebody reads the weather: the nowcast loop runs
        val w = withTimeout(5000) { p.weather.first { it?.air != null } }!!
        assertEquals(57, w.air!!.usAqi)
        assertNotNull(w.nowcast)
        assertEquals(2, w.nowcast!!.precipMm.size)
        // The next forecast fetch has an air failure: the previous air stays.
        airOk = false
        kotlinx.coroutines.delay(1100)
        assertEquals(57, p.weather.value!!.air!!.usAqi)
        // The nowcast loop made its own small requests (no hourly parameter) while somebody collected.
        val small = generateSequence { paths.poll() }.count { it.endsWith("false") && !it.contains("air-quality") }
        assertTrue("nowcast refreshes expected, got $small", small >= 1)
    }
}
