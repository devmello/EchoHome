package app.vesper.feature.home

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.CalendarProvider
import app.vesper.core.model.DefaultIslandManager
import app.vesper.core.model.HomeLocation
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Nowcast
import app.vesper.core.model.Profile
import app.vesper.core.model.TravelEstimator
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.feature.home.alerts.AlertSettings
import app.vesper.feature.home.alerts.LeaveNowWatcher
import app.vesper.feature.home.alerts.LeaveRule
import app.vesper.feature.home.alerts.RainAlertWatcher
import app.vesper.feature.home.alerts.RainRule
import app.vesper.feature.home.commute.Geocoder
import app.vesper.feature.home.commute.HomeTravelEstimator
import app.vesper.feature.home.commute.Router
import app.vesper.feature.home.glance.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private val min = 60_000L
    private val now = 1_795_400_000_000L

    /** Dry now, then [wetFromMin] minutes later one wet step of [mm]. Steps are 15 min; step 0 starts at `now`. */
    private fun nowcast(wetFromMin: Int?, mm: Float = 1f, rainingNow: Boolean = false): Nowcast {
        val steps = FloatArray(9)
        if (rainingNow) steps[0] = mm
        if (wetFromMin != null) steps[wetFromMin / 15] = mm
        return Nowcast(now, steps.toList(), 15)
    }

    // ---- rain rule ----

    @Test fun rainSoonPublishesWithinThirtyMinutesOnlyWhenDryNow() {
        val d = RainRule.decide(nowcast(15), now, true, false, 0)
        assertEquals(RainRule.Decision.Publish(15, false), d)
        assertEquals(RainRule.Decision.None, RainRule.decide(nowcast(45), now, true, false, 0)) // too far off
        assertEquals(RainRule.Decision.None, RainRule.decide(nowcast(15, rainingNow = true), now, true, false, 0)) // already raining
        assertEquals(RainRule.Decision.None, RainRule.decide(nowcast(null), now, true, false, 0)) // dry
        assertEquals(RainRule.Decision.None, RainRule.decide(null, now, true, false, 0)) // no data
        assertEquals(RainRule.Decision.None, RainRule.decide(nowcast(15), now, false, false, 0)) // setting off
        assertEquals(RainRule.Decision.Publish(30, false), RainRule.decide(nowcast(30), now, true, false, 0)) // the edge counts
    }

    @Test fun heavyRainIsFlagged() {
        assertEquals(RainRule.Decision.Publish(15, true), RainRule.decide(nowcast(15, mm = 3.2f), now, true, false, 0))
        assertEquals(RainRule.Decision.Publish(15, false), RainRule.decide(nowcast(15, mm = 2.4f), now, true, false, 0))
    }

    @Test fun neverRepeatsWithinTwoHours() {
        val last = now - 90 * min
        assertEquals(RainRule.Decision.None, RainRule.decide(nowcast(15), now, true, false, last))
        assertTrue(RainRule.decide(nowcast(15), now, true, false, now - 121 * min) is RainRule.Decision.Publish)
    }

    @Test fun activeAlertUpdatesThenEndsWhenRainStartsOrTheForecastDropsIt() {
        assertEquals(RainRule.Decision.Update(15, false), RainRule.decide(nowcast(15), now, true, true, now - 5 * min))
        // A shifting forecast (35 min) is kept; 45 min drops it.
        assertEquals(RainRule.Decision.Update(30, false), RainRule.decide(nowcast(30), now, true, true, now - 5 * min))
        assertEquals(RainRule.Decision.End, RainRule.decide(nowcast(45), now, true, true, now - 5 * min))
        assertEquals(RainRule.Decision.End, RainRule.decide(nowcast(15, rainingNow = true), now, true, true, now - 5 * min)) // started
        assertEquals(RainRule.Decision.End, RainRule.decide(nowcast(null), now, true, true, now - 5 * min)) // forecast dropped it
        assertEquals(RainRule.Decision.End, RainRule.decide(nowcast(15), now, false, true, now - 5 * min)) // switched off
    }

    @Test fun watcherPublishesUpdatesAndEndsOnTheIsland() = runBlocking {
        var t = now
        val island = DefaultIslandManager()
        val settings = MemSettings()
        val weather = MutableStateFlow<Weather?>(Weather(50, 55, 45, WeatherCondition.CLOUDY, false, nowcast = Nowcast(now + 20 * min, listOf(1f, 1f))))
        val w = RainAlertWatcher(settings, weather, island, scope, clock = { t }, elapsed = { 1000L })
        w.start()
        val a = withTimeout(3000) { while (island.activities.value.isEmpty()) kotlinx.coroutines.delay(20); island.activities.value.single() }
        assertEquals(ActivityKind.WEATHER_ALERT, a.kind)
        assertEquals(LiveContent.RainSoon(20, false), a.content)
        assertTrue(AlertSettings.long(settings, AlertSettings.RAIN_LAST) == now) // remembered for the cool-down

        t += 5 * min; w.evaluate()
        assertEquals(LiveContent.RainSoon(15, false), island.activities.value.single().content)

        t += 16 * min; w.evaluate() // the step it was waiting for has started: raining
        assertTrue(island.activities.value.isEmpty())

        // Dry again, rain forecast again 40 min later: still inside the 2 h cool-down → nothing.
        weather.value = weather.value!!.copy(nowcast = Nowcast(t, FloatArray(9).also { it[1] = 1f }.toList(), 15))
        t += 1 * min; w.evaluate()
        assertTrue(island.activities.value.isEmpty())
        // After 2 h it's allowed again.
        t += 125 * min
        weather.value = weather.value!!.copy(nowcast = Nowcast(t, FloatArray(9).also { it[1] = 1f }.toList(), 15))
        w.evaluate()
        assertEquals(1, island.activities.value.size)
    }

    // ---- leave now ----

    private fun event(id: String = "e", start: Long, loc: String? = "Downtown, 120 Pacific Ave", allDay: Boolean = false, profile: String? = null) =
        CalendarEvent(id, "Dentist", "Downtown", start, start + 30 * min, allDay, 0, loc, profile)

    @Test fun leaveTimeIsStartMinusTravelMinusFiveMinutes() {
        val start = now + 120 * min
        assertEquals(start - 25 * min, LeaveRule.leaveAt(start, 20))
        assertEquals(start - 30 * min, LeaveRule.leaveAt(start, 20, bufferMin = 10))
    }

    @Test fun phaseShowsFifteenMinutesBeforeAndEndsTenMinutesAfterTheLeaveTimeOrAtTheStart() {
        val start = now + 120 * min
        val leave = LeaveRule.leaveAt(start, 20) // start − 25
        fun ph(at: Long) = LeaveRule.phase(at, start, leave, 15)
        assertEquals(LeaveRule.Phase.LATER, ph(leave - 16 * min))
        assertEquals(LeaveRule.Phase.SHOW, ph(leave - 15 * min))
        assertEquals(LeaveRule.Phase.SHOW, ph(leave + 9 * min))
        assertEquals(LeaveRule.Phase.DONE, ph(leave + 10 * min))
        // A short trip: the start comes before leave + 10 min.
        val short = LeaveRule.leaveAt(start, 2) // start − 7
        assertEquals(LeaveRule.Phase.SHOW, LeaveRule.phase(start - 1, start, short, 15))
        assertEquals(LeaveRule.Phase.DONE, LeaveRule.phase(start, start, short, 15))
        // A different lead.
        assertEquals(LeaveRule.Phase.SHOW, LeaveRule.phase(leave - 30 * min, start, leave, 30))
    }

    @Test fun candidatesAreTimedPhysicalUpcomingAndForThePersonOrShared() {
        val maya = Profile("maya", "Maya", 0)
        val list = listOf(
            event("ok", now + 60 * min),
            event("allday", now + 60 * min, allDay = true),
            event("noloc", now + 60 * min, loc = null),
            event("zoom", now + 60 * min, loc = "https://zoom.us/j/1"),
            event("past", now - 5 * min),
            event("far", now + 7 * 60 * min),
            event("sam", now + 90 * min, profile = "sam"),
            event("mine", now + 95 * min, profile = "maya"),
        )
        assertEquals(listOf("ok", "mine"), LeaveRule.candidates(list, now, maya).map { it.id })
        // Nobody recognised (shared) or no profile: everyone's events.
        assertEquals(listOf("ok", "sam", "mine"), LeaveRule.candidates(list, now, Profile.SHARED).map { it.id })
        assertEquals(listOf("ok", "sam", "mine"), LeaveRule.candidates(list, now, null).map { it.id })
    }

    @Test fun pickTakesTheEarliestLeaveAndUntilNextFindsTheNextWindow() {
        val a = LeaveRule.Plan(event("a", now + 60 * min), 20, now + 35 * min)
        val b = LeaveRule.Plan(event("b", now + 45 * min), 30, now + 10 * min)
        assertNull(LeaveRule.pick(listOf(a), now, 15)) // a's window opens at +20
        assertEquals("b", LeaveRule.pick(listOf(a, b), now, 15)!!.event.id)
        assertEquals(20 * min, LeaveRule.untilNext(listOf(a), now, 15))
        assertNull(LeaveRule.untilNext(listOf(b), now, 15)) // b is already showing
        assertEquals("Downtown", a.destination)
    }

    private class FakeCal(e: List<CalendarEvent>) : CalendarProvider { val flow = MutableStateFlow(e); override val events = flow }
    private class Travel(var minutes: Int?) : TravelEstimator { override suspend fun minutesTo(destination: String, departAtMillis: Long) = minutes }

    @Test fun leaveNowWatcherPublishesUpdatesAndEndsOnTheIsland() = runBlocking {
        var t = now
        val start = now + 60 * min // 20 min drive + 5 → leave at +35 → shows from +20
        val cal = FakeCal(listOf(event("e", start)))
        val travel = Travel(20)
        val island = DefaultIslandManager()
        val w = LeaveNowWatcher(MemSettings(), { cal }, { travel }, { null }, island, scope, clock = { t }, elapsed = { 5_000L })
        w.start()
        kotlinx.coroutines.delay(300)
        assertTrue(island.activities.value.isEmpty()) // too early

        t = now + 22 * min
        cal.flow.value = listOf(event("e", start)) // a calendar refresh re-evaluates
        cal.flow.value = listOf(event("e", start).copy(title = "Dentist!"))
        val a = withTimeout(3000) { while (island.activities.value.isEmpty()) kotlinx.coroutines.delay(20); island.activities.value.single() }
        assertEquals(ActivityKind.LEAVE_NOW, a.kind)
        val c = a.content as LiveContent.LeaveNow
        assertEquals(start - 25 * min, c.leaveAtMillis)
        assertEquals(20, c.travelMinutes)
        assertEquals("Downtown", c.destination)

        // Travel gets longer → the same activity is updated.
        travel.minutes = 30
        cal.flow.value = listOf(event("e", start).copy(subtitle = "x"))
        withTimeout(3000) { while ((island.activities.value.single().content as LiveContent.LeaveNow).travelMinutes != 30) kotlinx.coroutines.delay(20) }
        assertEquals(start - 35 * min, (island.activities.value.single().content as LiveContent.LeaveNow).leaveAtMillis)

        // 10 minutes after the leave time it ends.
        t = start - 35 * min + 11 * min
        cal.flow.value = listOf(event("e", start).copy(subtitle = "y"))
        withTimeout(3000) { while (island.activities.value.isNotEmpty()) kotlinx.coroutines.delay(20) }
    }

    @Test fun leaveNowIsOffWhenDisabled() = runBlocking {
        val island = DefaultIslandManager()
        val cal = FakeCal(listOf(event("e", now + 40 * min)))
        val w = LeaveNowWatcher(MemSettings(AlertSettings.LEAVE to false), { cal }, { Travel(20) }, { null }, island, scope, clock = { now })
        w.start()
        kotlinx.coroutines.delay(400)
        assertTrue(island.activities.value.isEmpty())
    }

    // ---- travel estimator ----

    private fun estimator(clock: () -> Long = { now }): HomeTravelEstimator {
        val settings = MemSettings()
        val loc = MutableStateFlow(HomeLocation("Home", 37.77, -122.42, HomeLocation.Source.MANUAL))
        return HomeTravelEstimator(settings, loc,
            geocoder = Geocoder(settings, Net.http, server.url("/search"), clock = { System.currentTimeMillis() }),
            router = Router(Net.http, server.url("/route/v1/driving/")), clock = clock, gapMs = 0)
    }

    private val osrm = """{"code":"Ok","routes":[{"duration":1380.4,"distance":21000,"legs":[{"steps":[]}],"geometry":{"coordinates":[[-122.42,37.77],[-121.9,37.7]]}}]}"""

    @Test fun travelGeocodesRoutesAndCaches() = runBlocking {
        val counts = HashMap<String, Int>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val k = path.split('/')[1]; counts[k] = (counts[k] ?: 0) + 1
                return if (path.startsWith("/search")) MockResponse().setBody("""[{"lat":"37.70","lon":"-121.90"}]""") else MockResponse().setBody(osrm)
            }
        }
        val e = estimator()
        assertEquals(23, e.minutesTo("Downtown Oakland", now))
        assertEquals(23, e.minutesTo("downtown   oakland", now + min)) // same place: cached
        assertEquals(1, counts["search"]); assertEquals(1, counts["route"])
        assertEquals(2, server.requestCount)
        assertNull(e.minutesTo("https://zoom.us/j/123", now)) // nothing to drive to
        assertNull(e.minutesTo("   ", now))
        assertEquals(2, server.requestCount)
    }

    @Test fun travelFailureIsNullAndNotRetriedImmediately() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        var t = now
        val e = estimator { t }
        assertNull(e.minutesTo("Somewhere", now))
        val n = server.requestCount
        assertNull(e.minutesTo("Somewhere", now)) // cached failure, no new request
        assertEquals(n, server.requestCount)
        t += 6 * min // after the 5 min failure cache it tries again
        assertNull(e.minutesTo("Somewhere", t))
        assertTrue(server.requestCount > n)
    }

    @Test fun travelUnknownPlaceIsNull() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]") // Nominatim: no match
        }
        val e = estimator()
        assertNull(e.minutesTo("Nowhere Land", now))
        assertEquals(1, server.requestCount)
        assertNull(e.minutesTo("Nowhere Land", now)) // the miss is cached
        assertEquals(1, server.requestCount)
    }
}
