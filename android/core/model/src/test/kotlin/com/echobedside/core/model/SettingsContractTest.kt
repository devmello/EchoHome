package com.echobedside.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractTest {
    @Test fun schemaReplacesSectionsByIdAndSortsByOrder() {
        val s = DefaultSettingsSchema()
        s.register(SettingsSection("b", "B", 20, listOf(SettingSpec("b.x", "X", SettingType.BOOLEAN, "true"))))
        s.register(SettingsSection("a", "A", 10, emptyList()))
        s.register(SettingsSection("b", "B2", 20, listOf(SettingSpec("b.y", "Y", SettingType.INT, "3"))))
        assertEquals(listOf("A", "B2"), s.sections.value.map { it.title })
        assertEquals(SettingType.INT, s.spec("b.y")?.type)
        assertEquals(null, s.spec("b.x"))
    }

    @Test fun runtimeSecretsOverrideBuiltInOnes() {
        val store = InMemorySecretStore()
        val built = Secrets(anthropicApiKey = "built", calendarIcsUrls = listOf("https://a"))
        assertEquals("built", built.overriddenBy(store).anthropicApiKey)
        store.set(Secrets.ANTHROPIC_API_KEY, "runtime")
        store.set(Secrets.CALENDAR_ICS_URLS, "https://b, https://c")
        val s = built.overriddenBy(store)
        assertEquals("runtime", s.anthropicApiKey)
        assertEquals(listOf("https://b", "https://c"), s.calendarIcsUrls)
        assertTrue(store.isSet(Secrets.ANTHROPIC_API_KEY))
        store.set(Secrets.ANTHROPIC_API_KEY, " ")
        assertFalse(store.isSet(Secrets.ANTHROPIC_API_KEY))
        assertEquals("built", built.overriddenBy(store).anthropicApiKey)
        assertFalse(s.toString().contains("runtime"))
    }
}
