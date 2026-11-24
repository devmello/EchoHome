package app.vesper.feature.settings

import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.Secrets
import app.vesper.feature.settings.http.HttpResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class ApiTest {
    private val settings = FakeSettingsStore()
    private val secrets = InMemorySecretStore()
    private val actions = mutableListOf<String>()
    private val clock = longArrayOf(1_000_000L)
    private val api = newApi(settings, secrets, clock, actions, builtIn = Secrets(immichUrl = "http://built.in"))

    private fun HttpResponse.json() = JSONObject(body.decodeToString())

    @Test fun everythingButPairingNeedsTheCookie() {
        assertEquals(401, api.handle(req("GET", "/api/schema")).status)
        assertEquals(401, api.handle(req("PUT", "/api/settings", "{\"device.name\":\"x\"}")).status)
        assertEquals(401, api.handle(req("POST", "/api/action/settings.restartApp")).status)
        assertEquals(401, api.handle(req("GET", "/api/events")).status)
        assertEquals(401, api.handle(req("GET", "/api/pairs")).status)
        assertEquals(401, api.handle(req("GET", "/api/schema", cookie = "forged-token")).status)
        assertFalse(api.handle(req("GET", "/api/hello")).json().getBoolean("paired"))
        assertEquals(200, api.handle(req("GET", "/")).status) // the page itself shows the pair screen
    }

    @Test fun pairingSetsAnHttpOnlyCookie() {
        val start = api.handle(req("POST", "/api/pair/start", "{}")).json()
        assertEquals(120, start.getInt("expiresIn"))
        val code = api.pairing.prompt.value!!.code
        assertEquals("Safari on iPhone", api.pairing.prompt.value!!.browser)
        val res = api.handle(req("POST", "/api/pair", "{\"pairId\":\"${start.getString("pairId")}\",\"code\":\"${code.substring(0, 3)} ${code.substring(3)}\"}"))
        assertEquals(200, res.status)
        val cookie = res.headers.first { it.first == "Set-Cookie" }.second
        assertTrue(cookie, cookie.startsWith("vesper_session=") && "HttpOnly" in cookie && "SameSite=Lax" in cookie)
        val token = cookie.substringAfter("=").substringBefore(";")
        assertTrue(api.handle(req("GET", "/api/hello", cookie = token)).json().getBoolean("paired"))
        assertTrue(api.pairing.prompt.value!!.paired)
        // Only the hash is stored.
        assertFalse(api.pairing.browsers.value.single().tokenHash.contains(token))
    }

    @Test fun schemaHasEverySectionWithValuesAndNoSecrets() {
        val t = pair(api)
        settings.putString("device.name", "Bedroom")
        settings.putBoolean("clock.seconds", true)
        secrets.set(Secrets.ANTHROPIC_API_KEY, "sk-ant-SUPERSECRET")
        secrets.set(Secrets.CALENDAR_ICS_URLS, "https://calendar.google.com/private-abc/basic.ics")
        val res = api.handle(req("GET", "/api/schema", cookie = t))
        val text = res.body.decodeToString()
        assertFalse(text, "SUPERSECRET" in text)
        assertFalse(text, "private-abc" in text)
        assertFalse(text, "built.in" in text)
        val o = JSONObject(text)
        assertEquals("Bedroom", o.getJSONObject("device").getString("name"))
        val sections = o.getJSONArray("sections")
        assertEquals(listOf("general", "screens", "clock", "alarm", "home", "accounts"), (0 until sections.length()).map { sections.getJSONObject(it).getString("id") })
        fun spec(key: String): JSONObject {
            for (i in 0 until sections.length()) {
                val a = sections.getJSONObject(i).getJSONArray("settings")
                for (j in 0 until a.length()) if (a.getJSONObject(j).getString("key") == key) return a.getJSONObject(j)
            }
            throw AssertionError(key)
        }
        assertEquals(true, spec("clock.seconds").get("value"))
        assertEquals(9, spec("alarm.snooze").getInt("value"))
        assertEquals("BOOLEAN", spec("clock.seconds").getString("type"))
        assertEquals("set", spec(Secrets.ANTHROPIC_API_KEY).getString("secret"))
        assertEquals("builtin", spec(Secrets.IMMICH_URL).getString("secret"))
        assertEquals("unset", spec(Secrets.HA_TOKEN).getString("secret"))
        assertFalse(spec(Secrets.ANTHROPIC_API_KEY).has("value"))
        assertFalse(spec(Secrets.ANTHROPIC_API_KEY).has("default"))
        assertTrue(spec(Secrets.CALENDAR_ICS_URLS).getJSONObject("hints").has("multiline"))
        assertEquals("[\"digital\",\"Digital\"]", spec("clock.face").getJSONArray("options").getJSONArray(0).toString())
        assertTrue(spec("clock.nightLux").getBoolean("advanced"))
        assertEquals(1.0, spec("alarm.snooze").getDouble("min"), 0.0)
        assertTrue(spec("weather.location").isNull("value"))
        assertEquals("AAPL", spec("stocks.symbols").getJSONArray("value").getString(0))
    }

    @Test fun putValidatesWritesAndAnswersWithNewValues() {
        val t = pair(api)
        val res = api.handle(req("PUT", "/api/settings", "{\"device.name\":\" Kitchen \",\"alarm.snooze\":12,\"clock.color.digital\":\"#64d2ff\"}", cookie = t))
        assertEquals(200, res.status)
        assertEquals("Kitchen", settings.getString("device.name", ""))
        assertEquals(12, settings.getInt("alarm.snooze", 0))
        assertEquals("#64D2FF", settings.getString("clock.color.digital", ""))
        assertEquals("Kitchen", res.json().getJSONObject("values").getJSONObject("device.name").getString("value"))
    }

    @Test fun putIsAllOrNothing() {
        val t = pair(api)
        val res = api.handle(req("PUT", "/api/settings", "{\"device.name\":\"A\",\"alarm.snooze\":99}", cookie = t))
        assertEquals(422, res.status)
        assertEquals("alarm.snooze", res.json().getString("key"))
        assertNull(settings.map["device.name"])
    }

    @Test fun unknownKeysAreRejected() {
        val t = pair(api)
        val res = api.handle(req("PUT", "/api/settings", "{\"device.name\":\"A\",\"app.demo\":false}", cookie = t))
        assertEquals(400, res.status)
        assertTrue(res.json().getString("error").contains("app.demo"))
        assertNull(settings.map["device.name"])
        assertEquals(400, api.handle(req("PUT", "/api/settings", "{}", cookie = t)).status)
        assertEquals(400, api.handle(req("PUT", "/api/settings", "[1]", cookie = t)).status)
        assertEquals(404, api.handle(req("POST", "/api/action/device.name", cookie = t)).status)
        assertEquals(404, api.handle(req("POST", "/api/action/nope", cookie = t)).status)
    }

    @Test fun secretsAreWriteOnly() {
        val t = pair(api)
        val res = api.handle(req("PUT", "/api/settings", "{\"anthropic.apiKey\":\"sk-ant-WRITEONLY\"}", cookie = t))
        assertEquals(200, res.status)
        assertFalse("WRITEONLY" in res.body.decodeToString())
        assertEquals("sk-ant-WRITEONLY", secrets.get("anthropic.apiKey"))
        assertEquals("set", res.json().getJSONObject("values").getJSONObject("anthropic.apiKey").getString("secret"))
        api.handle(req("PUT", "/api/settings", "{\"anthropic.apiKey\":null}", cookie = t))
        assertNull(secrets.get("anthropic.apiKey"))
        val change = api.changeJson("anthropic.apiKey")!!
        assertEquals(setOf("key", "secret"), change.keys().asSequence().toSet())
        assertEquals("unset", change.getString("secret"))
    }

    @Test fun locationWritesPlace() {
        val t = pair(api)
        api.handle(req("PUT", "/api/settings", "{\"weather.location\":{\"lat\":37.77,\"lon\":-122.42,\"place\":\"San Francisco, California\"}}", cookie = t))
        assertEquals("37.77,-122.42", settings.getString("weather.location", ""))
        assertEquals("San Francisco, California", settings.getString("weather.location.place", ""))
        val ch = api.changeJson("weather.location.place")!!
        assertEquals("weather.location", ch.getString("key"))
        assertEquals("San Francisco, California", ch.getJSONObject("value").getString("place"))
    }

    @Test fun actionsRun() {
        val t = pair(api)
        val res = api.handle(req("POST", "/api/action/settings.restartApp", cookie = t))
        assertEquals(200, res.status)
        assertEquals("Restarting the Echo app…", res.json().getString("message"))
        assertEquals(listOf("restart"), actions)
    }

    @Test fun crossSiteAndRebindingAreRefused() {
        val t = pair(api)
        assertEquals(403, api.handle(req("GET", "/api/schema", cookie = t, host = "evil.example.com")).status)
        assertEquals(403, api.handle(req("GET", "/", host = "evil.example.com")).status)
        assertEquals(403, api.handle(req("PUT", "/api/settings", "{\"device.name\":\"x\"}", cookie = t, headers = mapOf("Origin" to "http://evil.example.com"))).status)
        assertEquals(200, api.handle(req("PUT", "/api/settings", "{\"device.name\":\"x\"}", cookie = t, headers = mapOf("Origin" to "http://192.168.10.136:8080"))).status)
        assertEquals(415, api.handle(req("PUT", "/api/settings", "{\"device.name\":\"x\"}", cookie = t, headers = mapOf("Content-Type" to "text/plain"))).status)
    }

    @Test fun pairedBrowsersListAndForget() {
        val a = pair(api)
        Thread.sleep(1)
        val b = newApiPairAgain()
        val list = api.handle(req("GET", "/api/pairs", cookie = a)).json().getJSONArray("browsers")
        assertEquals(2, list.length())
        val other = (0 until list.length()).map { list.getJSONObject(it) }.first { !it.getBoolean("current") }
        assertEquals(200, api.handle(req("DELETE", "/api/pairs/${other.getString("id")}", cookie = a)).status)
        assertEquals(401, api.handle(req("GET", "/api/schema", cookie = b)).status)
        val me = api.pairing.browsers.value.single()
        val res = api.handle(req("DELETE", "/api/pairs/${me.id}", cookie = a))
        assertTrue(res.json().getBoolean("self"))
        assertTrue(res.headers.any { it.first == "Set-Cookie" && "Max-Age=0" in it.second })
        assertEquals(401, api.handle(req("GET", "/api/schema", cookie = a)).status)
    }

    /** A second browser pairs, past the start interval. */
    private fun newApiPairAgain(): String {
        api.pairing.dismissPrompt()
        clock[0] += 10_000
        return pair(api)
    }

    @Test fun staticAssetsWithGzipAndEtag() {
        val res = api.handle(req("GET", "/", headers = mapOf("Accept-Encoding" to "gzip, deflate")))
        assertEquals(200, res.status)
        assertEquals("text/html; charset=utf-8", res.contentType)
        assertTrue(res.headers.any { it == ("Content-Encoding" to "gzip") })
        assertTrue(res.headers.any { it.first == "Content-Security-Policy" })
        val html = GZIPInputStream(res.body.inputStream()).readBytes().decodeToString()
        assertTrue("apple-mobile-web-app-capable" in html)
        val etag = res.headers.first { it.first == "ETag" }.second
        assertEquals(304, api.handle(req("GET", "/index.html", headers = mapOf("If-None-Match" to etag))).status)
        assertEquals(200, api.handle(req("GET", "/app.js")).status)
        assertNotNull(api.handle(req("GET", "/icon-180.png")).contentType)
        assertEquals(404, api.handle(req("GET", "/nope.js")).status)
        assertEquals(404, api.handle(req("GET", "/build.gradle")).status)
        assertEquals(405, api.handle(req("POST", "/index.html", "{}")).status)
    }

}
