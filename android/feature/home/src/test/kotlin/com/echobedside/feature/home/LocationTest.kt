package com.echobedside.feature.home

import com.echobedside.core.model.Weather
import com.echobedside.core.model.WeatherCondition
import com.echobedside.core.model.WeatherProvider
import com.echobedside.feature.home.glance.AutoLocation
import com.echobedside.feature.home.glance.IpLocator
import com.echobedside.feature.home.glance.Locations
import com.echobedside.feature.home.glance.Net
import com.echobedside.feature.home.glance.OpenMeteoWeatherProvider
import com.echobedside.feature.home.glance.Place
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationTest {
    private val sf = Place(37.7749, -122.4194, "San Francisco")
    private val oakland = Place(37.8044, -122.2712, "Oakland")
    private val seattle = Place(47.6062, -122.3321, "Seattle")
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private fun MemSettings.auto(p: Place, at: Long) = apply {
        putString(Locations.AUTO_LAT, p.lat.toString()); putString(Locations.AUTO_LON, p.lon.toString())
        putString(Locations.AUTO_PLACE, p.name); putString(Locations.AUTO_AT, at.toString())
    }

    @Test fun precedenceManualThenAutoThenLivermore() {
        val s = MemSettings()
        assertEquals(Locations.LIVERMORE, Locations.resolve(s, demo = false)) // works with no settings
        assertEquals(Place(37.6819, -121.7680, "Livermore"), Locations.LIVERMORE)
        assertNull(Locations.resolve(s, demo = true)) // demo keeps the sample weather
        s.auto(sf, 0)
        assertEquals(sf, Locations.resolve(s, demo = false))
        assertNull(Locations.resolve(s, demo = true))
        s.putString("weather.lat", "36.97"); s.putString("weather.lon", "-122.03"); s.putString("weather.place", "Santa Cruz")
        assertEquals(Place(36.97, -122.03, "Santa Cruz"), Locations.resolve(s, demo = false))
        assertEquals(Place(36.97, -122.03, "Santa Cruz"), Locations.resolve(s, demo = true))
        assertEquals(sf, Locations.auto(s)) // auto values live under their own keys
        assertEquals(15.0, Locations.km(sf, oakland), 3.0)
    }

    @Test fun cachesForADayAndKeepsTheCacheOnFailure() = runBlocking {
        var t = 1_000_000_000L
        var calls = 0
        var answer: Place? = sf
        val s = MemSettings()
        val auto = AutoLocation(s, { calls++; answer }, clock = { t })
        assertEquals(sf, auto.refresh())
        assertEquals(1, calls)
        t += 3_600_000
        assertEquals(sf, auto.refresh()) // fresh: no lookup
        assertEquals(1, calls)
        t += 24 * 3_600_000L
        answer = null // offline / both services down
        assertEquals(sf, auto.refresh())
        assertEquals(2, calls)
        assertEquals(sf, Locations.resolve(s, demo = false))
        t += 5 * 60_000L
        answer = oakland
        assertEquals(sf, auto.refresh(force = true)) // a forced lookup is still rate-limited (10 min)
        assertEquals(2, calls)
        t += 10 * 60_000L
        assertEquals(oakland, auto.refresh(force = true)) // network change: looks again, nearby move accepted
        assertEquals(3, calls)
    }

    @Test fun failureWithNoCacheFallsBackToLivermore() = runBlocking {
        val s = MemSettings()
        val auto = AutoLocation(s, { null }, clock = { 0L })
        assertNull(auto.refresh())
        assertEquals(Locations.LIVERMORE, Locations.resolve(s, demo = false))
        assertNull(s.map[Locations.AUTO_AT]) // nothing answered: the next refresh tries again
    }

    @Test fun aFarJumpIsHeldBackUnlessItRepeats() = runBlocking {
        var t = 0L
        var answer: Place? = seattle
        val s = MemSettings().auto(sf, 0)
        val day = 25 * 3_600_000L
        val auto = AutoLocation(s, { answer }, clock = { t })
        t += day
        assertEquals(sf, auto.refresh()) // VPN exit in Seattle: ignored once
        assertTrue(s.map.containsKey(Locations.AUTO_PENDING))
        t += day; answer = oakland
        assertEquals(oakland, auto.refresh()) // back near home: accepted, pending dropped
        assertNull(s.map[Locations.AUTO_PENDING])
        t += day; answer = seattle
        assertEquals(oakland, auto.refresh())
        t += day; answer = Place(47.61, -122.33, "Seattle")
        assertEquals("Seattle", auto.refresh()!!.name) // it repeated: really moved
        assertEquals(seattle.lat, Locations.auto(s)!!.lat, 0.01)
    }

    @Test fun ipLocatorFallsBackToTheSecondServiceAndReverseGeocodes() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/ipapi/" -> MockResponse().setBody("""{"error":true,"reason":"RateLimited"}""")
                "/ipwho/" -> MockResponse().setBody("""{"ip":"203.0.113.9","success":true,"city":"","latitude":37.68,"longitude":-121.77}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        var reversed: Pair<Double, Double>? = null
        val loc = IpLocator(Net.http, listOf(server.url("/ipapi/"), server.url("/ipwho/")), reverse = { lat, lon -> reversed = lat to lon; "Livermore" })
        assertEquals(Place(37.68, -121.77, "Livermore"), loc.lookup())
        assertEquals(37.68 to -121.77, reversed)
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/ipapi/", first.path) // a bare GET: no query, nothing identifying
        assertEquals(Net.USER_AGENT, first.getHeader("User-Agent"))
        assertEquals("/ipwho/", server.takeRequest(1, TimeUnit.SECONDS)!!.path)

        val none = IpLocator(Net.http, listOf(server.url("/down/")))
        assertNull(none.lookup())
        assertEquals(Place(37.6819, -121.768, "Livermore"),
            IpLocator.parse(JSONObject("""{"city":"Livermore","latitude":37.6819,"longitude":-121.768}""")))
        assertNull(IpLocator.parse(JSONObject("""{"success":false,"message":"Invalid IP address"}""")))
        assertNull(IpLocator.parse(JSONObject("""{"latitude":0,"longitude":0}""")))
    }

    private class Fake(w: Weather?) : WeatherProvider { override val weather = MutableStateFlow(w) }

    @Test fun releaseWeatherWorksWithNoSettingsAndFollowsTheAutoLocation() = runBlocking {
        val lats = java.util.concurrent.LinkedBlockingQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                lats.add(request.requestUrl!!.queryParameter("latitude")!!)
                return MockResponse().setResponseCode(503) // the location is what's under test
            }
        }
        val s = MemSettings()
        var answer: Place? = null
        var t = 0L
        val auto = AutoLocation(s, { answer }, clock = { t })
        val net = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val p = OpenMeteoWeatherProvider(s, scope, Fake(null), demo = false, auto = auto, networkChanges = net,
            client = Net.http, base = server.url("/v1/forecast"), retryMs = 60_000)
        p.start()
        assertEquals("37.682", lats.poll(5, TimeUnit.SECONDS)) // lookup failed, no cache: Livermore
        kotlinx.coroutines.delay(200)
        t += 11 * 60_000L; answer = sf
        net.emit(Unit) // Wi-Fi changed → look up again → SF → weather restarts for SF
        assertEquals("37.775", lats.poll(5, TimeUnit.SECONDS))
        s.putString("weather.lat", "36.97"); s.putString("weather.lon", "-122.03")
        assertEquals("36.970", lats.poll(5, TimeUnit.SECONDS)) // manual wins
        assertNull(lats.poll(1, TimeUnit.SECONDS)) // and nothing fetched twice
    }
}
