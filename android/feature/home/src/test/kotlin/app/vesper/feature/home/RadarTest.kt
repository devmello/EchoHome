package app.vesper.feature.home

import app.vesper.feature.home.radar.RadarFrames
import app.vesper.feature.home.radar.RadarMap
import app.vesper.feature.home.radar.RainViewer
import app.vesper.feature.home.radar.RainViewerClient
import app.vesper.feature.home.radar.RateLimiter
import app.vesper.feature.home.radar.TileCache
import app.vesper.feature.home.radar.TileMath
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RadarTest {
    /** The shape of api.rainviewer.com/public/weather-maps.json (13 past frames, 10 min apart). */
    private fun manifestJson(newest: Long = 1_763_870_400L, frames: Int = 13): String {
        val past = (frames - 1 downTo 0).joinToString(",") { i -> val t = newest - i * 600; """{"time":$t,"path":"/v2/radar/$t"}""" }
        return """{"version":"2.0","generated":${newest + 120},"host":"https://tilecache.rainviewer.com",
            "radar":{"past":[$past],"nowcast":[]},"satellite":{"infrared":[]}}"""
    }

    // ---------------------------------------------------------------- manifest

    @Test fun parsesTheManifest() {
        val m = RainViewer.parse(manifestJson())
        assertEquals("https://tilecache.rainviewer.com", m.host)
        assertEquals(13, m.past.size)
        assertTrue(m.nowcast.isEmpty())
        assertEquals(1_763_870_400L, m.past.last().time)
        assertEquals("/v2/radar/1763870400", m.past.last().path)
        assertEquals(1_763_870_400L + 120, m.generated)
    }

    @Test fun lastHourIsSevenFramesEndingAtTheNewest() {
        val m = RainViewer.parse(manifestJson())
        val f = RainViewer.lastHour(m, max = RadarFrames.MAX_FRAMES)
        assertEquals(7, f.size)
        assertEquals(m.past.last(), f.last())
        assertEquals(3600L, f.last().time - f.first().time)
        assertEquals(listOf(0L, 600, 1200, 1800, 2400, 3000, 3600), f.map { it.time - f.first().time })
        assertEquals(3, RainViewer.lastHour(RainViewer.parse(manifestJson(frames = 3))).size)
        assertTrue(RainViewer.lastHour(RainViewer.parse("""{"host":"https://h","radar":{"past":[]}}""")).isEmpty())
    }

    @Test fun skipsBadFramesAndSorts() {
        val m = RainViewer.parse("""{"host":"https://h/","radar":{"past":[
            {"time":200,"path":"/b"},{"time":100,"path":"/a"},{"time":0,"path":"/zero"},{"time":300,"path":"no-slash"},{"time":100,"path":"/dup"}]}}""")
        assertEquals("https://h", m.host)
        assertEquals(listOf(100L, 200L), m.past.map { it.time })
    }

    @Test fun badManifests() {
        for (b in listOf("<html>", """{"radar":{}}""", """{"host":"ftp://x","radar":{}}"""))
            try { RainViewer.parse(b); fail(b) } catch (e: IOException) {}
    }

    @Test fun tileUrls() {
        val f = RainViewer.Frame(1_763_870_400L, "/v2/radar/1763870400")
        assertEquals("https://tilecache.rainviewer.com/v2/radar/1763870400/512/6/10/24/2/1_0.png",
            RainViewer.tileUrl("https://tilecache.rainviewer.com", f, 6, 10, 24))
        assertEquals("https://h/p/256/3/1/2/2/0_1.png", RainViewer.tileUrl("https://h", RainViewer.Frame(1, "/p"), 3, 1, 2, size = 256, smooth = false, snow = true))
        try { RainViewer.tileUrl("https://h", f, 8, 0, 0); fail("zoom 8 is past the free tier") } catch (e: IllegalArgumentException) {}
    }

    @Test fun clientFetchesManifestAndTiles() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(manifestJson()))
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
            server.enqueue(MockResponse().setBody(Buffer().write(png)))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500))
            val client = RainViewerClient(OkHttpClient(), server.url("/public/weather-maps.json").toString())
            assertEquals(13, client.manifest().past.size)
            assertArrayEquals(png, client.tile(server.url("/v2/radar/1/512/6/10/24/2/1_0.png").toString()))
            assertNull(client.tile(server.url("/missing.png").toString()))
            try { client.tile(server.url("/err.png").toString()); fail() } catch (e: IOException) {}
            assertEquals("/public/weather-maps.json", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun rateLimiterSlidingWindow() {
        var t = 0L
        val rl = RateLimiter(max = 3, windowMs = 60_000, now = { t })
        assertEquals(0L, rl.tryAcquire()); t += 1_000
        assertEquals(0L, rl.tryAcquire()); t += 1_000
        assertEquals(0L, rl.tryAcquire())
        assertEquals(58_000L, rl.tryAcquire()) // the first one leaves the window at 60 s
        t = 60_000
        assertEquals(0L, rl.tryAcquire())
        assertTrue(rl.tryAcquire() > 0)
        // RainViewer allows 100 / IP / minute: the default stays well under
        val def = RateLimiter(now = { 0L })
        var n = 0
        while (def.tryAcquire() == 0L) n++
        assertTrue(n in 1..100)
    }

    // ---------------------------------------------------------------- tile maths

    @Test fun projectionRoundTrips() {
        for (z in listOf(0, 3, 6, 7)) for ((lat, lon) in listOf(37.7749 to -122.4194, -33.86 to 151.21, 0.0 to 0.0, 64.1 to -21.9)) {
            assertEquals(lon, TileMath.xToLon(TileMath.lonToX(lon, z), z), 1e-9)
            assertEquals(lat, TileMath.yToLat(TileMath.latToY(lat, z), z), 1e-9)
        }
        assertEquals(TileMath.worldSize(6) / 2, TileMath.lonToX(0.0, 6), 1e-9)
        assertEquals(TileMath.worldSize(6) / 2, TileMath.latToY(0.0, 6), 1e-9)
        assertEquals(0.0, TileMath.latToY(90.0, 3), 1e-6) // clamped to the map's top
    }

    @Test fun tilesForSanFranciscoAtZoom6() {
        // standard slippy tile for SF at z6 is x=10, y=24
        val tiles = TileMath.tilesFor(37.7749, -122.4194, 6, 960, 480)
        assertTrue(tiles.any { it.x == 10 && it.y == 24 })
        assertTrue(tiles.size in 4..6)
        // together they cover the whole view
        for (px in listOf(0.0, 480.0, 959.0)) for (py in listOf(0.0, 240.0, 479.0))
            assertTrue("($px,$py)", tiles.any { px >= it.left && px < it.left + TileMath.TILE && py >= it.top && py < it.top + TileMath.TILE })
        // the centre tile's corner puts SF at the view centre
        val c = tiles.first { 480.0 >= it.left && 480.0 < it.left + 512 && 240.0 >= it.top && 240.0 < it.top + 512 }
        assertEquals(TileMath.lonToX(-122.4194, 6) - c.x * 512.0, 480.0 - c.left, 1e-6)
    }

    @Test fun tilesWrapAroundTheAntimeridian() {
        val tiles = TileMath.tilesFor(-17.7, 179.9, 5, 960, 480) // Fiji
        val xs = tiles.map { it.x }.toSet()
        assertTrue(xs.contains(31) && xs.contains(0))
        assertTrue(tiles.all { it.x in 0 until 32 })
    }

    @Test fun tilesStayOnTheMapNearThePoles() {
        val tiles = TileMath.tilesFor(84.0, 0.0, 3, 960, 480)
        assertTrue(tiles.all { it.y >= 0 })
        assertTrue(tiles.isNotEmpty())
    }

    @Test fun scaleOfTheView() {
        // zoom 6 with 512 px tiles: ~930 km across 960 px at the Bay Area's latitude
        val km = TileMath.kmAcross(37.77, 6, 960)
        assertTrue("$km", km in 850.0..1000.0)
        assertEquals(1.0, TileMath.mapScale(7), 0.0)
        assertEquals(0.5, TileMath.mapScale(6), 0.0)
    }

    // ---------------------------------------------------------------- base map + memory

    private val asset = File("src/main/assets/radar/ne50m.bin")

    @Test fun parsesTheBundledBaseMap() {
        val map = RadarMap.parse(asset.readBytes())
        assertEquals(listOf(RadarMap.Kind.LAND, RadarMap.Kind.LAKES, RadarMap.Kind.BORDERS, RadarMap.Kind.STATES), map.layers.map { it.kind })
        assertTrue(map.layers[0].closed && !map.layers[2].closed)
        val points = map.layers.sumOf { l -> l.rings.sumOf { it.size } }
        assertTrue("$points", points in 100_000..140_000)
        // North America's ring contains San Francisco (Web Mercator units, 65536 = the world)
        val sx = (TileMath.lonToX(-122.4194, 7)).toInt(); val sy = TileMath.latToY(37.7749, 7).toInt()
        assertTrue(map.layers[0].rings.any { sx in it.minX..it.maxX && sy in it.minY..it.maxY && it.size > 1000 })
        // the bounding boxes hold their points
        for (r in map.layers[0].rings.take(50)) for (i in 0 until r.size) {
            assertTrue(r.x(i) in r.minX..r.maxX); assertTrue(r.y(i) in r.minY..r.maxY)
        }
    }

    @Test fun rejectsMalformedMaps() {
        val good = asset.readBytes()
        for (b in listOf(ByteArray(0), "NOPE\u0001\u0001".toByteArray(), good.copyOf(200)))
            try { RadarMap.parse(b); fail() } catch (e: IOException) {}
    }

    @Test fun tileCacheEvictsLeastRecentlyUsedWithinBudget() {
        val c = TileCache(1000)
        c.put("a", ByteArray(400)); c.put("b", ByteArray(400))
        c.get("a")
        c.put("c", ByteArray(400))
        assertNull(c.get("b"))
        assertTrue(c.get("a") != null && c.get("c") != null)
        assertEquals(800, c.bytes)
        c.put("huge", ByteArray(2000))
        assertNull(c.get("huge"))
        c.put("a", ByteArray(100))
        assertEquals(500, c.bytes)
        c.clear()
        assertEquals(0, c.bytes); assertEquals(0, c.size())
    }

    @Test fun radarStaysInsideEightMegabytes() {
        val total = RadarFrames.MAX_FRAMES.toLong() * RadarFrames.FRAME_BYTES + RadarFrames.TILE_CACHE_BYTES
        assertTrue("$total", total <= 8L * 1024 * 1024)
        assertTrue(abs(RadarFrames.FRAME_W * RadarFrames.SCALE - 960) == 0)
    }
}
