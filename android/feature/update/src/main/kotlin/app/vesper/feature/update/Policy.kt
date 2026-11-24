package app.vesper.feature.update

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Which releases may be offered or installed. Pure, unit-tested. */
object VersionPolicy {
    /** A release is an update when its versionCode is higher than the installed one. */
    fun isNewer(release: Release?, installedCode: Long): Boolean = release != null && release.versionCode > installedCode

    /** Automatic installs never pick a version that was rolled back ([skipped]). */
    fun canAutoInstall(release: Release?, installedCode: Long, skipped: Set<Long>): Boolean =
        isNewer(release, installedCode) && release!!.versionCode !in skipped

    /** "Install now" may install a skipped version: the user asked for it. */
    fun canManualInstall(release: Release?, installedCode: Long): Boolean = isNewer(release, installedCode)
}

/** What's going on in the room and on the Echo when an automatic install is considered. */
data class QuietInputs(
    val now: ZonedDateTime,
    /** Wall clock of the next alarm, or null. */
    val nextAlarmAt: Long?,
    /** Ringing, snoozed, sunrise or Good morning. */
    val alarmActive: Boolean,
    /** A call, the doorbell, a ringing timer or reminder, or the assistant on the island. */
    val islandBusy: Boolean,
    val mediaPlaying: Boolean,
    val timerRunning: Boolean,
    /** Someone is in the room (camera presence). Unknown counts as empty. */
    val someonePresent: Boolean,
)

sealed interface QuietDecision {
    data object Install : QuietDecision
    data class Wait(val reason: String) : QuietDecision
}

/**
 * When EchoHome may install an update by itself: inside the night window, nothing playing, nobody
 * in the room, no call, no alarm now or within [ALARM_GAP_MS]. Pure, unit-tested.
 */
object QuietWindow {
    val START: LocalTime = LocalTime.of(3, 0)
    val END: LocalTime = LocalTime.of(5, 0)
    const val ALARM_GAP_MS = 90 * 60_000L
    /** How often the window is re-checked while an update waits. */
    const val RETRY_MS = 15 * 60_000L

    fun inWindow(t: LocalTime, start: LocalTime = START, end: LocalTime = END): Boolean =
        if (start <= end) t >= start && t < end else t >= start || t < end

    fun decide(i: QuietInputs): QuietDecision {
        val ms = i.now.toInstant().toEpochMilli()
        return when {
            !inWindow(i.now.toLocalTime()) -> QuietDecision.Wait("Waiting for the night window (3–5 AM)")
            i.alarmActive -> QuietDecision.Wait("An alarm is going")
            i.nextAlarmAt != null && i.nextAlarmAt - ms in 0 until ALARM_GAP_MS -> QuietDecision.Wait("An alarm is due soon")
            i.islandBusy -> QuietDecision.Wait("The Echo is busy (call, timer or assistant)")
            i.mediaPlaying -> QuietDecision.Wait("Something is playing")
            i.timerRunning -> QuietDecision.Wait("A timer is running")
            i.someonePresent -> QuietDecision.Wait("Someone is in the room")
            else -> QuietDecision.Install
        }
    }

    /** "Install now" from the settings page: only a ringing alarm or a call stops it. */
    fun decideManual(alarmActive: Boolean, callOrDoorbell: Boolean): QuietDecision = when {
        alarmActive -> QuietDecision.Wait("An alarm is going; try again after it")
        callOrDoorbell -> QuietDecision.Wait("A call is going; try again after it")
        else -> QuietDecision.Install
    }
}

/** When the updater's alarm next wakes it. Pure, unit-tested. */
object Schedule {
    /** The daily check, before the install window so a new release can install the same night. */
    val CHECK_AT: LocalTime = LocalTime.of(2, 30)
    const val DAY_MS = 24 * 3_600_000L
    /** A check more than this old is overdue (the Echo was off at 02:30): check soon after start. */
    const val OVERDUE_MS = 26 * 3_600_000L
    const val STARTUP_DELAY_MS = 3 * 60_000L

