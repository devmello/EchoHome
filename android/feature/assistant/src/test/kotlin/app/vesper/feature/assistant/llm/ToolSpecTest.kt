package app.vesper.feature.assistant.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSpecTest {
    private fun spec(name: String) = (VesperTools.BASE + EchoTools.SPECS + VesperTools.HOME).first { it.name == name }

    @Test fun `valid inputs pass`() {
        assertNull(spec("set_timer").validate(JSONObject().put("duration_seconds", 300)))
        assertNull(spec("set_alarm").validate(JSONObject().put("hour", 6).put("minute", 40).put("days", JSONArray().put("MON").put("FRI"))))
        assertNull(spec("media_control").validate(JSONObject().put("action", "pause")))
        assertNull(spec("list_timers").validate(JSONObject()))
    }

    @Test fun `bad inputs are caught`() {
        assertNotNull(spec("set_timer").validate(JSONObject()))
        assertNotNull(spec("set_timer").validate(JSONObject().put("duration_seconds", "ten")))
        assertNotNull(spec("set_timer").validate(JSONObject().put("duration_seconds", 0)))
        assertNotNull(spec("set_alarm").validate(JSONObject().put("hour", 24).put("minute", 0)))
        assertNotNull(spec("set_alarm").validate(JSONObject().put("hour", 7).put("minute", 0).put("days", JSONArray().put("Monday"))))
        assertNotNull(spec("media_control").validate(JSONObject().put("action", "shuffle")))
        assertNotNull(spec("get_weather").validate(JSONObject().put("city", "Paris")))
    }

    @Test fun `schema json has the eager streaming flag and closed objects`() {
        val j = spec("home_control").toJson()
        assertEquals(true, j.getBoolean("eager_input_streaming"))
        assertEquals(false, j.getJSONObject("input_schema").getBoolean("additionalProperties"))
        assertEquals(2, j.getJSONObject("input_schema").getJSONArray("required").length())
    }

    @Test fun `formatting helpers`() {
        assertEquals("6:40 AM", VesperTools.clock(6, 40))
        assertEquals("12:05 PM", VesperTools.clock(12, 5))
        assertEquals("12:00 AM", VesperTools.clock(0, 0))
        assertEquals("10 min", VesperTools.duration(600))
        assertEquals("1 h 30 min", VesperTools.duration(5400))
        assertEquals("45 s", VesperTools.duration(45))
    }

    @Test fun `every tool schema is a well formed JSON schema`() {
        val all = VesperTools.BASE + EchoTools.SPECS + VesperTools.HOME
        assertEquals("duplicate tool names", all.size, all.map { it.name }.toSet().size)
        val types = setOf("string", "integer", "number", "boolean", "array")
        for (s in all) {
            val j = s.toJson()
            assertTrue(s.name, j.getString("name").matches(Regex("[a-z_]{1,64}")))
            assertTrue(s.name, j.getString("description").isNotBlank())
            val schema = j.getJSONObject("input_schema")
            assertEquals("object", schema.getString("type"))
            val props = schema.getJSONObject("properties")
            for (r in s.required) assertTrue("${s.name}: required $r not in properties", props.has(r))
            for (k in props.keys()) {
                val p = props.getJSONObject(k)
                assertTrue("${s.name}.$k type", p.getString("type") in types)
                assertTrue("${s.name}.$k description", p.getString("description").isNotBlank())
                p.optJSONArray("enum")?.let { assertTrue("${s.name}.$k enum", it.length() > 0) }
                if (p.getString("type") == "array") assertTrue("${s.name}.$k items", p.getJSONObject("items").has("type"))
            }
            // The whole thing must serialize and parse back.
            JSONObject(j.toString())
        }
    }

    @Test fun `new tools validate their input`() {
        assertNull(spec("delete_alarm").validate(JSONObject().put("alarm_id", "a").put("confirmed", true)))
        assertNotNull(spec("delete_alarm").validate(JSONObject().put("alarm_id", "a")))
        assertNull(spec("set_alarm").validate(JSONObject().put("hour", 7).put("minute", 0).put("leave_by", true).put("ready_minutes", 30).put("skip_holidays", true)))
        assertNotNull(spec("set_alarm").validate(JSONObject().put("hour", 7).put("minute", 0).put("ready_minutes", 999)))
        assertNull(spec("open_screen").validate(JSONObject().put("screen", "weather")))
        assertNotNull(spec("open_screen").validate(JSONObject().put("screen", "alarm")))
        assertNotNull(spec("set_night_mode").validate(JSONObject().put("mode", "red")))
        assertNotNull(spec("set_do_not_disturb").validate(JSONObject().put("on", "yes")))
        assertNotNull(spec("show_note").validate(JSONObject().put("text", "x".repeat(201))))
        assertNull(spec("show_note").validate(JSONObject().put("text", "Call Sam")))
        assertNotNull(spec("get_travel_time").validate(JSONObject().put("destination", "x").put("depart_in_minutes", -5)))
    }
}
