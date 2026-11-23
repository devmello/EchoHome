package app.vesper.feature.assistant.llm

import app.vesper.core.model.RadioControl
import app.vesper.core.model.RadioNowPlaying
import app.vesper.core.model.RadioPlayResult
import app.vesper.core.model.Secrets
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.EngineTest
import app.vesper.feature.assistant.pipeline.ResultCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RadioToolsTest {
    init { Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher()) }
    @org.junit.After fun tearDown() = Dispatchers.resetMain()

    private class FakeRadio : RadioControl {
        val queries = ArrayList<String>()
        override val nowPlaying = MutableStateFlow<RadioNowPlaying?>(null)
        override suspend fun play(query: String): RadioPlayResult {
            queries += query
            if (query == "nothing") return RadioPlayResult(false, error = "NO_MATCH", message = "No station found")
            nowPlaying.value = RadioNowPlaying("Night Swim Jazz")
            return RadioPlayResult(true, "Night Swim Jazz", "Jazz · France · MP3 · 192 kbps")
        }
        override fun stop(): Boolean { val was = nowPlaying.value != null; nowPlaying.value = null; return was }
    }

    private val radio = FakeRadio()
    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), EngineTest.FakeSettings()).apply { this.radio = this@RadioToolsTest.radio }
    private val tools = VesperTools({ services }, main = Dispatchers.Unconfined)

    private fun run(name: String, input: JSONObject = JSONObject()) = runBlocking {
        assertNull(tools.specs().first { it.name == name }.validate(input))
        tools.run(name, input)
    }

    @Test fun specs() {
        val names = tools.specs().map { it.name }
        assertTrue("play_radio" in names && "stop_radio" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun playAndStop() {
        val r = run("play_radio", JSONObject().put("query", "jazz from France"))
        assertFalse(r.isError)
        assertEquals("Night Swim Jazz", r.content.getString("station"))
        assertEquals(ResultCard.Media("Night Swim Jazz", "Jazz · France · MP3 · 192 kbps"), r.card)
        assertEquals(listOf("jazz from France"), radio.queries)
        val s = run("stop_radio")
        assertTrue(s.content.getBoolean("was_playing"))
        assertEquals(ResultCard.Action("Night Swim Jazz", "Stopped", ResultCard.Icon.PAUSE), s.card)
        assertFalse(run("stop_radio").content.getBoolean("was_playing"))
    }

    @Test fun noMatchIsAnError() {
        val r = run("play_radio", JSONObject().put("query", "nothing"))
        assertTrue(r.isError)
        assertEquals("NO_MATCH", r.content.getString("error"))
    }

    @Test fun withoutRadio() {
        services.radio = app.vesper.core.model.NoRadio
        val r = run("play_radio", JSONObject().put("query", "jazz"))
        assertTrue(r.isError)
        assertEquals("NOT_AVAILABLE", r.content.getString("error"))
    }
}
