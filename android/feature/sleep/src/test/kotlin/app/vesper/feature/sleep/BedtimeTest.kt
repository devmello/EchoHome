package app.vesper.feature.sleep

import app.vesper.core.model.Alarm
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.AlarmStore
import app.vesper.core.model.BedtimeRequest
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.NightMode
import app.vesper.core.model.Secrets
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepState
import app.vesper.core.model.StateOnlySystemControls
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BedtimeTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private var wall = at("2026-11-23T22:40:00")

    private val settings = MemSettings()
    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), settings)
    private val played = ArrayList<Pair<SleepSound, Int>>()
    private var playOk = true
    private val wakes = ArrayList<Long>()
    private val routine = BedtimeRoutine({ services }, { s, t -> played += s to t; playOk }, { wakes += it }, { wall }, { zone })

    private class OneAlarm(at: Long) : AlarmStore {
        val a = Alarm("a1", 7, 0, label = "Wake up")
        override val alarms: StateFlow<List<Alarm>> = MutableStateFlow(listOf(a))
        override val next: StateFlow<NextAlarm?> = MutableStateFlow(NextAlarm(a, at))
        override val phase: StateFlow<AlarmPhase> = MutableStateFlow(AlarmPhase.Idle)
        override fun upsert(alarm: Alarm) {}
        override fun delete(id: String) {}
        override fun setEnabled(id: String, enabled: Boolean) {}
        override fun snooze() {}
        override fun stop() {}
        override fun dismissGoodMorning() {}
    }

    private fun withAlarm(at: Long) { services.alarms = OneAlarm(at) }

    @Test fun `good night with an alarm - DND until it, Night on, rain for 30 min`() {
        val alarm = at("2026-11-24T07:00:00")
        withAlarm(alarm)
        val r = routine.run()
        assertEquals(alarm, r.alarmAtMillis)
        assertEquals("Wake up", r.alarmLabel)
        assertTrue(r.dndOn)
        assertEquals(alarm, r.dndUntilMillis)
        assertTrue(services.system.state.value.doNotDisturb)
        assertTrue(r.nightOn)
        assertEquals(NightMode.ON, services.system.state.value.nightMode)
        assertEquals(listOf(SleepSound.RAIN to 30), played)
        assertEquals(SleepSound.RAIN, r.sound)
        assertEquals(30, r.timerMinutes)
        assertNull(r.dimmedTo)
        assertTrue(r.failed.isEmpty())
        assertEquals(listOf(alarm), wakes)
        assertEquals(alarm.toString(), settings.getString(SleepSettings.DND_UNTIL, ""))
    }

    @Test fun `no alarm - DND until the fallback wake time`() {
        services.alarms = object : AlarmStore by OneAlarm(0) { override val next: StateFlow<NextAlarm?> = MutableStateFlow(null) }
        settings.putString(SleepSettings.WAKE_FALLBACK, "06:30")
        val r = routine.run()
        assertNull(r.alarmAtMillis)
        assertEquals(at("2026-11-24T06:30:00"), r.dndUntilMillis)
        assertEquals(listOf(at("2026-11-24T06:30:00")), wakes)
    }

    @Test fun `fallback wake later the same day`() {
        wall = at("2026-11-24T01:10:00")
        assertEquals(at("2026-11-24T07:00:00"), routine.fallbackWake(wall))
    }

    @Test fun `settings turn steps off, the request overrides sound, timer and dim`() {
        withAlarm(at("2026-11-24T07:00:00"))
        settings.putBoolean(SleepSettings.BEDTIME_DND, false)
        settings.putBoolean(SleepSettings.BEDTIME_NIGHT, false)
        settings.putBoolean(SleepSettings.BEDTIME_SOUND, false)
        val r = routine.run()
        assertFalse(r.dndOn); assertFalse(r.nightOn); assertNull(r.sound)
        assertFalse(services.system.state.value.doNotDisturb)
        assertTrue(played.isEmpty())
        assertTrue("nothing to hand back", wakes.isEmpty())

        services.system.update { it.copy(brightness = 0.6f) }
        val r2 = routine.run(BedtimeRequest(sound = SleepSound.OCEAN, timerMinutes = 90, dim = true))
        assertEquals(listOf(SleepSound.OCEAN to 90), played)
        assertEquals(0.1f, r2.dimmedTo!!, 1e-4f)
        assertEquals(0.1f, services.system.state.value.brightness, 1e-4f)
    }

    @Test fun `what fails is reported`() {
        withAlarm(at("2026-11-24T07:00:00"))
        services.systemControls = object : StateOnlySystemControls(services.system) {
            override fun setDoNotDisturb(on: Boolean) = false
        }
        playOk = false
        val r = routine.run()
        assertEquals(listOf("Do Not Disturb", "Sleep sound"), r.failed)
        assertFalse(r.dndOn)
        assertTrue(r.nightOn)
        assertNull(r.sound)
    }

    @Test fun `already on stays the user setting, not handed back in the morning`() {
        withAlarm(at("2026-11-24T07:00:00"))
        services.system.update { it.copy(doNotDisturb = true) }
        routine.run()
        // Night mode was turned on by us (AUTO before), DND was already on
        wall = at("2026-11-24T07:00:00")
        assertNull(WakeRestore.run(services, wall))
        assertTrue("the user's DND stays", services.system.state.value.doNotDisturb)
        assertEquals(NightMode.AUTO, services.system.state.value.nightMode)
    }

    @Test fun `wake restore hands DND and Night mode back, only at the wake time`() {
        val alarm = at("2026-11-24T07:00:00")
        withAlarm(alarm)
        routine.run()
        assertEquals(alarm, WakeRestore.run(services, at("2026-11-24T03:00:00")))
        assertTrue(services.system.state.value.doNotDisturb)
        assertNull(WakeRestore.run(services, alarm))
        assertFalse(services.system.state.value.doNotDisturb)
        assertEquals(NightMode.AUTO, services.system.state.value.nightMode)
        assertEquals("", settings.getString(SleepSettings.DND_UNTIL, ""))
        // a second run is a no-op
        assertNull(WakeRestore.run(services, alarm + 1000))
    }

    @Test fun `changed by hand overnight is left alone`() {
        val alarm = at("2026-11-24T07:00:00")
        withAlarm(alarm)
        routine.run()
        services.systemControls.setNightMode(NightMode.OFF)
        WakeRestore.run(services, alarm)
        assertEquals(NightMode.OFF, services.system.state.value.nightMode)
    }

    @Test fun `controller bedtime starts the sound through the real player path`() {
        withAlarm(at("2026-11-24T07:00:00"))
        val out = FakeOutput()
        val c = SleepController({ services }, out, FakeFocus(), FakeScheduler(), { 5_000L }, wall = { wall })
        settings.putString(SleepSettings.SOUND, "crickets")
        settings.putString(SleepSettings.TIMER, "60")
        val r = c.bedtime()
        assertEquals(SleepSound.CRICKETS, r.sound)
        assertEquals(SleepState(SleepSound.CRICKETS, playing = true, timerMinutes = 60, endsAtElapsed = 5_000L + 3_600_000L), c.state.value)
        assertEquals(listOf("play crickets"), out.log)
    }
}
