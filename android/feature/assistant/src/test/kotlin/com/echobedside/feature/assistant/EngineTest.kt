package com.echobedside.feature.assistant

import android.os.Bundle
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.DefaultIslandManager
import com.echobedside.core.model.DefaultSystemStateStore
import com.echobedside.core.model.Navigator
import com.echobedside.core.model.SettingsStore
import com.echobedside.feature.assistant.audio.Ducker
import com.echobedside.feature.assistant.audio.MicSource
import com.echobedside.feature.assistant.llm.AssistantAgent
import com.echobedside.feature.assistant.llm.ClaudeApi
import com.echobedside.feature.assistant.llm.ClaudeException
import com.echobedside.feature.assistant.llm.ClaudeMessage
import com.echobedside.feature.assistant.llm.ClaudeModels
import com.echobedside.feature.assistant.llm.ToolHost
import com.echobedside.feature.assistant.llm.ToolOutcome
import com.echobedside.feature.assistant.llm.ToolSpec
import com.echobedside.feature.assistant.pipeline.Phase
import com.echobedside.feature.assistant.voice.Speaker
import com.echobedside.feature.assistant.voice.SttSession
import com.echobedside.feature.assistant.voice.VoiceStack
import com.echobedside.feature.assistant.voice.WakeWordSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {

    class FakeMic : MicSource {
        var cb: ((ByteArray, Float) -> Unit)? = null
        var starts = 0
        override val running get() = cb != null
        override fun start(onChunk: (ByteArray, Float) -> Unit): Boolean { cb = onChunk; starts++; return true }
        override fun stop() { cb = null }
        fun chunk(loud: Boolean = true) = cb?.invoke(ByteArray(1280) { if (loud && it % 2 == 1) 0x30 else 0 }, if (loud) -15f else -80f)
    }

    class FakeVoice(var stt: Boolean = true, var wake: Boolean = true) : VoiceStack {
        var transcript = "SET A TIMER FOR TEN MINUTES"
        var endpointAfter = 3
        var sttOpened = 0
        var sttReleased = 0
        var wakeOpened = 0
        val thresholds = ArrayList<Float>()
        val spoken = ArrayList<String>()
        var ttsReleased = 0
        override val sttAvailable get() = stt
        override val wakeAvailable get() = wake
        override val ttsAvailable = true
        override fun openStt(): SttSession {
            sttOpened++
            return object : SttSession {
                var n = 0
                override fun accept(pcm: ByteArray): String { n++; return transcript.split(' ').take(n).joinToString(" ") }
                override val endpoint get() = n >= endpointAfter
                override fun finish() = transcript.split(' ').take(n).joinToString(" ")
                override fun close() {}
            }
        }
        override fun openWakeWord(threshold: Float): WakeWordSession {
            thresholds += threshold
            wakeOpened++
            return object : WakeWordSession {
                override fun accept(pcm: ByteArray) = "HEY_NOVA"
                override fun reset() {}
                override fun close() {}
            }
        }
        val speaker = object : Speaker {
            override fun prepare() {}
            override fun speak(text: String, speed: Float): Boolean { spoken += text; return true }
            override val level = 0f
            override fun stop() {}
            override fun release() { ttsReleased++ }
        }
        override fun speaker() = speaker
        override fun releaseStt() { sttReleased++ }
        override fun describe() = "fake"
    }

    class FakeSettings : SettingsStore {
        val map = HashMap<String, Any>()
        override val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value; changes.tryEmit(key) }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value; changes.tryEmit(key) }
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun putFloat(key: String, value: Float) { map[key] = value; changes.tryEmit(key) }
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value; changes.tryEmit(key) }
        override fun remove(key: String) { map.remove(key); changes.tryEmit(key) }
    }

    class FakeNav : Navigator {
        override val current = MutableStateFlow("standby")
        val shown = ArrayList<String>()
        override fun show(screenId: String, args: Bundle?) { shown += screenId; current.value = screenId }
        override fun home() { current.value = "standby" }
    }

    private val queries = ArrayList<String>()

    private fun textMessage(t: String) = ClaudeMessage(JSONArray().put(JSONObject().put("type", "text").put("text", t)), "end_turn", null)

    inner class Rig(scope: TestScope, withKey: Boolean = true, wake: Boolean = false, val api: ClaudeApi = ClaudeApi { body, _ ->
        val m = body.getJSONArray("messages")
        queries += m.getJSONObject(m.length() - 1).getJSONArray("content").getJSONObject(0).getString("text")
        textMessage("Your timer is set.")
    }) {
        val key = MutableStateFlow(withKey)
        val system = DefaultSystemStateStore()
        val island = DefaultIslandManager()
        val settings = FakeSettings().also { it.map[AssistantEngine.KEY_WAKE] = wake }
        val nav = FakeNav()
        val mic = FakeMic()
        var ducked = 0
        val voice = FakeVoice()
        private val noTools = object : ToolHost {
            override fun specs() = emptyList<ToolSpec>()
            override suspend fun run(name: String, input: JSONObject) = ToolOutcome.error("X", "x")
        }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val agent = AssistantAgent(api, noTools, model = { settings.getString(AssistantEngine.KEY_MODEL, ClaudeModels.DEFAULT) }, now = { 0L })
        val engine = AssistantEngine(
            scope = TestScope(dispatcher), system = system, island = island, settings = settings, navigator = { nav },
            mic = mic, ducker = object : Ducker { override fun duck() { ducked++ }; override fun release() {} },
            voice = voice, agent = { if (key.value) agent else null }, keyConfigured = key, hasMicPermission = { true },
            voiceThread = dispatcher, demoData = true, now = { scope.testScheduler.currentTime },
        )
    }

    @Test fun `tap to talk runs a whole turn and frees the models`() = runTest {
        val r = Rig(this)
        r.engine.start(); runCurrent()
        assertEquals(Availability.READY, r.engine.availability.value)
        assertFalse(r.mic.running) // tap-to-talk: no idle mic

        r.engine.tapDroplet(); runCurrent()
        assertEquals(Phase.LISTENING, r.engine.state.value.phase)
        assertTrue(r.mic.running)
        assertEquals(ActivityKind.ASSISTANT, r.island.activities.value.single().kind)
        repeat(3) { r.mic.chunk(); runCurrent() }
        advanceTimeBy(5000) // the answer stays 12 s

        assertEquals(listOf("Set a timer"), queries.map { it.substringAfter("\n\n") })
        val s = r.engine.state.value
        assertEquals(Phase.ANSWER, s.phase)
        assertEquals("Set a timer", s.query)
        assertEquals("Your timer is set.", s.answer)
        assertEquals(listOf("Your timer is set."), r.voice.spoken)
        assertFalse(r.mic.running)
        assertEquals(1, r.voice.sttOpened)
        assertEquals(1, r.voice.sttReleased)
        assertEquals(1, r.voice.ttsReleased)
        assertTrue(r.island.activities.value.isEmpty())
        assertTrue(r.ducked > 0)
    }

    @Test fun `mute while listening stops the mic at once`() = runTest {
        val r = Rig(this)
        r.engine.start(); runCurrent()
        r.engine.tapDroplet(); runCurrent()
        r.mic.chunk(); runCurrent()
        assertTrue(r.mic.running)

        r.system.update { it.copy(micMutedHardware = true) }
        runCurrent()
        assertFalse(r.mic.running)
        assertEquals(Phase.IDLE, r.engine.state.value.phase)
        assertEquals(Availability.MUTED, r.engine.availability.value)
        advanceUntilIdle()
        assertTrue(queries.isEmpty())

        r.engine.tapDroplet(); runCurrent()
        assertFalse(r.mic.running)
    }

    @Test fun `silence gives a no-speech error without calling Claude`() = runTest {
        val r = Rig(this)
        r.voice.transcript = ""
        r.voice.endpointAfter = 2
        r.engine.start(); runCurrent()
        r.engine.tapDroplet(); runCurrent()
        repeat(2) { r.mic.chunk(false); runCurrent() }
        advanceTimeBy(5000)
        assertEquals("no-speech", r.engine.state.value.errorCode)
        assertTrue(queries.isEmpty())
    }

    @Test fun `Claude errors are shown and spoken`() = runTest {
        val r = Rig(this, api = ClaudeApi { _, _ -> throw ClaudeException("offline", "network", retryable = true) })
        r.engine.start(); runCurrent()
        r.engine.ask("What time is it?")
        advanceTimeBy(5000)
        val s = r.engine.state.value
        assertEquals(Phase.ERROR, s.phase)
        assertEquals("offline", s.errorCode)
        assertEquals(1, r.voice.spoken.size)
        assertEquals(0, r.mic.starts)
    }

    @Test fun `demo mode without a key plays the script and never opens the mic`() = runTest {
        val r = Rig(this, withKey = false)
        r.engine.start(); runCurrent()
        assertTrue(r.engine.useDemo())
        assertEquals(Availability.NO_KEY, r.engine.availability.value)
        r.engine.ask("x", "c")
        advanceTimeBy(1000)
        assertEquals(Phase.LISTENING, r.engine.state.value.phase)
        assertEquals("c", r.engine.state.value.demo)
        advanceUntilIdle()
        assertEquals(0, r.mic.starts)
        assertEquals(Phase.IDLE, r.engine.state.value.phase) // auto-dismissed
    }

    @Test fun `demo freeze holds at the given time`() = runTest {
        val r = Rig(this, withKey = false)
        r.engine.start(); runCurrent()
        r.engine.runDemo("a", freezeAtMs = 4500)
        advanceTimeBy(60_000) // (not advanceUntilIdle: the island ticks while it's frozen in WORKING)
        assertEquals(Phase.WORKING, r.engine.state.value.phase)
        r.engine.stopAll(); runCurrent()
    }

    @Test fun `wake word opens the screen and starts listening`() = runTest {
        val r = Rig(this, wake = true)
        r.engine.start(); runCurrent()
        assertTrue(r.mic.running)
        assertTrue(r.engine.wakeActive.value)
        r.mic.chunk(); runCurrent()
        assertEquals(listOf("assistant"), r.nav.shown)
        assertEquals(Phase.LISTENING, r.engine.state.value.phase)
        repeat(3) { r.mic.chunk(); runCurrent() }
        advanceUntilIdle()
        assertEquals(Phase.IDLE, r.engine.state.value.phase) // dismissed after the answer
        assertEquals("standby", r.nav.current.value)          // and back to StandBy
        assertTrue(r.mic.running)                             // listening for the wake word again
        assertEquals(2, r.voice.wakeOpened)
    }

    @Test fun `muting turns the wake word off`() = runTest {
        val r = Rig(this, wake = true)
        r.engine.start(); runCurrent()
        assertTrue(r.mic.running)
        r.system.update { it.copy(micMutedSoftware = true) }; runCurrent()
        assertFalse(r.mic.running)
        assertFalse(r.engine.wakeActive.value)
        r.system.update { it.copy(micMutedSoftware = false) }; runCurrent()
        assertTrue(r.mic.running)
    }

    @Test fun `no models means no listening`() = runTest {
        val r = Rig(this)
        r.voice.stt = false
        r.engine.start(); runCurrent()
        assertEquals(Availability.NO_MODELS, r.engine.availability.value)
        r.engine.tapDroplet(); runCurrent()
        assertFalse(r.mic.running)
        assertEquals("no-models", r.engine.state.value.errorCode)
        assertNull(r.mic.cb)
    }

    @Test fun `a key set at runtime makes it ready without a restart`() = runTest {
        val r = Rig(this, withKey = false)
        r.engine.start(); runCurrent()
        assertEquals(Availability.NO_KEY, r.engine.availability.value)
        r.key.value = true; runCurrent()
        assertEquals(Availability.READY, r.engine.availability.value)
        assertFalse(r.engine.useDemo())
        r.key.value = false; runCurrent()
        assertEquals(Availability.NO_KEY, r.engine.availability.value)
    }

    @Test fun `night mode shows the answer without speaking it`() = runTest {
        val r = Rig(this)
        r.system.update { it.copy(nightActive = true) }
        r.engine.start(); runCurrent()
        r.engine.ask("What time is it?")
        advanceTimeBy(5000)
        assertEquals("Your timer is set.", r.engine.state.value.answer)
        assertTrue(r.voice.spoken.isEmpty())

        r.settings.putBoolean(AssistantEngine.KEY_SPEAK_AT_NIGHT, true)
        r.engine.ask("And now?")
        advanceTimeBy(5000)
        assertEquals(1, r.voice.spoken.size)
    }

    @Test fun `speaking can be turned off`() = runTest {
        val r = Rig(this)
        r.settings.putBoolean(AssistantEngine.KEY_SPEAK, false)
        r.engine.start(); runCurrent()
        r.engine.ask("x")
        advanceTimeBy(5000)
        assertEquals(Phase.ANSWER, r.engine.state.value.phase)
        assertTrue(r.voice.spoken.isEmpty())
    }

    @Test fun `a question from Claude listens for the reply, and silence keeps the answer`() = runTest {
        val r = Rig(this, api = ClaudeApi { _, _ -> textMessage("For how long?") })
        r.engine.start(); runCurrent()
        r.engine.ask("Set a timer")
        advanceTimeBy(3000)
        assertEquals(Phase.LISTENING, r.engine.state.value.phase) // follow-up
        assertTrue(r.mic.running)
        r.voice.transcript = ""
        r.voice.endpointAfter = 2
        repeat(2) { r.mic.chunk(false); runCurrent() }
        advanceTimeBy(1000)
        val s = r.engine.state.value
        assertEquals(Phase.ANSWER, s.phase)
        assertEquals("For how long?", s.answer)
        assertFalse(r.mic.running)
    }

    @Test fun `follow-up listening can be turned off`() = runTest {
        val r = Rig(this, api = ClaudeApi { _, _ -> textMessage("For how long?") })
        r.settings.putBoolean(AssistantEngine.KEY_FOLLOW_UP, false)
        r.engine.start(); runCurrent()
        r.engine.ask("Set a timer")
        advanceTimeBy(3000)
        assertEquals(Phase.ANSWER, r.engine.state.value.phase)
        assertFalse(r.mic.running)
    }

    @Test fun `a model change starts a new conversation`() = runTest {
        val sent = ArrayList<Int>()
        val r = Rig(this, api = ClaudeApi { body, _ -> sent += body.getJSONArray("messages").length(); textMessage("Ok.") })
        r.engine.start(); runCurrent()
        r.engine.ask("one"); advanceTimeBy(3000)
        r.engine.ask("two"); advanceTimeBy(3000)
        r.settings.putString(AssistantEngine.KEY_MODEL, ClaudeModels.SONNET); runCurrent()
        r.engine.ask("three"); advanceTimeBy(3000)
        assertEquals(listOf(1, 3, 1), sent)
    }

    @Test fun `wake sensitivity sets the spotter threshold and reloads it`() = runTest {
        val r = Rig(this, wake = true)
        r.voice.wake = true
        val v = r.voice
        r.engine.start(); runCurrent()
        assertEquals(listOf(0.25f), v.thresholds)
        r.settings.putString(AssistantEngine.KEY_WAKE_SENSITIVITY, "high"); runCurrent()
        assertEquals(listOf(0.25f, 0.15f), v.thresholds)
        assertTrue(r.mic.running)
    }

    private fun TestScope.advanceTimeBy(ms: Long) = testScheduler.advanceTimeBy(ms).also { runCurrent() }
}
