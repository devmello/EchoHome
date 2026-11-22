package app.vesper.feature.assistant.llm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolSpecTest {
    private fun spec(name: String) = (VesperTools.BASE + VesperTools.HOME).first { it.name == name }

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
}
