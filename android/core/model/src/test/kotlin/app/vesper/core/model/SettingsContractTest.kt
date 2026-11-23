package app.vesper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
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

class Round2ContractTest {
    @Test fun alarmJsonRoundTripsAndRejectsBadTimes() {
        val a = Alarm("x", 6, 45, setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY), "Work", true, false, 20, 5, "maya")
        assertEquals(a, AlarmJson.fromJson(AlarmJson.toJson(a)))
        val lb = a.copy(leaveBy = true, readyMinutes = 60, skipHolidays = true)
        assertEquals(lb, AlarmJson.fromJson(AlarmJson.toJson(lb)))
        assertEquals(null, AlarmJson.fromJson(org.json.JSONObject().put("id", "y").put("hour", 25)))
        assertEquals(null, AlarmJson.fromJson(org.json.JSONObject().put("hour", 7)))
    }

    @Test fun userDismissalsOnlyForExplicitDismiss() = kotlinx.coroutines.test.runTest {
        val feed = DefaultNotificationFeed()
        val seen = mutableListOf<String>()
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { feed.userDismissals.collect { seen += it } }
        feed.post(Banner("a", "App", 0, title = "t", body = "b")); feed.post(Banner("b", "App", 0, title = "t", body = "b"))
        feed.dismiss("a"); feed.dismiss("b", byUser = true)
        assertEquals(listOf("b"), seen); assertTrue(feed.banners.value.isEmpty())
        job.cancel()
    }

    @Test fun micButtonDoesNotBlockCamera() {
        assertFalse(SystemState(micMutedHardware = true).cameraBlocked)
        assertTrue(SystemState(shutterClosed = true).cameraBlocked)
    }
}
