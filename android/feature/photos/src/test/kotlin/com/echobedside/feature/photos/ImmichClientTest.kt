package com.echobedside.feature.photos

import com.echobedside.feature.photos.data.ImmichClient
import com.echobedside.feature.photos.data.ImmichHttpException
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Immich client against a fake server (MockWebServer). */
class ImmichClientTest {
    private val server = MockWebServer()
    private lateinit var client: ImmichClient

    @Before fun setUp() {
        server.start()
        client = ImmichClient(server.url("/").toString(), "test-key")
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun albumsSendsApiKeyAndParses() {
        server.enqueue(json("""[
            {"id":"a1","albumName":"Family","assetCount":42,"albumThumbnailAssetId":"x"},
            {"id":"a2","albumName":"Trips","assetCount":7}
        ]"""))
        val albums = client.albums()
        assertEquals(listOf("Family", "Trips"), albums.map { it.name })
        assertEquals(42, albums[0].assetCount)
        val req = server.takeRequest()
        assertEquals("/api/albums", req.path)
        assertEquals("GET", req.method)
        assertEquals("test-key", req.getHeader("x-api-key"))
    }

    @Test fun albumAssetsKeepsStillImagesOnly() {
        server.enqueue(json("""{"id":"a1","albumName":"Family","assetCount":4,"assets":[
            {"id":"p1","type":"IMAGE","localDateTime":"2025-10-12T18:30:00.000Z",
             "exifInfo":{"city":"Big Sur","state":"California","country":"United States","exifImageWidth":4032,"exifImageHeight":3024}},
            {"id":"v1","type":"VIDEO","localDateTime":"2025-10-12T18:31:00.000Z"},
            {"id":"p2","type":"IMAGE","isTrashed":true,"localDateTime":"2025-10-12T18:32:00.000Z"},
            {"id":"p3","type":"IMAGE","fileCreatedAt":"2024-01-02T03:04:05.000Z",
             "exifInfo":{"city":null,"state":null,"country":"Japan","exifImageWidth":4000,"exifImageHeight":3000,"orientation":"6"}}
        ]}"""))
        val assets = client.albumAssets("a1")
        assertEquals(listOf("p1", "p3"), assets.map { it.id })
        assertEquals(LocalDateTime.of(2025, 10, 12, 18, 30), assets[0].takenAt) // local wall time, Z ignored
        assertEquals("Big Sur", assets[0].place)
        assertEquals(4032, assets[0].width)
        assertEquals("Japan", assets[1].place)
        assertEquals(3000, assets[1].width) // orientation 6: rotated, width/height swapped
        assertEquals(LocalDateTime.of(2024, 1, 2, 3, 4, 5), assets[1].takenAt)
        val req = server.takeRequest()
        assertEquals("/api/albums/a1?withoutAssets=false", req.path)
        assertEquals("test-key", req.getHeader("x-api-key"))
    }

    @Test fun albumAssetsFallsBackToSearchWhenAssetsOmitted() {
        server.enqueue(json("""{"id":"a1","albumName":"Family","assetCount":3}"""))
        server.enqueue(json("""{"assets":{"items":[{"id":"p1","type":"IMAGE"},{"id":"p2","type":"IMAGE"}],"nextPage":"2","total":3,"count":2}}"""))
        server.enqueue(json("""{"assets":{"items":[{"id":"p3","type":"IMAGE"}],"nextPage":null,"total":3,"count":1}}"""))
        assertEquals(listOf("p1", "p2", "p3"), client.albumAssets("a1").map { it.id })
        server.takeRequest()
        val s1 = server.takeRequest()
        assertEquals("/api/search/metadata", s1.path)
        assertEquals("POST", s1.method)
        assertEquals("test-key", s1.getHeader("x-api-key"))
        val body = JSONObject(s1.body.readUtf8())
        assertEquals("a1", body.getJSONArray("albumIds").getString(0))
        assertEquals("IMAGE", body.getString("type"))
        assertEquals(1, body.getInt("page"))
        assertEquals(2, JSONObject(server.takeRequest().body.readUtf8()).getInt("page"))
    }

    @Test fun emptyAlbumDoesNotSearch() {
        server.enqueue(json("""{"id":"a1","albumName":"Empty","assetCount":0,"assets":[]}"""))
        assertTrue(client.albumAssets("a1").isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test fun downloadsPreviewAtomically() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes)))
        val dir = Files.createTempDirectory("prev").toFile()
        val dest = File(dir, "p1.img")
        assertEquals(5000L, client.downloadPreview("p1", dest))
        assertTrue(dest.readBytes().contentEquals(bytes))
        assertFalse(File(dir, "p1.img.tmp").exists())
        val req = server.takeRequest()
        assertEquals("/api/assets/p1/thumbnail?size=preview", req.path)
        assertEquals("test-key", req.getHeader("x-api-key"))
        dir.deleteRecursively()
    }

    @Test fun httpErrorsCarryTheCodeButNotTheKey() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Invalid API key"}"""))
        try {
            client.albums()
            fail("expected ImmichHttpException")
        } catch (e: ImmichHttpException) {
            assertEquals(401, e.code)
            assertFalse(e.message!!.contains("test-key"))
        }
    }

    @Test fun failedDownloadLeavesNoFile() {
        server.enqueue(MockResponse().setResponseCode(404))
        val dir = Files.createTempDirectory("prev").toFile()
        val dest = File(dir, "gone.img")
        try { client.downloadPreview("gone", dest); fail() } catch (e: ImmichHttpException) { assertEquals(404, e.code) }
        assertFalse(dest.exists())
        dir.deleteRecursively()
    }

    @Test fun normalizesBaseUrl() {
        assertEquals("http://immich.local:2283/", ImmichClient.normalize("immich.local:2283").toString())
        assertEquals("http://immich.local:2283/", ImmichClient.normalize("http://immich.local:2283/api/").toString())
        assertEquals("https://photos.example.com/immich/", ImmichClient.normalize("https://photos.example.com/immich").toString())
        assertEquals("http://h:1/api/assets/x/thumbnail?size=preview", ImmichClient("http://h:1/api", "k").previewUrl("x").toString())
    }

    @Test fun parsesDates() {
        assertEquals(LocalDateTime.of(2025, 7, 4, 20, 15), ImmichClient.parseLocal("2025-07-04T20:15:00.000Z"))
        assertEquals(LocalDateTime.of(2025, 7, 4, 20, 15), ImmichClient.parseLocal("2025-07-04T20:15:00+02:00"))
        assertEquals(LocalDateTime.of(2025, 7, 4, 20, 15), ImmichClient.parseLocal("2025-07-04T20:15:00"))
        assertNull(ImmichClient.parseLocal("yesterday"))
        assertNull(ImmichClient.parseLocal(null))
    }
}
