package app.vesper.feature.assistant.llm

import app.vesper.core.model.BedtimeReport
import app.vesper.core.model.BedtimeRequest
import app.vesper.core.model.Secrets
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.SleepState
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.EngineTest
import app.vesper.feature.assistant.pipeline.DemoScripts
import app.vesper.feature.assistant.pipeline.ResultCard
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepToolsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val zone = ZoneId.of("UTC")
    private val alarmAt = LocalDateTime.parse("2026-11-24T07:00:00").atZone(zone).toInstant().toEpochMilli()
    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings())
    private val tools = VesperTools({ services }, main = Dispatchers.Unconfined, zone = { zone }, elapsed = { 0L }, wall = { alarmAt - 8 * 3_600_000L })

    private class FakeSleep : SleepSounds {
        val s = MutableStateFlow(SleepState())
        override val state: StateFlow<SleepState> = s
        var lastRequest: BedtimeRequest? = null
        var report: BedtimeReport? = null
        var grant = true
        override fun play(sound: SleepSound?, timerMinutes: Int?): Boolean {
            if (!grant) return false
            s.value = SleepState(sound ?: SleepSound.RAIN, true, timerMinutes ?: 30, 1L); return true
        }
        override fun stop() { s.value = SleepState() }
        override fun setTimer(minutes: Int) { s.value = s.value.copy(timerMinutes = minutes) }
        override fun bedtime(request: BedtimeRequest): BedtimeReport { lastRequest = request; return report!! }
    }

    private val sleep = FakeSleep()
    private fun call(name: String, input: JSONObject = JSONObject()) = runBlocking { tools.run(name, input) }

    @Test fun `specs are offered and valid`() {
        val names = tools.specs().map { it.name }
        assertTrue("bedtime" in names && "sleep_sound" in names)
        val bed = tools.specs().first { it.name == "bedtime" }
        assertNull(bed.validate(JSONObject()))
        assertNull(bed.validate(JSONObject().put("sound", "ocean").put("timer_minutes", 60)))
        assertTrue(bed.validate(JSONObject().put("sound", "thunder")) != null)
        val ss = tools.specs().first { it.name == "sleep_sound" }
        assertTrue(ss.validate(JSONObject()) != null) // action required
    }

    @Test fun `bedtime reports the alarm and what it did`() {
        services.sleep = sleep
        sleep.report = BedtimeReport(alarmAt, "Wake up", true, alarmAt, true, SleepSound.RAIN, 30, null)
        val r = call("bedtime")
        assertFalse(r.isError)
        assertEquals(BedtimeRequest(), sleep.lastRequest) // no overrides unless asked
        assertEquals("2026-11-24T07:00", r.content.getString("next_alarm"))
        assertEquals("Wake up", r.content.getString("alarm_label"))
        assertTrue(r.content.getBoolean("do_not_disturb"))
        assertEquals("2026-11-24T07:00", r.content.getString("do_not_disturb_until"))
        assertTrue(r.content.getBoolean("night_mode"))
        assertEquals("Rain", r.content.getString("sleep_sound"))
        assertEquals(30, r.content.getInt("timer_minutes"))
        val card = r.card as ResultCard.Action
        assertEquals("Good night", card.name)
        assertEquals("Alarm 7:00 AM · Rain 30 min", card.state)
    }

    @Test fun `bedtime passes the user's overrides and says when no alarm is set`() {
        services.sleep = sleep
        sleep.report = BedtimeReport(null, null, true, alarmAt, true, null, 0, 0.1f, listOf("Sleep sound"))
        val r = call("bedtime", JSONObject().put("sound", "ocean").put("timer_minutes", 90).put("dim", true))
        assertEquals(BedtimeRequest(sound = SleepSound.OCEAN, timerMinutes = 90, dim = true), sleep.lastRequest)
        assertTrue(r.content.isNull("next_alarm"))
        assertEquals(10, r.content.getInt("brightness_percent"))
        assertEquals("Sleep sound", r.content.getJSONArray("failed").getString(0))
        assertEquals("No alarm", (r.card as ResultCard.Action).state)
    }

    @Test fun `without the sleep feature it says so`() {
        val r = call("bedtime")
        assertTrue(r.isError)
        assertEquals("NOT_AVAILABLE", r.content.getString("error"))
    }

    @Test fun `sleep_sound plays, retimes and stops`() {
        services.sleep = sleep
        val r = call("sleep_sound", JSONObject().put("action", "play").put("sound", "brown").put("timer_minutes", 60))
        assertFalse(r.isError)
        assertEquals(SleepSound.BROWN, sleep.s.value.sound)
        assertEquals("Brown noise", r.content.getString("playing"))
        assertEquals(60, r.content.getInt("timer_minutes"))
        call("sleep_sound", JSONObject().put("action", "set_timer").put("timer_minutes", 15))
        assertEquals(15, sleep.s.value.timerMinutes)
        val st = call("sleep_sound", JSONObject().put("action", "stop"))
        assertEquals("Brown noise", st.content.getString("stopped"))
        assertNull(sleep.s.value.sound)
        assertEquals("NOTHING_PLAYING", call("sleep_sound", JSONObject().put("action", "set_timer").put("timer_minutes", 15)).content.getString("error"))
        sleep.grant = false
        assertEquals("FAILED", call("sleep_sound", JSONObject().put("action", "play")).content.getString("error"))
    }

    @Test fun `the good night demo exists and only shows`() {
        val d = DemoScripts.byId("e")
        assertEquals("e", d.id)
        assertTrue(d.answer.startsWith("Good night."))
        assertFalse(DemoScripts.chips.any { it.id == "e" })
    }
}
