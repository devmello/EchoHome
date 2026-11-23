package app.vesper.feature.settings

import app.vesper.core.model.InMemorySecretStore
import app.vesper.core.model.NightMode
import app.vesper.core.model.Secrets
import app.vesper.core.model.fake.FakeAlarmStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApiTest {
    private val secrets = InMemorySecretStore()
    private val alarms = FakeAlarmStore(demo = true)
    private val world = TestWorld(secrets, alarms)
    private val api = newApi(secrets = secrets, alarms = alarms, world = world)
    private val t = pair(api)

    private fun call(method: String, path: String, body: String? = null) = api.handle(req(method, path, body, cookie = t))
    private fun json(method: String, path: String, body: String? = null) = JSONObject(call(method, path, body).body.decodeToString())

    @Test fun everyNewEndpointNeedsPairing() {
        for ((m, p) in listOf("GET" to "/api/remote", "POST" to "/api/remote/media", "POST" to "/api/remote/system", "POST" to "/api/remote/timers",
            "POST" to "/api/remote/timers/x", "POST" to "/api/remote/note", "GET" to "/api/people", "PUT" to "/api/people/maya/calendar",
            "GET" to "/api/diagnostics", "POST" to "/api/diagnostics/clear")) {
            assertEquals("$m $p", 401, api.handle(req(m, p, if (m == "GET") null else "{}")).status)
        }
    }

    @Test fun foreignOriginIsRefused() {
        val r = api.handle(req("POST", "/api/remote/note", "{\"text\":\"hi\"}", cookie = t, headers = mapOf("Origin" to "http://evil.example")))
        assertEquals(403, r.status)
        assertTrue(world.notifications.banners.value.isEmpty())
    }

    @Test fun stateWithNothingPlaying() {
        val s = json("GET", "/api/remote")
        assertTrue(s.isNull("media"))
        assertEquals(0.5, s.getJSONObject("system").getDouble("volume"), 1e-6)
        assertEquals("auto", s.getJSONObject("system").getString("night"))
        assertEquals(0, s.getJSONArray("timers").length())
        assertEquals(3, s.getInt("maxTimers"))
    }

    @Test fun nowPlayingAndTransport() {
        world.playSomething()
        world.elapsed[0] += 4_000
        val m = json("GET", "/api/remote").getJSONObject("media")
        assertEquals("Song", m.getString("title"))
        assertEquals("Maya's iPhone", m.getString("source"))
        assertEquals(54_000, m.getLong("positionMs"))
        assertTrue(m.getBoolean("playing"))
        for (a in listOf("pause", "play", "next", "previous")) assertEquals(200, call("POST", "/api/remote/media", "{\"action\":\"$a\"}").status)
        assertEquals(listOf("pause", "play", "next", "previous"), world.mediaControls.calls)
        assertEquals(422, call("POST", "/api/remote/media", "{\"action\":\"seek\"}").status)
    }

    @Test fun mediaWithNothingPlayingIs409() {
        assertEquals(409, call("POST", "/api/remote/media", "{\"action\":\"play\"}").status)
    }

    @Test fun systemControls() {
        assertEquals(200, call("POST", "/api/remote/system", "{\"volume\":0.8,\"brightness\":0.25,\"dnd\":true,\"night\":\"on\"}").status)
        val s = world.system.state.value
        assertEquals(0.8f, s.volume, 1e-6f); assertEquals(0.25f, s.brightness, 1e-6f)
        assertTrue(s.doNotDisturb); assertEquals(NightMode.ON, s.nightMode)
    }

    @Test fun systemValidationIsAllOrNothing() {
        for (bad in listOf("{}", "{\"volume\":1.5}", "{\"volume\":-0.1}", "{\"volume\":\"loud\"}", "{\"dnd\":\"yes\"}", "{\"night\":\"red\"}", "{\"wifi\":true}",
            "{\"dnd\":true,\"volume\":2}", "not json")) {
            assertEquals(bad, 422, call("POST", "/api/remote/system", bad).status)
        }
        assertFalse(world.system.state.value.doNotDisturb)
    }

    @Test fun timersStartPauseResumeCancel() {
        val started = json("POST", "/api/remote/timers", "{\"seconds\":300,\"label\":\"Pasta\"}").getJSONObject("timer")
        val id = started.getString("id")
        assertEquals(300_000, started.getLong("remainingMs"))
        world.elapsed[0] += 60_000
        val listed = json("GET", "/api/remote").getJSONArray("timers").getJSONObject(0)
        assertEquals("Pasta", listed.getString("label")); assertEquals(240_000, listed.getLong("remainingMs")); assertEquals("running", listed.getString("state"))

        assertEquals(200, call("POST", "/api/remote/timers/$id", "{\"action\":\"pause\"}").status)
        world.elapsed[0] += 30_000
        assertEquals(240_000, json("GET", "/api/remote").getJSONArray("timers").getJSONObject(0).getLong("remainingMs"))
        call("POST", "/api/remote/timers/$id", "{\"action\":\"resume\"}")
        call("POST", "/api/remote/timers/$id", "{\"action\":\"add\",\"seconds\":60}")
        assertEquals(300_000, json("GET", "/api/remote").getJSONArray("timers").getJSONObject(0).getLong("remainingMs"))
        assertEquals(200, call("POST", "/api/remote/timers/$id", "{\"action\":\"cancel\"}").status)
        assertEquals(0, world.timers.timers.value.size)
        assertEquals(404, call("POST", "/api/remote/timers/$id", "{\"action\":\"cancel\"}").status)
    }

    @Test fun timerLimitsAndValidation() {
        repeat(3) { assertEquals(200, call("POST", "/api/remote/timers", "{\"seconds\":60}").status) }
        assertEquals(409, call("POST", "/api/remote/timers", "{\"seconds\":60}").status)
        for (bad in listOf("{}", "{\"seconds\":0}", "{\"seconds\":90000}", "{\"seconds\":1.5}", "{\"seconds\":60,\"label\":\"${"x".repeat(41)}\"}", "{\"seconds\":60,\"label\":5}")) {
            assertEquals(bad, 422, call("POST", "/api/remote/timers", bad).status)
        }
        val id = world.timers.timers.value.first().id
        assertEquals(422, call("POST", "/api/remote/timers/$id", "{\"action\":\"explode\"}").status)
        assertEquals(422, call("POST", "/api/remote/timers/$id", "{\"action\":\"add\"}").status)
    }

    @Test fun noteBecomesABanner() {
        assertEquals(200, call("POST", "/api/remote/note", "{\"text\":\"  Dinner is ready  \"}").status)
        val b = world.notifications.banners.value.single()
        assertEquals("Note", b.appName); assertEquals("Note", b.title); assertEquals("message", b.glyph)
        assertEquals("Dinner is ready", b.body)
        assertFalse(b.critical)
        assertEquals(1_795_330_000_000L, b.postedAtMillis)
    }

    @Test fun noteValidationAndRateLimit() {
        for (bad in listOf("{}", "{\"text\":\"\"}", "{\"text\":\"   \"}", "{\"text\":\"${"x".repeat(281)}\"}", "{\"text\":\"a\\u0007b\"}", "{\"text\":3}")) {
            assertEquals(bad, 422, call("POST", "/api/remote/note", bad).status)
        }
        assertEquals(200, call("POST", "/api/remote/note", "{\"text\":\"line one\\nline two\"}").status)
        repeat(19) { assertEquals(200, call("POST", "/api/remote/note", "{\"text\":\"n$it\"}").status) }
        assertEquals(429, call("POST", "/api/remote/note", "{\"text\":\"one too many\"}").status)
        world.wall[0] += 61_000
        assertEquals(200, call("POST", "/api/remote/note", "{\"text\":\"later\"}").status)
    }

    @Test fun nextAlarmWithReason() {
        val s = json("GET", "/api/remote")
        assertEquals("07:00", s.getJSONObject("nextAlarm").getString("time"))
        assertFalse(s.getJSONObject("nextAlarm").has("reason"))
    }

    // ---- people's own preferences ----

    @Test fun personPrefsListAndSet() {
        fun maya() = json("GET", "/api/people").getJSONArray("people").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("id") == "maya" } }
        assertEquals("", maya().getString("wallpaper"))
        assertEquals("", maya().getString("nightMode"))
        assertEquals("analog", maya().getString("face"))
        val r = call("PUT", "/api/people/maya/prefs", JSONObject().put("wallpaper", "Dusk").put("face", "glass").put("alarmSound", "chime").toString())
        assertEquals(200, r.status)
        val p = world.profiles.profiles.value.first { it.id == "maya" }
        assertEquals("Dusk", p.wallpaper); assertEquals("glass", p.faceId); assertEquals("chime", p.alarmSound); assertNull(p.nightMode)
        assertEquals("Dusk", maya().getString("wallpaper"))
        // "" (or null) = back to Everyone's.
        assertEquals(200, call("PUT", "/api/people/maya/prefs", "{\"wallpaper\":\"\",\"alarmSound\":null}").status)
        assertNull(world.profiles.profiles.value.first { it.id == "maya" }.wallpaper)
        assertNull(world.profiles.profiles.value.first { it.id == "maya" }.alarmSound)
        assertEquals("glass", world.profiles.profiles.value.first { it.id == "maya" }.faceId)
    }

    @Test fun personPrefsValidation() {
        assertEquals(422, call("PUT", "/api/people/maya/prefs", "{\"wallpaper\":\"Neon\"}").status)
        assertEquals(422, call("PUT", "/api/people/maya/prefs", "{\"nightMode\":\"sometimes\"}").status)
        assertEquals(422, call("PUT", "/api/people/maya/prefs", "{\"face\":\"\"}").status)
        assertEquals(422, call("PUT", "/api/people/maya/prefs", "{\"wallpaper\":5}").status)
        assertEquals(422, call("PUT", "/api/people/maya/prefs", "{\"color\":\"#FFFFFF\"}").status)
        assertEquals(404, call("PUT", "/api/people/shared/prefs", "{\"wallpaper\":\"Dusk\"}").status)
        assertEquals(401, api.handle(req("PUT", "/api/people/maya/prefs", "{\"wallpaper\":\"Dusk\"}")).status)
        val p = world.profiles.profiles.value.first { it.id == "maya" }
        assertNull(p.wallpaper); assertEquals("analog", p.faceId)
    }

    // ---- people's calendars ----

    @Test fun peopleListOmitsSharedAndNeverShowsUrls() {
        val body = call("GET", "/api/people").body.decodeToString()
        val people = JSONObject(body).getJSONArray("people")
        assertEquals(listOf("maya", "sam"), (0 until people.length()).map { people.getJSONObject(it).getString("id") })
        assertEquals("unset", people.getJSONObject(0).getString("calendar"))
        assertEquals("#FF375F", people.getJSONObject(0).getString("color"))
    }

    @Test fun calendarIsWriteOnly() {
        val secret = "https://calendar.example.com/ical/private-token-123/basic.ics"
        val put = call("PUT", "/api/people/maya/calendar", JSONObject().put("urls", "$secret\nwebcal://x.example.org/a.ics").toString())
        assertEquals(200, put.status)
        assertEquals("set", JSONObject(put.body.decodeToString()).getString("calendar"))
        assertEquals("$secret\nwebcal://x.example.org/a.ics", secrets.get(Secrets.calendarKeyFor("maya")))
        assertNull(secrets.get(Secrets.calendarKeyFor("sam")))
        // No reply, listing or schema contains the value.
        for (r in listOf(put, call("GET", "/api/people"), call("GET", "/api/schema"), call("GET", "/api/remote"))) {
            assertFalse(r.body.decodeToString().contains("private-token-123"))
        }
        assertEquals("set", JSONObject(call("GET", "/api/people").body.decodeToString()).getJSONArray("people").getJSONObject(0).getString("calendar"))
        // Clear.
        assertEquals(200, call("PUT", "/api/people/maya/calendar", "{\"urls\":null}").status)
        assertNull(secrets.get(Secrets.calendarKeyFor("maya")))
        assertEquals("unset", JSONObject(call("GET", "/api/people").body.decodeToString()).getJSONArray("people").getJSONObject(0).getString("calendar"))
    }

    @Test fun calendarValidation() {
        val bad = listOf("not a url", "ftp://x.example/a.ics", "javascript:alert(1)", "https://", "https:///x", "https://x.example/a b\u0001",
            (1..11).joinToString("\n") { "https://x.example/$it.ics" }, "https://x.example/" + "a".repeat(2100))
        for (v in bad) {
            val r = call("PUT", "/api/people/maya/calendar", JSONObject().put("urls", v).toString())
            assertEquals(v.take(30), 422, r.status)
            assertFalse(r.body.decodeToString().contains("x.example"))
        }
        assertEquals(422, call("PUT", "/api/people/maya/calendar", "{\"urls\":5}").status)
        assertEquals(422, call("PUT", "/api/people/maya/calendar", "{\"urls\":\"https://a.example/x.ics\",\"extra\":1}").status)
        assertEquals(404, call("PUT", "/api/people/nobody/calendar", "{\"urls\":\"https://a.example/x.ics\"}").status)
        assertEquals(404, call("PUT", "/api/people/shared/calendar", "{\"urls\":\"https://a.example/x.ics\"}").status)
        assertNull(secrets.get(Secrets.calendarKeyFor("maya")))
    }

    // ---- diagnostics ----

    @Test fun diagnosticsListNewestFirstAndClear() {
        val e = json("GET", "/api/diagnostics").getJSONArray("entries")
        assertEquals(2, e.length())
        assertEquals("hang", e.getJSONObject(0).getString("kind"))
        assertEquals("crash", e.getJSONObject(1).getString("kind"))
        assertTrue(e.getJSONObject(1).getString("stack").contains("Clock.draw"))
        assertEquals(0, json("POST", "/api/diagnostics/clear", "{}").getJSONArray("entries").length())
        assertEquals(0, world.crashLog.entries.value.size)
    }

    @Test fun unknownRemotePathIs404() {
        assertEquals(404, call("GET", "/api/remote/nope").status)
        assertEquals(404, call("DELETE", "/api/remote").status)
    }
}
