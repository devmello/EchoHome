package app.vesper.feature.assistant.voice

import app.vesper.feature.assistant.AssistantEngine
import app.vesper.feature.assistant.AssistantSettings
import app.vesper.feature.assistant.EngineTest
import app.vesper.core.model.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordsTest {
    @Test fun `threshold is replaced or added per phrase`() {
        val lines = listOf("▁HE Y ▁NO V A :2.0 #0.25 @HEY_NOVA", "", "▁HE Y ▁VE S P ER @HEY_VESPER", "▁OK ▁NO V A")
        assertEquals(
            "▁HE Y ▁NO V A :2.0 #0.15 @HEY_NOVA/▁HE Y ▁VE S P ER #0.15 @HEY_VESPER/▁OK ▁NO V A #0.15",
            Keywords.withThreshold(lines, 0.15f),
        )
    }

    @Test fun `sensitivity maps to thresholds`() {
        val s = EngineTest.FakeSettings()
        assertEquals(0.25f, AssistantSettings.threshold(s))
        s.putString(AssistantEngine.KEY_WAKE_SENSITIVITY, "high")
        assertEquals(0.15f, AssistantSettings.threshold(s))
        s.putString(AssistantEngine.KEY_WAKE_SENSITIVITY, "bogus")
        assertEquals(0.25f, AssistantSettings.threshold(s))
    }

    @Test fun `settings section declares the user-facing keys but not the API key`() {
        val sec = AssistantSettings.section(emptyList(), "amy")
        val keys = sec.settings.map { it.key }
        for (k in listOf(AssistantEngine.KEY_WAKE, AssistantEngine.KEY_WAKE_SENSITIVITY, AssistantEngine.KEY_MODEL,
            AssistantEngine.KEY_VOICE, AssistantEngine.KEY_FOLLOW_UP, AssistantEngine.KEY_SPEAK_AT_NIGHT)) assertTrue(k, k in keys)
        assertFalse(sec.settings.any { it.type == SettingType.SECRET })
        assertEquals(keys.size, keys.toSet().size)
        val model = sec.settings.first { it.key == AssistantEngine.KEY_MODEL }
        assertEquals("claude-haiku-5-5", model.default)
        assertTrue(model.options.any { it.first == model.default })
        sec.settings.filter { it.type == SettingType.CHOICE }.forEach { c -> assertTrue(c.key, c.options.any { it.first == c.default }) }
    }
}
