package app.vesper.feature.radio

import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loopback relay end to end: a fake Icecast server → [IcyProxy] → a client standing in for MediaPlayer. */
class IcyProxyTest {
    private val upstream = MockWebServer().apply { start() }
    private val http = OkHttpClient()

    @After fun tearDown() = upstream.shutdown()

    @Test fun relaysAudioWithoutMetadataAndReportsTitles() {
        val metaInt = 64
        val audio = ByteArrayOutputStream()
        val body = Buffer()
        val title = "StreamTitle='Marisol Vey - Low Tide';"
        val tb = title.toByteArray()
        val len = (tb.size + 15) / 16
        repeat(5) { block ->
            val chunk = ByteArray(metaInt) { (block * 7 + it).toByte() }
            audio.write(chunk); body.write(chunk)
            if (block == 1) { body.writeByte(len); body.write(tb); body.write(ByteArray(len * 16 - tb.size)) } else body.writeByte(0)
        }
        upstream.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setHeader("icy-metaint", metaInt).setHeader("icy-br", "128").setBody(body))

        val titles = CopyOnWriteArrayList<String?>()
        var bitrate: Int? = null
        val proxy = IcyProxy(http, upstream.url("/live").toString(), onTitle = { titles += it }, onHeaders = { br, _ -> bitrate = br })
        val local = proxy.start()
        try {
            assertTrue(local.startsWith("http://127.0.0.1:"))
            val got = http.newCall(Request.Builder().url(local).build()).execute().use { res ->
                assertEquals(200, res.code)
                assertEquals("audio/mpeg", res.header("Content-Type"))
                res.body!!.bytes()
            }
            assertArrayEquals(audio.toByteArray(), got)
            assertEquals(listOf<String?>("Marisol Vey - Low Tide"), titles.toList())
            assertEquals(128, bitrate)
            val req = upstream.takeRequest()
            assertEquals("1", req.getHeader("Icy-MetaData"))
            assertEquals(RadioBrowser.USER_AGENT, req.getHeader("User-Agent"))
        } finally {
            proxy.stop()
        }
    }

    @Test fun passesStreamsWithoutMetadataThrough() {
        val bytes = ByteArray(1000) { it.toByte() }
        upstream.enqueue(MockResponse().setHeader("Content-Type", "audio/aacp").setBody(Buffer().write(bytes)))
        val proxy = IcyProxy(http, upstream.url("/aac").toString(), onTitle = {})
        val local = proxy.start()
        try {
            http.newCall(Request.Builder().url(local).build()).execute().use { res ->
                assertEquals("audio/aacp", res.header("Content-Type"))
                assertArrayEquals(bytes, res.body!!.bytes())
            }
        } finally {
            proxy.stop()
        }
    }
}
