package app.vesper.feature.alarm.engine

import app.vesper.core.model.VesperTimer.State
import app.vesper.core.model.TimerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerEngineTest {
    private var elapsed = 1_000_000L
    private var wall = 1_800_000_000_000L
    private var ids = 0
    private fun engine() = TimerEngine({ elapsed }, { wall }, { "t" + ids++ })
    private fun advance(ms: Long) { elapsed += ms; wall += ms }

    @Test fun startRunsAndFinishes() {
        val e = engine()
        val t = e.start(60_000, "Tea")!!
        assertEquals(60_000, t.remainingMs(elapsed))
        advance(59_000)
        assertFalse(e.tick())
        assertEquals(1_000, e.timers[0].remainingMs(elapsed))
        assertEquals(elapsed + 1_000, e.nextWakeElapsed())
        advance(1_000)
        assertTrue(e.tick())
        assertEquals(State.DONE, e.timers[0].state)
        assertEquals(listOf("t0"), e.alerting().map { it.id })
    }

    @Test fun atMostThree() {
        val e = engine()
        repeat(3) { assertTrue(e.start(1000L * (it + 1), "") != null) }
        assertNull(e.start(5000, "four"))
        assertEquals(TimerStore.MAX_TIMERS, e.timers.size)
        assertEquals("Timer", e.timers[0].label)
    }

    @Test fun pauseResumeKeepsRemaining() {
        val e = engine()
        e.start(300_000, "Pasta")
        advance(100_000)
        e.pause("t0")
        advance(500_000)
        e.tick()
        assertEquals(State.PAUSED, e.timers[0].state)
        assertEquals(200_000, e.timers[0].remainingMs(elapsed))
        assertNull(e.nextWakeElapsed())
        e.resume("t0")
        assertEquals(200_000, e.timers[0].remainingMs(elapsed))
        advance(200_000)
        e.tick()
        assertEquals(State.DONE, e.timers[0].state)
    }

    @Test fun addTimeAndRestart() {
        val e = engine()
        e.start(60_000, "Eggs")
        e.addTime("t0", 60_000)
        assertEquals(120_000, e.timers[0].remainingMs(elapsed))
        assertEquals(120_000, e.timers[0].durationMs)
        advance(120_000); e.tick()
        assertEquals(State.DONE, e.timers[0].state)
        e.restart("t0")
        assertEquals(State.RUNNING, e.timers[0].state)
        assertEquals(120_000, e.timers[0].remainingMs(elapsed))
    }

    @Test fun alertSilencesAfterThreeMinutesButCardStays() {
        val e = engine()
        e.start(1_000, "Tea")
        advance(1_000); e.tick()
        assertEquals(1, e.alerting().size)
        assertEquals(elapsed + TimerEngine.ALERT_MS, e.nextWakeElapsed())
        advance(TimerEngine.ALERT_MS); assertTrue(e.tick())
        assertTrue(e.alerting().isEmpty())
        assertEquals(State.DONE, e.timers[0].state)
        e.cancel("t0")
        assertTrue(e.timers.isEmpty())
    }

    @Test fun persistsAcrossProcessDeath() {
        val e = engine()
        e.start(600_000, "Pasta")
        e.start(240_000, "Tea"); e.pause("t1")
        advance(60_000)
        val json = Codec.timersToJson(e.save(bootCount = 4))
        // The process dies; 30 s later a new one loads the file (same boot).
        advance(30_000)
        val e2 = engine()
        e2.load(Codec.timersFromJson(json), bootCount = 4)
        assertEquals(510_000, e2.timers[0].remainingMs(elapsed))
        assertEquals(State.PAUSED, e2.timers[1].state)
        assertEquals(240_000, e2.timers[1].remainingMs(elapsed))
    }

    @Test fun persistsAcrossReboot() {
        val e = engine()
        e.start(600_000, "Oven")
        advance(100_000)
        val json = Codec.timersToJson(e.save(bootCount = 4))
        // Reboot: elapsedRealtime restarts near 0; 60 s of wall time pass.
        elapsed = 20_000; wall += 60_000
        val e2 = engine()
        e2.load(Codec.timersFromJson(json), bootCount = 5)
        assertEquals(440_000, e2.timers[0].remainingMs(elapsed))
    }

    @Test fun finishedWhileDeadIsDoneOnLoad() {
        val e = engine()
        e.start(10_000, "Tea")
        val json = Codec.timersToJson(e.save(1))
        advance(20_000)
        val e2 = engine()
        e2.load(Codec.timersFromJson(json), 1)
        assertEquals(State.DONE, e2.timers[0].state)
        assertEquals(1, e2.alerting().size)
        // Finished long ago: shown as done, but silent.
        advance(TimerEngine.ALERT_MS)
        val e3 = engine()
        e3.load(Codec.timersFromJson(json), 1)
        assertTrue(e3.alerting().isEmpty())
    }
}
