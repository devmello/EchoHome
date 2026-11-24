package app.vesper.feature.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class VersionPolicyTest {
    private fun rel(code: Long) = Release(code, "0.2.$code", "v0.2.$code", "", "a.apk", "https://github.com/x", "00".repeat(32), 1)

    @Test fun onlyHigherVersionCodesAreUpdates() {
        assertTrue(VersionPolicy.isNewer(rel(270), 268))
        assertFalse(VersionPolicy.isNewer(rel(268), 268)) // same version: ignored
        assertFalse(VersionPolicy.isNewer(rel(260), 268)) // older: ignored
        assertFalse(VersionPolicy.isNewer(null, 268))
    }

    @Test fun skippedVersionsAreNeverAutoInstalled() {
        assertTrue(VersionPolicy.canAutoInstall(rel(270), 268, emptySet()))
        assertFalse(VersionPolicy.canAutoInstall(rel(270), 268, setOf(270L)))
        assertTrue(VersionPolicy.canAutoInstall(rel(271), 268, setOf(270L))) // a newer one clears it
        assertTrue(VersionPolicy.canManualInstall(rel(270), 268))           // the user may still ask for it
        assertFalse(VersionPolicy.canManualInstall(rel(268), 268))
    }
}

class QuietWindowTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 24, h, m, 0, 0, zone)
    private fun quiet(now: ZonedDateTime = at(3, 30)) = QuietInputs(now, nextAlarmAt = null, alarmActive = false, islandBusy = false,
        mediaPlaying = false, timerRunning = false, someonePresent = false)
    private fun waits(i: QuietInputs) = QuietWindow.decide(i) is QuietDecision.Wait

    @Test fun installsInAQuietNight() = assertEquals(QuietDecision.Install, QuietWindow.decide(quiet()))

    @Test fun onlyInsideTheWindow() {
        assertFalse(waits(quiet(at(3, 0))))
        assertFalse(waits(quiet(at(4, 59))))
        assertTrue(waits(quiet(at(5, 0))))
        assertTrue(waits(quiet(at(2, 59))))
        assertTrue(waits(quiet(at(14, 0))))
    }

    @Test fun alarmsBlock() {
        val now = at(3, 30)
        val ms = now.toInstant().toEpochMilli()
        assertTrue(waits(quiet(now).copy(alarmActive = true)))
        assertTrue(waits(quiet(now).copy(nextAlarmAt = ms + 60 * 60_000)))  // 04:30 alarm: too close
        assertTrue(waits(quiet(now).copy(nextAlarmAt = ms + 89 * 60_000)))
        assertFalse(waits(quiet(now).copy(nextAlarmAt = ms + 91 * 60_000))) // 05:01: fine
        assertFalse(waits(quiet(now).copy(nextAlarmAt = ms - 60_000)))      // stale value in the past
    }

    @Test fun roomAndMediaBlock() {
        assertTrue(waits(quiet().copy(mediaPlaying = true)))
        assertTrue(waits(quiet().copy(someonePresent = true)))
        assertTrue(waits(quiet().copy(islandBusy = true)))
        assertTrue(waits(quiet().copy(timerRunning = true)))
    }

    @Test fun manualInstallOnlyStopsForAlarmsAndCalls() {
        assertEquals(QuietDecision.Install, QuietWindow.decideManual(alarmActive = false, callOrDoorbell = false))
        assertTrue(QuietWindow.decideManual(alarmActive = true, callOrDoorbell = false) is QuietDecision.Wait)
        assertTrue(QuietWindow.decideManual(alarmActive = false, callOrDoorbell = true) is QuietDecision.Wait)
    }

    @Test fun windowCanWrapMidnight() {
        assertTrue(QuietWindow.inWindow(LocalTime.of(23, 30), LocalTime.of(23, 0), LocalTime.of(1, 0)))
        assertTrue(QuietWindow.inWindow(LocalTime.of(0, 30), LocalTime.of(23, 0), LocalTime.of(1, 0)))
        assertFalse(QuietWindow.inWindow(LocalTime.of(1, 30), LocalTime.of(23, 0), LocalTime.of(1, 0)))
    }
}

class ScheduleTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)
    private fun ms(z: ZonedDateTime) = z.toInstant().toEpochMilli()

    @Test fun dailyCheckAt0230PlusJitter() {
        val now = at(24, 10)
        val last = ms(at(24, 2, 40))
        assertEquals(ms(at(25, 2, 47)), Schedule.nextWake(now, last, 17, checksEnabled = true, waitingToInstall = false))
    }

    @Test fun overdueCheckRunsSoonAfterStart() {
        val now = at(24, 10)
        assertEquals(ms(now) + Schedule.STARTUP_DELAY_MS, Schedule.nextWake(now, 0, 5, true, false))
        assertEquals(ms(now) + Schedule.STARTUP_DELAY_MS, Schedule.nextWake(now, ms(at(23, 2)), 5, true, false))
    }

    @Test fun waitingUpdateWakesAtTheWindowAndRetries() {
        val last = ms(at(24, 2, 35))
        // Afternoon: next 03:00 (before the 02:30 check? no: the check comes first).
        assertEquals(ms(at(25, 2, 30)), Schedule.nextWake(at(24, 15), last, 0, true, true))
        assertEquals(ms(at(25, 3, 0)), Schedule.nextWake(at(24, 15), last, 0, false, true))
        // Inside the window: every 15 minutes.
        assertEquals(ms(at(24, 3, 15)), Schedule.nextWake(at(24, 3, 0), last, 0, true, true))
        // Last retry would be past 05:00: tomorrow's 02:30 check comes first.
        assertEquals(ms(at(25, 2, 30)), Schedule.nextWake(at(24, 4, 50), last, 0, true, true))
        assertEquals(ms(at(25, 3, 0)), Schedule.nextWake(at(24, 4, 50), last, 0, false, true))
    }

    @Test fun nothingToDo() {
        assertEquals(null, Schedule.nextWake(at(24, 15), 0, 0, checksEnabled = false, waitingToInstall = false))
    }

    @Test fun nextAtSurvivesDst() {
        // US DST ends Nov 1 2026 at 02:00: 02:30 exists once.
        val n = Schedule.nextAt(ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, zone), LocalTime.of(2, 30))
        assertEquals(ZonedDateTime.of(2026, 11, 1, 2, 30, 0, 0, zone).toInstant().toEpochMilli(), n)
    }
}

class CrashLoopTest {
    private val t0 = 1_800_000_000_000L
    private val armed = CrashLoop.Guard(version = 270, armedAt = t0)

    @Test fun notArmedDoesNothing() {
        val (g, d) = CrashLoop.onStart(CrashLoop.Guard(), 270, t0, true)
        assertEquals(CrashLoop.Decision.NONE, d)
        assertEquals(CrashLoop.Guard(), g)
    }

    @Test fun threeCrashedStartsRollBack() {
        var g = armed
        var d: CrashLoop.Decision
        // Start 1 (first after the install), then three crashes before 2 minutes.
        CrashLoop.onStart(g, 270, t0 + 1_000, true).let { g = it.first; d = it.second }
        assertEquals(CrashLoop.Decision.WATCH, d); assertEquals(0, g.failedStarts)
        CrashLoop.onStart(g, 270, t0 + 5_000, true).let { g = it.first; d = it.second }
        assertEquals(CrashLoop.Decision.WATCH, d); assertEquals(1, g.failedStarts)
        CrashLoop.onStart(g, 270, t0 + 9_000, true).let { g = it.first; d = it.second }
        assertEquals(CrashLoop.Decision.WATCH, d); assertEquals(2, g.failedStarts)
        CrashLoop.onStart(g, 270, t0 + 13_000, true).let { g = it.first; d = it.second }
        assertEquals(CrashLoop.Decision.ROLLBACK, d); assertEquals(3, g.failedStarts)
    }