    /**
     * @param lastCheckAt wall clock of the last check, 0 = never.
     * @param jitterMin per-install minutes added to [CHECK_AT] (0..29) so the check isn't on the dot.
     * @param waitingToInstall a verified update waits for the quiet window.
     */
    fun nextWake(now: ZonedDateTime, lastCheckAt: Long, jitterMin: Int, checksEnabled: Boolean, waitingToInstall: Boolean): Long? {
        val ms = now.toInstant().toEpochMilli()
        var next: Long? = null
        fun consider(t: Long) { next = next?.let { minOf(it, t) } ?: t }
        if (checksEnabled) {
            if (ms - lastCheckAt > OVERDUE_MS) consider(ms + STARTUP_DELAY_MS)
            else consider(nextAt(now, CHECK_AT.plusMinutes(jitterMin.toLong().coerceIn(0, 29))))
        }
        if (waitingToInstall) {
            consider(if (QuietWindow.inWindow(now.toLocalTime())) {
                val retry = now.plusNanos(QuietWindow.RETRY_MS * 1_000_000)
                if (QuietWindow.inWindow(retry.toLocalTime())) retry.toInstant().toEpochMilli() else nextAt(now, QuietWindow.START)
            } else nextAt(now, QuietWindow.START))
        }
        return next
    }

    /** The next wall-clock time [t] strictly after [now] (today or tomorrow, DST-safe). */
    fun nextAt(now: ZonedDateTime, t: LocalTime): Long {
        var d = now.toLocalDate().atTime(t).atZone(now.zone)
        if (!d.isAfter(now)) d = now.toLocalDate().plusDays(1).atTime(t).atZone(now.zone)
        return d.toInstant().toEpochMilli()
    }

    fun at(ms: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
}

/**
 * The crash-loop counter for a freshly installed update (UpdateGuard). Runs first thing in
 * `Application.onCreate`, before any feature loads. Pure, unit-tested.
 *
 * Each start of the watched version records [Guard.lastStartAt]; a start that stays up
 * [STABLE_MS] clears it ([markStable]). A start that finds it still set means the previous start
 * ended early; if that process ended in a way that can be the app's fault (crash, native crash,
 * ANR, a kill by our own watchdog), it counts. [MAX_FAILED_STARTS] → roll back.
 */
object CrashLoop {
    const val MAX_FAILED_STARTS = 3
    const val STABLE_MS = 120_000L
    /** The guard watches a new version for this long after its install. */
    const val ARMED_MS = 24 * 3_600_000L

    data class Guard(
        /** The version being watched; 0 = nothing armed. */
        val version: Long = 0,
        val armedAt: Long = 0,
        val failedStarts: Int = 0,
        /** Wall clock of the current unconfirmed start; 0 = the last start stayed up. */
        val lastStartAt: Long = 0,
    ) {
        val armed get() = version > 0
    }

    enum class Decision { NONE, WATCH, ROLLBACK }

    /**
     * @param lastExitCouldBeCrash why Android says the previous process ended
     *   ([couldBeCrash] of `ApplicationExitInfo.reason`).
     */
    fun onStart(g: Guard, installed: Long, now: Long, lastExitCouldBeCrash: Boolean): Pair<Guard, Decision> {
        if (!g.armed) return g to Decision.NONE
        // Another build was installed by hand (adb), or the watch expired: stand down.
        if (g.version != installed || now - g.armedAt > ARMED_MS) return Guard() to Decision.NONE
        val failed = g.failedStarts + if (g.lastStartAt != 0L && lastExitCouldBeCrash) 1 else 0
        val next = g.copy(failedStarts = failed, lastStartAt = now)
        return next to if (failed >= MAX_FAILED_STARTS) Decision.ROLLBACK else Decision.WATCH
    }

    /** This start stayed up [STABLE_MS]: a crash later on doesn't count against it. */
    fun markStable(g: Guard): Guard = if (!g.armed) g else g.copy(failedStarts = 0, lastStartAt = 0)

    // ApplicationExitInfo.REASON_* (API 30) as plain ints, so the JVM tests don't need android.jar.
    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9

    /**
     * Whether the previous process's exit can be the new version's fault. Our watchdog kills its
     * own process after a Java crash (EXIT_SELF / SIGNALED). A force-stop, an install, a
     * permission change count as fine. null (no record): counts, the safe side.
     */
    fun couldBeCrash(reason: Int?): Boolean = when (reason) {
        null, REASON_UNKNOWN, REASON_EXIT_SELF, REASON_SIGNALED, REASON_LOW_MEMORY, REASON_CRASH, REASON_CRASH_NATIVE,
        REASON_ANR, REASON_INITIALIZATION_FAILURE, REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }
}
