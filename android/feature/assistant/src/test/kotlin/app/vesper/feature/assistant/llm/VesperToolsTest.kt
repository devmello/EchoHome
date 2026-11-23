package app.vesper.feature.assistant.llm

import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.core.model.Secrets
import app.vesper.feature.assistant.EngineTest
import app.vesper.feature.assistant.pipeline.ResultCard
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VesperToolsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings())
    private val tools = VesperTools({ services }, main = Dispatchers.Unconfined, zone = { ZoneId.of("UTC") }, elapsed = { 0L }, wall = { 0L })

    private class Controls(override val canSkip: Boolean, override val canPause: Boolean) : MediaControls {
        val calls = ArrayList<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
    }

    @Test fun `no home tools without Home Assistant`() {
        assertFalse(tools.specs().any { it.name.startsWith("home_") })
        assertTrue(tools.specs().any { it.name == "set_brightness" })
    }

    @Test fun `AirPlay that can't skip says so instead of pretending`() = runBlocking {
        val c = Controls(canSkip = false, canPause = true)
        services.media.publish("airplay", MediaState("Afterglow", "Kito Rae", source = MediaSourceKind.AIRPLAY, sourceLabel = "Maya’s iPhone", playing = true), c)
        val skip = tools.run("media_control", JSONObject().put("action", "next"))
        assertTrue(skip.isError)
        assertEquals("CANT_SKIP", skip.content.getString("error"))
        assertTrue(c.calls.isEmpty())
        val pause = tools.run("media_control", JSONObject().put("action", "pause"))
        assertFalse(pause.isError)
        assertEquals(listOf("pause"), c.calls)
        assertEquals(ResultCard.Icon.PAUSE, (pause.card as ResultCard.Action).icon)
        val np = tools.run("now_playing", JSONObject())
        assertFalse(np.content.getBoolean("can_skip"))
    }

    @Test fun `brightness and volume go to system controls`() = runBlocking {
        tools.run("set_brightness", JSONObject().put("percent", 80))
        assertEquals(0.8f, services.system.state.value.brightness, 1e-3f)
        val d = tools.run("get_display", JSONObject())
        assertEquals(80, d.content.getInt("brightness_percent"))
    }

    @Test fun `timers and alarms`() = runBlocking {
        val t = tools.run("set_timer", JSONObject().put("duration_seconds", 600).put("label", "Pasta"))
        assertFalse(t.isError)
        assertEquals("10 min · started", (t.card as ResultCard.Action).state)
        assertEquals(1, services.timers.timers.value.size)
        val c = tools.run("cancel_timer", JSONObject())
        assertFalse(c.isError)
        val a = tools.run("set_alarm", JSONObject().put("hour", 6).put("minute", 40))
        assertEquals("6:40 AM", (a.card as ResultCard.Action).state)
        val id = a.content.getJSONObject("alarm").getString("alarm_id")
        assertTrue(services.alarms.alarms.value.any { it.id == id })
        // Nobody recognised: a shared alarm.
        assertEquals(null, services.alarms.alarms.value.first { it.id == id }.profileId)
        assertEquals("shared", a.content.getJSONObject("alarm").getString("owner"))
    }

    @Test fun `set_alarm belongs to the recognised person unless shared is asked for`() = runBlocking {
        services.profiles.switchTo("maya", app.vesper.core.model.SwitchReason.FACE)
        val mine = tools.run("set_alarm", JSONObject().put("hour", 6).put("minute", 15).put("label", "Run"))
        val mineId = mine.content.getJSONObject("alarm").getString("alarm_id")
        assertEquals("maya", services.alarms.alarms.value.first { it.id == mineId }.profileId)
        assertEquals("Maya", mine.content.getJSONObject("alarm").getString("owner"))
        assertEquals("6:15 AM · Maya", (mine.card as ResultCard.Action).state)
        val shared = tools.run("set_alarm", JSONObject().put("hour", 7).put("minute", 30).put("shared", true))
        val sharedId = shared.content.getJSONObject("alarm").getString("alarm_id")
        assertEquals(null, services.alarms.alarms.value.first { it.id == sharedId }.profileId)
        // list_alarms says whose each one is and who's recognised.
        val list = tools.run("list_alarms", JSONObject()).content
        assertEquals("Maya", list.getString("recognized"))
        val owners = (0 until list.getJSONArray("alarms").length()).map { list.getJSONArray("alarms").getJSONObject(it) }
            .associate { it.getString("alarm_id") to it.getString("owner") }
        assertEquals("Maya", owners[mineId]); assertEquals("shared", owners[sharedId])
    }

    @Test fun `alarm owner rule`() {
        assertEquals("maya", VesperTools.alarmOwnerFor("maya", shared = false))
        assertEquals(null, VesperTools.alarmOwnerFor("maya", shared = true))
        assertEquals(null, VesperTools.alarmOwnerFor(app.vesper.core.model.Profile.SHARED_ID, shared = false))
    }
}
