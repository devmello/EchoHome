package app.vesper.feature.settings

import app.vesper.core.model.fake.FakeAlarmStore
import app.vesper.feature.settings.api.AlarmsApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class AlarmsApiTest {
    private val store = FakeAlarmStore(demo = true)
    private val api = newApi(alarms = store)
    private val t = pair(api)

    private fun call(method: String, path: String, body: String? = null) = api.handle(req(method, path, body, cookie = t))
    private fun json(method: String, path: String, body: String? = null) = JSONObject(call(method, path, body).body.decodeToString())

    @Test fun needsPairing() {
        assertEquals(401, api.handle(req("GET", "/api/alarms")).status)
        assertEquals(401, api.handle(req("POST", "/api/alarms", "{\"time\":\"07:00\"}")).status)
    }

    @Test fun listsSortedByTime() {
        val list = json("GET", "/api/alarms").getJSONArray("alarms")
        assertEquals(2, list.length())
        assertEquals("06:30", list.getJSONObject(0).getString("time"))
        assertEquals(6, list.getJSONObject(0).getInt("hour"))
        assertEquals("[1,2,3,4,5]", list.getJSONObject(0).getJSONArray("days").toString())
        assertFalse(list.getJSONObject(0).getBoolean("enabled"))
    }

    @Test fun whoseAlarm() {
        val id = json("POST", "/api/alarms", "{\"time\":\"06:15\",\"profileId\":\"maya\"}").getJSONObject("alarm").getString("id")
        assertEquals("maya", store.alarms.value.first { it.id == id }.profileId)
        assertEquals("maya", json("GET", "/api/alarms").getJSONArray("alarms").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .first { it.getString("id") == id }.getString("profileId"))
        assertEquals(200, call("PUT", "/api/alarms/$id", "{\"label\":\"Run\"}").status)
        assertEquals("maya", store.alarms.value.first { it.id == id }.profileId) // other edits keep the owner
        assertEquals(200, call("PUT", "/api/alarms/$id", "{\"profileId\":null}").status)
        assertNull(store.alarms.value.first { it.id == id }.profileId)
        assertEquals(422, call("PUT", "/api/alarms/$id", "{\"profileId\":\"../etc\"}").status)
        assertEquals(422, call("PUT", "/api/alarms/$id", "{\"profileId\":7}").status)
    }

    @Test fun createEditToggleDelete() {
        val created = json("POST", "/api/alarms", "{\"time\":\"10:45\",\"days\":[6,7],\"label\":\"Weekend\",\"enabled\":false,\"sunrise\":false}").getJSONObject("alarm")
        val id = created.getString("id")
        val a = store.alarms.value.first { it.id == id }
        assertEquals(10, a.hour); assertEquals(45, a.minute)
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), a.days)
        assertEquals("Weekend", a.label)
        assertFalse(a.enabled); assertFalse(a.sunrise)

        assertEquals(200, call("PUT", "/api/alarms/$id", "{\"time\":\"11:05\",\"days\":[\"MON\"],\"label\":\"  \"}").status)
        store.alarms.value.first { it.id == id }.let {
            assertEquals(11, it.hour); assertEquals(5, it.minute)
            assertEquals(setOf(DayOfWeek.MONDAY), it.days)
            assertEquals("Alarm", it.label)
            assertFalse(it.enabled)
        }
        call("PUT", "/api/alarms/$id", "{\"enabled\":true}")
        assertTrue(store.alarms.value.first { it.id == id }.enabled)
        assertEquals(200, call("DELETE", "/api/alarms/$id").status)
        assertNull(store.alarms.value.firstOrNull { it.id == id })
        assertEquals(404, call("DELETE", "/api/alarms/$id").status)
        assertEquals(404, call("PUT", "/api/alarms/nope", "{\"enabled\":true}").status)
    }

    @Test fun validates() {
        for (bad in listOf("{\"time\":\"25:00\"}", "{\"time\":\"7\"}", "{\"days\":[0]}", "{\"days\":[8]}", "{\"days\":[\"XYZ\"]}", "{\"days\":\"MON\"}",
                "{\"label\":\"${"x".repeat(41)}\"}", "{\"label\":\"a\\nb\"}", "{\"enabled\":\"yes\"}", "{\"hour\":7.5}", "{\"sound\":\"chime\"}", "not json")) {
            assertEquals(bad, 422, call("POST", "/api/alarms", bad).status)
        }
        assertEquals(2, store.alarms.value.size)
    }

    @Test fun newAlarmDefaults() {
        val a = json("POST", "/api/alarms", "{\"hour\":11,\"minute\":30,\"enabled\":false}").getJSONObject("alarm")
        assertEquals("11:30", a.getString("time"))
        assertEquals("Alarm", a.getString("label"))
        assertEquals(0, a.getJSONArray("days").length())
        assertTrue(a.getBoolean("sunrise"))
    }

    @Test fun leaveByAndHolidayFields() {
        val a = json("POST", "/api/alarms", "{\"time\":\"06:45\",\"leaveBy\":true,\"readyMinutes\":30,\"skipHolidays\":true}").getJSONObject("alarm")
        assertTrue(a.getBoolean("leaveBy")); assertEquals(30, a.getInt("readyMinutes")); assertTrue(a.getBoolean("skipHolidays"))
        val stored = store.alarms.value.first { it.id == a.getString("id") }
        assertTrue(stored.leaveBy); assertEquals(30, stored.readyMinutes); assertTrue(stored.skipHolidays)
        call("PUT", "/api/alarms/${stored.id}", "{\"leaveBy\":false}")
        assertFalse(store.alarms.value.first { it.id == stored.id }.leaveBy)
        val defaults = json("POST", "/api/alarms", "{\"time\":\"08:00\"}").getJSONObject("alarm")
        assertFalse(defaults.getBoolean("leaveBy")); assertEquals(45, defaults.getInt("readyMinutes")); assertFalse(defaults.getBoolean("skipHolidays"))
        for (bad in listOf("{\"readyMinutes\":181}", "{\"readyMinutes\":-1}", "{\"leaveBy\":\"yes\"}", "{\"skipHolidays\":1}")) {
            assertEquals(bad, 422, call("POST", "/api/alarms", bad).status)
        }
    }

    @Test fun nextCarriesReason() {
        val alarm = app.vesper.core.model.Alarm("a", 7, 0)
        val l = AlarmsApi.listJson(listOf(alarm), app.vesper.core.model.NextAlarm(alarm, 123L, "Earlier for Dentist 9:00 · 25 min drive"))
        assertEquals("Earlier for Dentist 9:00 · 25 min drive", l.getJSONObject("next").getString("reason"))
        assertFalse(AlarmsApi.listJson(listOf(alarm), app.vesper.core.model.NextAlarm(alarm, 123L)).getJSONObject("next").has("reason"))
    }
}
