package com.echobedside.feature.home

import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.HourForecast
import com.echobedside.core.model.WeatherCondition
import com.echobedside.feature.home.commute.CommuteMath
import com.echobedside.feature.home.commute.Geocoder
import com.echobedside.feature.home.commute.LatLon
import com.echobedside.feature.home.commute.Route
import com.echobedside.feature.home.commute.Router
import com.echobedside.feature.home.glance.Net
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommuteTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val mon = LocalDate.of(2026, 11, 23)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(la).toInstant().toEpochMilli()
    private val server = MockWebServer()
    @After fun tearDown() = server.shutdown()

    private fun event(title: String, start: Long, location: String?, allDay: Boolean = false) =
        CalendarEvent(title, title, "", start, start + 3_600_000, allDay, 0, location)

    @Test fun leaveTimeAndHeadline() {
        val now = at(mon, 7, 0)
        // Arrive by 7:47 with a 5 min buffer and 24 min drive → leave at 7:18 = in 18 min (design).
        assertEquals(18, CommuteMath.leaveInMin(now, at(mon, 7, 47), 24, 5))
        assertEquals(17, CommuteMath.leaveInMin(now + 30_000, at(mon, 7, 47), 24, 5)) // floors
        assertEquals(now + 42 * 60_000L, CommuteMath.arrival(now, 18, 24))
        assertEquals(now + 24 * 60_000L, CommuteMath.arrival(now, -3, 24)) // late: leave now
        val ui = CommuteMath.demo(now)
        assertEquals("Leave in" to "18 min", ui.headline(now, la))
        assertEquals("Leave" to "now", ui.copy(leaveInMin = 0).headline(now, la))
        assertEquals("Leave in" to "1 h 30 min", ui.copy(leaveInMin = 90).headline(now, la))
        assertEquals("Leave at" to "10:00 AM", ui.copy(leaveInMin = 180).headline(now, la))
    }

    @Test fun nextEventTripSkipsAllDayVirtualAndPast() {
        val now = at(mon, 7)
        val events = listOf(
            event("Holiday", at(mon, 0), "Somewhere", allDay = true),
            event("Earlier", at(mon, 6), "Downtown"),
            event("Standup", at(mon, 8), "https://meet.google.com/abc"),
            event("Zoom thing", at(mon, 8, 30), "Zoom"),
            event("Dentist", at(mon, 9, 15), "1515 Soquel Dr, Santa Cruz"),
            event("Lunch", at(mon, 12), "Pacific Ave"),
            event("Far", at(mon, 18), "San Jose"),
        )
        val t = CommuteMath.nextEventTrip(events, now, 4)!!
        assertEquals("TO DENTIST", t.label)
        assertEquals("1515 Soquel Dr, Santa Cruz", t.destination)
        assertEquals(at(mon, 9, 15), t.arriveBy)
        assertNull(CommuteMath.nextEventTrip(events.filter { it.title == "Far" }, now, 4)) // more than 4 h out
        assertTrue(CommuteMath.looksVirtual("Teams meeting"))
        assertFalse(CommuteMath.looksVirtual("Phoenix Coffee"))
    }

    @Test fun defaultTripIsWeekdays() {
        val fri = LocalDate.of(2026, 11, 27)
        val t = CommuteMath.defaultTrip("Work", "Scotts Valley", "09:00", at(fri, 10), la)!!
        assertEquals(at(LocalDate.of(2026, 11, 30), 9), t.arriveBy) // Friday after 9 → Monday
        assertEquals("TO WORK", t.label)
        assertEquals(at(fri, 9), CommuteMath.defaultTrip("Work", "x", "09:00", at(fri, 7), la)!!.arriveBy)
        assertNull(CommuteMath.defaultTrip("Work", null, "09:00", at(fri, 7), la))
        assertNull(CommuteMath.defaultTrip("Work", "x", "nine", at(fri, 7), la))
    }

    @Test fun rainChip() {
        val now = at(mon, 7)
        val dry = (0..12).map { HourForecast(at(mon, 7 + it), 50, WeatherCondition.CLOUDY, 10) }
        assertNull(CommuteMath.rainChip(dry, now, la))
        val wet = dry.map { if (it.timeMillis == at(mon, 8)) it.copy(condition = WeatherCondition.RAIN, precipChance = 60) else it }
        assertEquals("Rain from 8 AM", CommuteMath.rainChip(wet, now, la))
        assertEquals("Rain now", CommuteMath.rainChip(dry.map { it.copy(precipChance = 80) }, now, la))
    }

    @Test fun buildHasNoTrafficChipAndFitsTheRoute() {
        val now = at(mon, 7)
        val trip = CommuteMath.defaultTrip("Work", "Scotts Valley", "07:47", now, la)!!
        val main = Route(24, 31.2, "Hwy 17", listOf(LatLon(36.97, -122.03), LatLon(37.05, -122.01)))
        val alt = Route(31, 27.9, "Soquel Dr", listOf(LatLon(36.97, -122.03), LatLon(37.0, -121.98), LatLon(37.05, -122.01)))
        val ui = CommuteMath.build(trip, listOf(alt, main), emptyList(), 5, now, la)
        assertEquals(18, ui.leaveInMin); assertEquals(24, ui.driveMin); assertEquals("Hwy 17", ui.via)
        assertNull(ui.trafficChip) // no free keyless traffic source
        assertEquals(listOf("VIA SOQUEL DR"), ui.alternatives.map { it.label })
        val pts = ui.route!! + ui.altRoute!!
        assertTrue(pts.all { (x, y) -> x in 0f..1f && y in 0f..1f })
        assertTrue(ui.route!!.first().second > ui.route!!.last().second) // north is up
    }

    @Test fun osrmParseAndRoadNames() {
        val body = JSONObject("""{"code":"Ok","routes":[
            {"duration":1860,"distance":27900,"geometry":{"coordinates":[[-122.03,36.97],[-122.01,37.05]]},
             "legs":[{"steps":[{"name":"Soquel Drive","ref":"","distance":15000},{"name":"","ref":"CA 1","distance":9000}]}]},
            {"duration":1440,"distance":31200,"geometry":{"coordinates":[[-122.03,36.97],[-122.01,37.05]]},
             "legs":[{"steps":[{"name":"Ocean Street","ref":"","distance":2000},{"name":"Santa Cruz Highway","ref":"CA 17","distance":25000}]}]}]}""")
        val r = Router.parse(body)
        assertEquals(listOf(24, 31), r.map { it.minutes }) // fastest first
        assertEquals("Hwy 17", r[0].via); assertEquals("Soquel Drive", r[1].via)
        assertEquals(LatLon(36.97, -122.03), r[0].path.first())
        assertEquals("I-280", Router.roadName("I 280", "Junipero Serra Freeway"))
        assertEquals("Hwy 101", Router.roadName("US 101;CA 1", ""))
        assertNull(Router.roadName("", " "))
        assertTrue(Router.parse(JSONObject("""{"code":"NoRoute"}""")).isEmpty())
    }

    @Test fun geocoderCachesHitsAndMissesAndSendsOnlyThePlace() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"lat":"37.0511","lon":"-122.0147","display_name":"Scotts Valley"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        val settings = MemSettings()
        var t = 0L
        val g = Geocoder(settings, Net.http, server.url("/search"), clock = { t })
        assertEquals(LatLon(37.0511, -122.0147), g.geocode("  Scotts   Valley "))
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Scotts Valley", req.requestUrl!!.queryParameter("q"))
        assertEquals(Net.USER_AGENT, req.getHeader("User-Agent"))
        assertEquals(LatLon(37.0511, -122.0147), g.geocode("scotts valley")) // cached: no request
        t = 5000
        assertNull(g.geocode("Nowhere at all"))
        assertNull(g.geocode("Nowhere at all")) // the miss is cached too
        assertEquals(2, server.requestCount)
        assertEquals(LatLon(36.97, -122.03), g.geocode("36.97, -122.03")) // coordinates: no request
        assertNull(Geocoder.parseLatLon("95060"))
    }
}
