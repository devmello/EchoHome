package app.vesper.feature.lists

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.InMemoryReminderStore
import app.vesper.core.model.Reminder
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.feature.lists.engine.Evaluation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The island and the banner don't both show Done / Snooze for the same due reminder. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReminderAlertsTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private class Settings : SettingsStore {
        val map = HashMap<String, Any>()
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override val changes: SharedFlow<String> = MutableSharedFlow()
    }

    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()),
        Settings().apply { putBoolean(ListsSettings.CHIME, false) })
    private val alerts = ReminderAlerts(services, InMemoryReminderStore())
    private val now = 1_800_000_000_000L

    private fun due(id: String) = Reminder(id, "Take the bins out", now, state = Reminder.State.DUE)

    @Test fun newlyDueShowsTheBannerAndACompactIsland() {
        val r = due("r1")
        alerts.apply(Evaluation(listOf(r), newlyDue = listOf(r), nextWakeAt = null))
        assertEquals(listOf("reminder:r1"), services.notifications.banners.value.map { it.id })
        val a = services.island.activities.value.single()
        assertEquals(ActivityKind.REMINDER, a.kind)
        assertFalse("the banner is the alert; the island mustn't expand over it", a.alert)
    }

    @Test fun dueWithoutABannerAlertsInTheIsland() {
        // Due before this process started: no banner, so the island is the only thing showing it.
        alerts.apply(Evaluation(listOf(due("r2")), newlyDue = emptyList(), nextWakeAt = null))
        assertTrue(services.notifications.banners.value.isEmpty())
        assertTrue(services.island.activities.value.single().alert)
    }

    @Test fun missedNeverAlerts() {
        val m = Reminder("r3", "Call Mum", now - 600_000L, state = Reminder.State.MISSED)
        alerts.apply(Evaluation(listOf(m), newlyDue = emptyList(), nextWakeAt = null))
        val a = services.island.activities.value.single()
        assertEquals(ActivityKind.REMINDER_MISSED, a.kind)
        assertFalse(a.alert)
    }

    @Test fun answeredReminderEndsBannerAndIsland() {
        val r = due("r4")
        alerts.apply(Evaluation(listOf(r), newlyDue = listOf(r), nextWakeAt = null))
        alerts.apply(Evaluation(listOf(r.copy(state = Reminder.State.DONE, doneAtMillis = now)), newlyDue = emptyList(), nextWakeAt = null))
        assertTrue(services.notifications.banners.value.isEmpty())
        assertTrue(services.island.activities.value.isEmpty())
    }
}
