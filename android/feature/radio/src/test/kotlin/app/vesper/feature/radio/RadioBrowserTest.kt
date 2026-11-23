package app.vesper.feature.radio

import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RadioBrowserTest {
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }
    private val http = OkHttpClient()

    @After fun tearDown() { a.shutdown(); b.shutdown() }

    private fun base(s: MockWebServer) = s.url("/").toString().trimEnd('/')

    /** A station object the way the API sends it (numbers as numbers, some as strings on old servers). */
    private fun station(uuid: String, name: String, codec: String = "MP3", bitrate: Any = 128, hls: Any = 0, url: String = "http://stream.example/$uuid") =
        JSONObject().put("changeuuid", "c-$uuid").put("stationuuid", uuid).put("serveruuid", JSONObject.NULL)
            .put("name", name).put("url", "http://example/$uuid.pls").put("url_resolved", url)
            .put("homepage", "https://example.org/").put("favicon", "https://example.org/icon.png")
            .put("tags", "jazz,Smooth Jazz, ,lounge").put("country", "France").put("countrycode", "FR")
            .put("iso_3166_2", JSONObject.NULL).put("state", "").put("language", "french").put("votes", 12)
            .put("codec", codec).put("bitrate", bitrate).put("hls", hls).put("lastcheckok", 1)
            .put("clickcount", 421).put("clicktrend", 3).put("ssl_error", 0).put("geo_lat", JSONObject.NULL)

    private fun body(vararg s: JSONObject) = JSONArray(s.toList()).toString()

    @Test fun parsesStations() {
        val list = RadioBrowser.parseStations(body(
            station("u1", "  Night   Swim  Jazz ", bitrate = "192"),
            station("u2", "HLS One", codec = "UNKNOWN", bitrate = 0, hls = 1, url = "https://example/live.m3u8"),
            station("u3", "No stream", url = "").put("url", "ftp://nope"),
        ))
        assertEquals(2, list.size)
        val s = list[0]
        assertEquals("u1", s.uuid)
        assertEquals("Night Swim Jazz", s.name)
        assertEquals("http://stream.example/u1", s.url)
        assertEquals(listOf("jazz", "smooth jazz", "lounge"), s.tags)
        assertEquals("FR", s.countryCode)
        assertEquals(192, s.bitrate)
        assertEquals("MP3 · 192 kbps", s.format)
        assertEquals("Jazz · Smooth jazz · France", s.subtitle)
        assertTrue(list[1].hls)
        assertEquals("HLS", list[1].format)
    }

    @Test fun roundTripsThroughOwnJson() {
        val s = RadioBrowser.parseStations(body(station("u1", "Night Swim Jazz")))[0]
        assertEquals(s, Station.fromJson(s.toJson()))
    }

    @Test fun badJsonIsAnIoError() {
        try { RadioBrowser.parseStations("<html>"); fail() } catch (e: IOException) {}
    }

    @Test fun searchSendsQueryAndUserAgentAndDropsUnplayable() = runBlocking {
        a.enqueue(MockResponse().setBody(body(station("u1", "Jazz One"), station("u2", "Video", codec = "H.264"))))
        val rb = RadioBrowser(http, discover = { listOf(base(a)) })
        val r = rb.search(RadioBrowser.Query(tag = "Jazz", countryCode = "fr", limit = 20))
        assertEquals(listOf("u1"), r.map { it.uuid })
        val req = a.takeRequest()
        assertEquals("/json/stations/search", req.requestUrl!!.encodedPath)
        assertEquals("jazz", req.requestUrl!!.queryParameter("tag"))
        assertEquals("true", req.requestUrl!!.queryParameter("tagExact"))
        assertEquals("FR", req.requestUrl!!.queryParameter("countrycode"))
        assertEquals("clickcount", req.requestUrl!!.queryParameter("order"))
        assertEquals("true", req.requestUrl!!.queryParameter("reverse"))
        assertEquals("true", req.requestUrl!!.queryParameter("hidebroken"))
        assertEquals("20", req.requestUrl!!.queryParameter("limit"))
        assertNull(req.requestUrl!!.queryParameter("name"))
        assertEquals(RadioBrowser.USER_AGENT, req.getHeader("User-Agent"))
        assertTrue(req.getHeader("User-Agent")!!.startsWith("EchoHome/0.1"))
    }

    @Test fun failsOverToTheNextServerAndSticksToIt() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(503))
        b.enqueue(MockResponse().setBody(body(station("u1", "One"))))
        b.enqueue(MockResponse().setBody(body(station("u2", "Two"))))
        // order() shuffles; a fixed list with a Random that keeps a first is checked below, here force the order
        val rb = RadioBrowser(http, discover = { listOf(base(a), base(b)) }, random = Identity)
        assertEquals("u1", rb.search(RadioBrowser.Query(name = "one")).single().uuid)
        assertEquals("u2", rb.search(RadioBrowser.Query(name = "two")).single().uuid)
        assertEquals(1, a.requestCount)
        assertEquals(2, b.requestCount)
    }

    @Test fun clientErrorsDontFailOver() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(400))
        val rb = RadioBrowser(http, discover = { listOf(base(a), base(b)) }, random = Identity)
        try { rb.search(RadioBrowser.Query(name = "x")); fail() } catch (e: RadioBrowser.HttpError) { assertEquals(400, e.code) }
        assertEquals(0, b.requestCount)
    }

    @Test fun allServersDownRediscoversNextTime() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(502))
        a.enqueue(MockResponse().setBody(body(station("u1", "One"))))
        var lookups = 0
        val rb = RadioBrowser(http, discover = { lookups++; listOf(base(a)) })
        try { rb.search(RadioBrowser.Query(name = "one")); fail() } catch (e: IOException) {}
        assertEquals("u1", rb.search(RadioBrowser.Query(name = "one")).single().uuid)
        assertEquals(2, lookups)
    }

    @Test fun noServers() = runBlocking {
        val rb = RadioBrowser(http, discover = { emptyList() })
        try { rb.search(RadioBrowser.Query(name = "x")); fail() } catch (e: IOException) {}
    }

    @Test fun searchesAreCached() = runBlocking {
        a.enqueue(MockResponse().setBody(body(station("u1", "One"))))
        var t = 0L
        val rb = RadioBrowser(http, discover = { listOf(base(a)) }, now = { t })
        rb.search(RadioBrowser.Query(tag = "jazz"))
        t += 60_000
        rb.search(RadioBrowser.Query(tag = "jazz"))
        assertEquals(1, a.requestCount)
        a.enqueue(MockResponse().setBody(body(station("u2", "Two"))))
        t += RadioBrowser.CACHE_MS
        assertEquals("u2", rb.search(RadioBrowser.Query(tag = "jazz")).single().uuid)
        assertEquals(2, a.requestCount)
    }

    @Test fun reportsAPlayAndFetchesByUuid() = runBlocking {
        a.enqueue(MockResponse().setBody("""{"ok":true,"message":"retrieved station url","stationuuid":"u1","name":"One","url":"http://s"}"""))
        a.enqueue(MockResponse().setBody(body(station("u1", "One"), station("u2", "Two"))))
        val rb = RadioBrowser(http, discover = { listOf(base(a)) })
        rb.reportPlay("u1")
        assertEquals("/json/url/u1", a.takeRequest().path)
        assertEquals(2, rb.byUuid(listOf("u1", "u2")).size)
        assertEquals("u1,u2", a.takeRequest().requestUrl!!.queryParameter("uuids"))
    }

    @Test fun reportPlayIgnoresErrors() = runBlocking {
        a.enqueue(MockResponse().setResponseCode(500))
        RadioBrowser(http, discover = { listOf(base(a)) }).reportPlay("u1") // no throw
    }

    @Test fun parsesTheServersList() {
        val list = ServerDiscovery.parseServers("""[
            {"ip":"2a01:4f8::2","name":"de1.api.radio-browser.info"},
            {"ip":"91.132.145.114","name":"de1.api.radio-browser.info"},
            {"ip":"89.58.16.19","name":"NL1.api.radio-browser.info"},
            {"ip":"1.2.3.4","name":"evil.example.com"},
            {"ip":"1.2.3.5","name":".api.radio-browser.info"}]""")
        assertEquals(listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info"), list)
        try { ServerDiscovery.parseServers("nope"); fail() } catch (e: IOException) {}
    }

    @Test fun serversEndpointOverHttp() {
        a.enqueue(MockResponse().setBody("""[{"ip":"1","name":"at1.api.radio-browser.info"}]"""))
        assertEquals(listOf("https://at1.api.radio-browser.info"), ServerDiscovery.fromServersEndpoint(http, a.url("/json/servers").toString()))
        assertEquals(RadioBrowser.USER_AGENT, a.takeRequest().getHeader("User-Agent"))
    }

    @Test fun serverOrderIsShuffledAndDistinct() {
        val s = listOf("https://a", "https://b", "https://c", "https://a")
        val one = ServerDiscovery.order(s, Random(1))
        assertEquals(3, one.size)
        assertEquals(setOf("https://a", "https://b", "https://c"), one.toSet())
        val orders = (0 until 20).map { ServerDiscovery.order(s, Random(it)).first() }.toSet()
        assertTrue("first server varies", orders.size > 1)
        assertFalse(one.isEmpty())
    }

    /** A Random whose shuffle keeps the list in order (nextInt(until) returns the last index). */
    private object Identity : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextInt(from: Int, until: Int) = until - 1
    }
}
