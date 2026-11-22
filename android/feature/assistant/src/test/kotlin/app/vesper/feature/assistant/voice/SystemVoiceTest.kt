package app.vesper.feature.assistant.voice

import app.vesper.feature.assistant.EngineTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemVoiceTest {
    private val system = object : Speaker {
        var released = 0
        override fun prepare() {}
        override fun speak(text: String, speed: Float) = true
        override val level = 0f
        override fun stop() {}
        override fun release() { released++ }
    }

    @Test fun `an installed engine is the first and default voice, and can be switched away from`() {
        val base = EngineTest.FakeVoice()
        val v = VoiceWithSystemTts(base, system)
        assertEquals(VoiceWithSystemTts.SYSTEM, v.defaultVoice)
        assertEquals(VoiceWithSystemTts.SYSTEM, v.voices().first().first)
        v.selectVoice(VoiceWithSystemTts.SYSTEM)
        assertSame(system, v.speaker())
        v.selectVoice("amy")
        assertSame(base.speaker(), v.speaker())
        assertEquals(1, system.released)
    }

    @Test fun `without an engine nothing changes`() {
        val base = EngineTest.FakeVoice()
        val v = VoiceWithSystemTts(base, null)
        v.selectVoice(VoiceWithSystemTts.SYSTEM)
        assertSame(base.speaker(), v.speaker())
        assertTrue(v.voices().none { it.first == VoiceWithSystemTts.SYSTEM })
        assertEquals(base.defaultVoice, v.defaultVoice)
    }
}