    @Test fun aStableStartClearsTheCount() {
        var g = CrashLoop.onStart(armed, 270, t0, true).first
        g = CrashLoop.onStart(g, 270, t0 + 5_000, true).first
        g = CrashLoop.onStart(g, 270, t0 + 9_000, true).first
        assertEquals(2, g.failedStarts)
        g = CrashLoop.markStable(g) // stayed up 2 minutes
        assertEquals(0, g.failedStarts); assertEquals(0L, g.lastStartAt)
        val (g2, d) = CrashLoop.onStart(g, 270, t0 + 600_000, true) // a later restart isn't a failure
        assertEquals(CrashLoop.Decision.WATCH, d); assertEquals(0, g2.failedStarts)
    }

    @Test fun nonCrashExitsDontCount() {
        var g = CrashLoop.onStart(armed, 270, t0, true).first
        repeat(5) { i -> g = CrashLoop.onStart(g, 270, t0 + 5_000L * (i + 1), lastExitCouldBeCrash = false).first }
        assertEquals(0, g.failedStarts)
    }

    @Test fun anotherVersionOrExpiryDisarms() {
        val g = CrashLoop.onStart(armed, 270, t0, true).first
        assertEquals(CrashLoop.Decision.NONE, CrashLoop.onStart(g, 271, t0 + 5_000, true).second) // adb-installed another build
        val (g2, d) = CrashLoop.onStart(g, 270, t0 + CrashLoop.ARMED_MS + 1, true)
        assertEquals(CrashLoop.Decision.NONE, d); assertFalse(g2.armed)
    }

    @Test fun exitReasons() {
        assertTrue(CrashLoop.couldBeCrash(null))
        assertTrue(CrashLoop.couldBeCrash(CrashLoop.REASON_CRASH))
        assertTrue(CrashLoop.couldBeCrash(CrashLoop.REASON_CRASH_NATIVE))
        assertTrue(CrashLoop.couldBeCrash(CrashLoop.REASON_ANR))
        assertTrue(CrashLoop.couldBeCrash(CrashLoop.REASON_SIGNALED))  // our watchdog kills its own process
        assertTrue(CrashLoop.couldBeCrash(CrashLoop.REASON_EXIT_SELF))
        assertFalse(CrashLoop.couldBeCrash(10)) // USER_REQUESTED: force-stop, install
        assertFalse(CrashLoop.couldBeCrash(8))  // PERMISSION_CHANGE
        assertFalse(CrashLoop.couldBeCrash(13)) // OTHER
    }
}

class UpdateStateTest {
    @Test fun jsonRoundTrip() {
        val s = UpdateState(
            lastCheckAt = 5, lastCheckResult = "Up to date", etag = "W/\"x\"",
            latest = Release(270, "0.2.270", "v0.2.270", "notes", "a.apk", "https://github.com/a", "ab".repeat(32), 10, "2026"),
            ready = UpdateState.Ready(270, "0.2.270", "ab".repeat(32), "/data/x/ready.apk"),
            skipped = setOf(269, 271), refused = setOf("cd".repeat(32)),
            pending = UpdateState.Pending(268, 270, "0.2.270", 7, manual = true, rollback = false),
            previous = UpdateState.Previous(268, "0.2.268", "ef".repeat(32), "/data/x/previous.apk"),
            guard = CrashLoop.Guard(270, 9, 2, 11), rollbackFailed = 0,
            history = listOf("a", "b"),
        )
        assertEquals(s, UpdateState.fromJson(org.json.JSONObject(s.toJson().toString())))
        assertEquals(UpdateState(), UpdateState.fromJson(org.json.JSONObject("{}")))
    }

    @Test fun historyIsCapped() {
        var s = UpdateState()
        repeat(20) { s = s.withHistory("e$it") }
        assertEquals(UpdateState.HISTORY, s.history.size)
        assertEquals("e19", s.history.last())
    }
}
