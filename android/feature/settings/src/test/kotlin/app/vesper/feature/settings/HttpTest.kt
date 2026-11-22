package app.vesper.feature.settings

import app.vesper.feature.settings.api.EventHub
import app.vesper.feature.settings.http.HttpException
import app.vesper.feature.settings.http.HttpParser
import app.vesper.feature.settings.http.HttpResponse
import app.vesper.feature.settings.http.HttpServer
import app.vesper.feature.settings.http.Lan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class HttpTest {
    private fun parse(s: String) = HttpParser.parse(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)))

    private fun status(s: String): Int = try { parse(s); 0 } catch (e: HttpException) { e.status }

    @Test fun parsesRequestLineHeadersAndBody() {
        val r = parse("PUT /api/settings?x=1&y=a%20b HTTP/1.1\r\nHost: 192.168.10.136:8080\r\nContent-Type: application/json\r\n" +
            "Content-Length: 13\r\nCookie: a=1; vesper_session=tok\r\n\r\n{\"k\":\"v\"}xyz!")!!
        assertEquals("PUT", r.method)
        assertEquals("/api/settings", r.path)
        assertEquals(mapOf("x" to "1", "y" to "a b"), r.query)
        assertEquals("192.168.10.136:8080", r.header("Host"))
        assertEquals("{\"k\":\"v\"}xyz!", r.bodyText())
        assertEquals("tok", r.cookies["vesper_session"])
        assertTrue(r.keepAlive)
    }

    @Test fun bareLfAndPipelining() {
        val input = ByteArrayInputStream("GET /a HTTP/1.1\nHost: h\n\nGET /b HTTP/1.0\nHost: h\n\n".toByteArray())
        val a = HttpParser.parse(input)!!
        val b = HttpParser.parse(input)!!
        assertEquals("/a", a.path)
        assertEquals("/b", b.path)
        assertFalse(b.keepAlive) // HTTP/1.0 without keep-alive
        assertNull(HttpParser.parse(input)) // clean end
    }

    @Test fun connectionClose() {
        assertFalse(parse("GET / HTTP/1.1\r\nConnection: close\r\n\r\n")!!.keepAlive)
    }

    @Test fun decodesUtf8PathAndKeepsPlus() {
        assertEquals("/api/action/a+b", parse("POST /api/action/a+b HTTP/1.1\r\n\r\n")!!.path)
        assertEquals("/é", parse("GET /%C3%A9 HTTP/1.1\r\n\r\n")!!.path)
    }

    @Test fun rejectsMalformed() {
        assertEquals(400, status("GARBAGE\r\n\r\n"))
        assertEquals(400, status("GET relative HTTP/1.1\r\n\r\n"))
        assertEquals(505, status("GET / HTTP/2.0\r\n\r\n"))
        assertEquals(400, status("get / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nNoColon\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"))
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"))
        assertEquals(431, status("GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n"))
        assertEquals(431, status("GET / HTTP/1.1\r\n" + (1..70).joinToString("") { "X-$it: 1\r\n" } + "\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: h")) // cut short
    }

    @Test fun rejectsPathTraversal() {
        assertEquals(400, status("GET /../secret HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a/%2e%2e/b HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /a%00 HTTP/1.1\r\n\r\n"))
    }

    @Test fun writesResponse() {
        val out = ByteArrayOutputStream()
        HttpResponse.json(200, "{\"a\":1}").with("X-Y" to "z").writeTo(out, keepAlive = true)
        val s = out.toString("ISO-8859-1")
        assertTrue(s.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Length: 7\r\n" in s)
        assertTrue("Connection: keep-alive\r\n" in s)
        assertTrue("X-Y: z\r\n" in s)
        assertTrue(s.endsWith("\r\n\r\n{\"a\":1}"))
        val head = ByteArrayOutputStream()
        HttpResponse.json(200, "{\"a\":1}").writeTo(head, keepAlive = false, head = true)
        assertTrue(head.toString().endsWith("Connection: close\r\nCache-Control: no-store\r\n\r\n"))
    }

    @Test fun refusesHeaderInjection() {
        try { HttpResponse(200, headers = listOf("Set-Cookie" to "a\r\nX: y")).writeTo(ByteArrayOutputStream(), false); fail() } catch (e: IllegalArgumentException) {}
    }

    @Test fun jsonStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", HttpResponse.jsonString("a\"b\\c\n\u0001"))
    }

    @Test fun sseFraming() {
        assertEquals("event: change\ndata: {\"a\":1}\n\n", EventHub.frame("change", "{\"a\":1}"))
        assertEquals("event: x\ndata: one\ndata: two\ndata: three\n\n", EventHub.frame("x", "one\ntwo\r\nthree"))
    }

    @Test fun sseStreamsPublishedEventsAndHeartbeats() {
        val hub = EventHub(heartbeatMs = 50)
        val out = object : ByteArrayOutputStream() { @Synchronized override fun toString() = super.toString() }
        val t = Thread { hub.serve(out, "h1") }.apply { start() }
        while (!hub.hasClients()) Thread.sleep(5)
        hub.publish("change", "{\"key\":\"a\"}")
        Thread.sleep(150)
        hub.closeFor("h1")
        t.join(2000)
        assertFalse(t.isAlive)
        assertFalse(hub.hasClients())
        val s = out.toString()
        assertTrue(s.startsWith("retry: 3000\n"))
        assertTrue("event: change\ndata: {\"key\":\"a\"}\n\n" in s)
        assertTrue(": ping\n\n" in s)
    }

    @Test fun lanAddresses() {
        for (a in listOf("192.168.10.20", "10.0.0.1", "172.16.5.4", "172.31.255.255", "127.0.0.1", "169.254.1.1", "fe80::1", "fd12:3456::1", "::1")) {
            assertTrue(a, Lan.isLocal(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "1.1.1.1", "2001:4860::8888", "224.0.0.251")) {
            assertFalse(a, Lan.isLocal(InetAddress.getByName(a)))
        }
        assertFalse(Lan.isLocal(null))
    }

    @Test fun localHostHeaders() {
        for (h in listOf("192.168.10.136:8080", "192.168.10.136", "echo-show.local:8080", "Android.local", "echo.lan", "localhost:8080", "echo", "[fe80::1]:8080", "vesper.home.arpa"))
            assertTrue(h, Lan.isLocalHost(h))
        for (h in listOf(null, "", "evil.com", "192.168.10.136.nip.io", "attacker.example:8080", "local.evil.com"))
            assertFalse("$h", Lan.isLocalHost(h))
    }

    @Test fun serverServesOverLoopbackWithKeepAlive() {
        val server = HttpServer({ r -> HttpResponse.json(200, "{\"path\":\"${r.path}\"}") }, ports = listOf(0))
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\nGET /two HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                val all = s.getInputStream().readBytes().decodeToString()
                assertTrue(all, "{\"path\":\"/one\"}" in all && "{\"path\":\"/two\"}" in all)
            }
            Socket("127.0.0.1", port).use { s ->
                s.getOutputStream().write("BAD\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 400"))
            }
        } finally { server.stop() }
    }

    @Test fun gzipRoundTrip() {
        val b = "hello ".repeat(200).toByteArray()
        val gz = app.vesper.feature.settings.api.Assets.gzip(b)
        assertArrayEquals(b, java.util.zip.GZIPInputStream(gz.inputStream()).readBytes())
    }
}
