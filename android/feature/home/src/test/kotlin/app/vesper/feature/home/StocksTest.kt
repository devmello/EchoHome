package app.vesper.feature.home

import app.vesper.feature.home.glance.Net
import app.vesper.feature.home.stocks.DemoStocks
import app.vesper.feature.home.stocks.MarketHours
import app.vesper.feature.home.stocks.MarketHours.Phase
import app.vesper.feature.home.stocks.YahooChartSource
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StocksTest {
    private fun et(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(MarketHours.ZONE).toInstant().toEpochMilli()
    private val server = MockWebServer()
    @After fun tearDown() = server.shutdown()

    @Test fun holidays2026And2027() {
        val h = MarketHours.holidays(2026)
        listOf("2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19", "2026-07-03",
            "2026-09-07", "2026-11-26", "2026-12-25").forEach { assertTrue(it, LocalDate.parse(it) in h) }
        assertEquals(10, h.size)
        assertTrue(LocalDate.parse("2027-12-24") in MarketHours.holidays(2027)) // Christmas on a Saturday → Friday
        assertTrue(MarketHours.holidays(2028).none { it.year == 2027 || it == LocalDate.parse("2027-12-31") }) // New Year's on a Saturday isn't observed
        assertTrue(MarketHours.isEarlyClose(LocalDate.parse("2026-11-27")))
        assertTrue(MarketHours.isEarlyClose(LocalDate.parse("2026-12-24")))
        assertFalse(MarketHours.isEarlyClose(LocalDate.parse("2026-07-03"))) // a holiday that year, not a half day
        assertEquals(LocalTime.of(13, 0), MarketHours.closeTime(LocalDate.parse("2026-11-27")))
    }

    @Test fun phasesAndHeadline() {
        val fri = LocalDate.parse("2026-11-20")
        assertEquals(Phase.PRE_OPEN, MarketHours.phase(et(fri, 9, 29)))
        assertEquals(Phase.OPEN, MarketHours.phase(et(fri, 9, 30)))
        assertEquals(Phase.AFTER_CLOSE, MarketHours.phase(et(fri, 16)))
        assertEquals(Phase.CLOSED_DAY, MarketHours.phase(et(LocalDate.parse("2026-11-26"), 12))) // Thanksgiving
        assertEquals(Phase.AFTER_CLOSE, MarketHours.phase(et(LocalDate.parse("2026-11-27"), 13, 30))) // early close
        // Sunday Nov 22 (the design's "MARKETS CLOSED · FRIDAY CLOSE").
        val sun = et(LocalDate.parse("2026-11-22"), 10)
        assertEquals("MARKETS CLOSED · FRIDAY CLOSE", MarketHours.headline(sun, "OPEN"))
        assertEquals(fri, MarketHours.lastSession(sun))
        assertEquals(et(LocalDate.parse("2026-11-23"), 9, 30), MarketHours.nextOpen(sun))
        assertEquals(et(LocalDate.parse("2026-11-27"), 9, 30), MarketHours.nextOpen(et(LocalDate.parse("2026-11-25"), 17))) // skips Thanksgiving
        assertEquals("MARKETS CLOSED · TODAY’S CLOSE", MarketHours.headline(et(fri, 17), "OPEN"))
        assertEquals("MARKETS · DELAYED 15 MIN", MarketHours.headline(et(fri, 11), "MARKETS · DELAYED 15 MIN"))
        assertEquals(LocalDate.parse("2026-11-19"), MarketHours.lastSession(et(fri, 8))) // before the open: yesterday's
        assertEquals(LocalDate.parse("2026-03-27"), MarketHours.easter(2026).minusDays(9))
    }

    private fun chart(price: Double, prev: Double, name: String, start: Long, end: Long, ts: List<Long>, closes: List<Double?>) = JSONObject()
        .put("chart", JSONObject().put("result", org.json.JSONArray().put(JSONObject()
            .put("meta", JSONObject().put("regularMarketPrice", price).put("previousClose", prev).put("shortName", name)
                .put("currentTradingPeriod", JSONObject().put("regular", JSONObject().put("start", start).put("end", end))))
            .put("timestamp", org.json.JSONArray(ts))
            .put("indicators", JSONObject().put("quote", org.json.JSONArray().put(JSONObject().put("close", org.json.JSONArray().apply { closes.forEach { put(it ?: JSONObject.NULL) } })))))))

    @Test fun yahooParse() {
        val fri = LocalDate.parse("2026-11-20")
        val start = et(fri, 9, 30) / 1000; val end = et(fri, 16) / 1000
        val ts = listOf(start - 300, start, start + 300, start + 600, end)
        val q = YahooChartSource.parse(chart(231.0, 220.0, "Apple Inc.", start, end, ts, listOf(219.0, 221.0, null, 230.0, 231.5)), "AAPL")!!
        assertEquals("Apple", q.name)
        assertEquals(11.0, q.change, 1e-9)
        assertEquals(5.0, q.percent, 1e-9)
        assertEquals(listOf(221.0, 230.0), q.series) // regular session only, nulls dropped
        assertEquals(78, q.sessionBars)
        assertEquals(220.0, q.baseline!!, 0.0)
        assertEquals("NVIDIA", YahooChartSource.shortName("NVIDIA Corporation"))
        assertEquals("Amazon.com", YahooChartSource.shortName("Amazon.com, Inc."))
        assertEquals(null, YahooChartSource.parse(JSONObject("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""), "ZZZZ"))
    }

    @Test fun yahooSourceKeepsWhatLoads() = runBlocking {
        val fri = LocalDate.parse("2026-11-20")
        val s = et(fri, 9, 30) / 1000; val e = et(fri, 16) / 1000
        server.enqueue(MockResponse().setBody(chart(100.0, 99.0, "Alpha Corp", s, e, listOf(s), listOf(100.0)).toString()))
        server.enqueue(MockResponse().setResponseCode(404))
        val src = YahooChartSource(Net.http, server.url("/v8/finance/chart/"))
        val q = src.quotes(listOf("AAA", "BBB"))
        assertEquals(listOf("AAA"), q.map { it.symbol })
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v8/finance/chart/AAA?range=1d&interval=5m", r.path)
        server.enqueue(MockResponse().setResponseCode(429))
        try { src.quotes(listOf("CCC")); fail() } catch (_: IOException) { }
    }

    @Test fun demoMatchesTheDesign() {
        val w = DemoStocks.watchlist(0)
        assertTrue(w.demo)
        assertEquals(78, w.quotes.first().series.size)
        assertEquals(40, w.quotes.first().spark.size)
        assertEquals(DemoStocks.series(1, 10, 0.0), DemoStocks.series(1, 10, 0.0)) // deterministic
    }
}
