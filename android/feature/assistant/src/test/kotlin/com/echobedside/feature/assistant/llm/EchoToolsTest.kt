package com.echobedside.feature.assistant.llm

import com.echobedside.core.model.EchoConfig
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.MediaState
import com.echobedside.core.model.Secrets
import com.echobedside.feature.assistant.EngineTest
import com.echobedside.feature.assistant.pipeline.ResultCard
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
class EchoToolsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val services = EchoServices(EchoConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings())
    private val tools = EchoTools({ services }, main = Dispatchers.Unconfined, zone = { ZoneId.of("UTC") }, elapsed = { 0L }, wall = { 0L })

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
    }
}
