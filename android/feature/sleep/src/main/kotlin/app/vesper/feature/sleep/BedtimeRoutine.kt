package app.vesper.feature.sleep

import app.vesper.core.model.BedtimeReport
import app.vesper.core.model.BedtimeRequest
import app.vesper.core.model.NightMode
import app.vesper.core.model.SleepSound
import app.vesper.core.model.VesperServices
import java.time.Instant
import java.time.ZoneId

/**
 * "Good night" (voice tool `bedtime`, the Sleep screen's button). In order:
 *  1. reads the next alarm (the answer says it);
 *  2. Do Not Disturb on until that alarm (or the fallback wake time when none is set);
 *  3. Night mode on;
 *  4. optionally dims the screen;
 *  5. starts the default sleep sound with the default timer.
 * Each step follows its setting unless the [BedtimeRequest] overrides it. What the routine turned
 * on is remembered ([SleepSettings.DND_UNTIL] and friends) and handed back at the wake time by
 * [WakeRestore]; what was already on is left alone. Main thread; pure apart from [services].
 */
internal class BedtimeRoutine(
    private val services: () -> VesperServices,
    private val play: (SleepSound, Int) -> Boolean,
    /** Arrange for [WakeRestore.run] at this wall-clock time. */
    private val scheduleWake: (Long) -> Unit,
    private val wall: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun run(req: BedtimeRequest = BedtimeRequest()): BedtimeReport {
        val s = services()
        val st = s.settings
        val now = wall()
        val failed = ArrayList<String>()

        val next = s.alarms.next.value?.takeIf { it.triggerAtMillis > now }
        val until = next?.triggerAtMillis ?: fallbackWake(now)

        // Do Not Disturb until the alarm (alarms pass DND's alarm exception)
        val state0 = s.system.state.value
        var dndOn = state0.doNotDisturb
        if (SleepSettings.bedtimeDnd(st) && !dndOn) {
            if (s.systemControls.setDoNotDisturb(true)) {
                dndOn = true
                st.putBoolean(DND_BY_US, true)
            } else failed += "Do Not Disturb"
        }

        // Night mode on (remember what it was, to give it back in the morning)
        var nightOn = state0.nightMode == NightMode.ON
        if (SleepSettings.bedtimeNight(st) && !nightOn) {
            if (s.systemControls.setNightMode(NightMode.ON)) {
                nightOn = true
                st.putString(NIGHT_PREV, state0.nightMode.name)
            } else failed += "Night mode"
        }

        // Dim
        var dimmed: Float? = null
        if (req.dim ?: SleepSettings.bedtimeDim(st)) {
            val to = SleepSettings.dimLevel(st)
            if (s.system.state.value.brightness <= to) dimmed = s.system.state.value.brightness
            else if (s.systemControls.setBrightness(to, showHud = false)) dimmed = to
            else failed += "Brightness"
        }

        // Sleep sound
        var sound: SleepSound? = null
        val timer = (req.timerMinutes ?: SleepSettings.timer(st)).coerceAtLeast(0)
        if (req.playSound ?: (req.sound != null || SleepSettings.bedtimeSound(st))) {
            val want = req.sound ?: SleepSettings.sound(st)
            if (play(want, timer)) sound = want else failed += "Sleep sound"
        }

        val restore = st.getBoolean(DND_BY_US, false) || st.getString(NIGHT_PREV, "").isNotEmpty()
        if (restore) {
            st.putString(SleepSettings.DND_UNTIL, until.toString())
            scheduleWake(until)
        }
        return BedtimeReport(
            alarmAtMillis = next?.triggerAtMillis, alarmLabel = next?.alarm?.label,
            dndOn = dndOn, dndUntilMillis = if (dndOn) until else null,
            nightOn = nightOn, sound = sound, timerMinutes = if (sound != null) timer else 0,
            dimmedTo = dimmed, failed = failed,
        )
    }

    /** The next [SleepSettings.wakeFallback] time of day after [now]. */
    fun fallbackWake(now: Long): Long {
        val (h, m) = SleepSettings.wakeFallback(services().settings)
        val z = zone()
        var t = Instant.ofEpochMilli(now).atZone(z).toLocalDate().atTime(h, m).atZone(z)
        if (t.toInstant().toEpochMilli() <= now) t = t.plusDays(1)
        return t.toInstant().toEpochMilli()
    }

    companion object {
        const val DND_BY_US = "sleep.state.dndByUs"
        const val NIGHT_PREV = "sleep.state.nightPrev"
    }
}

/**
 * At the wake time (or the first start after it): turn off the Do Not Disturb the routine turned
 * on, and put Night mode back to what it was, unless the user changed them since. Pure.
 */
internal object WakeRestore {
    /** Returns the pending wake time if it's still ahead (nothing done), else null after restoring. */
    fun run(s: VesperServices, now: Long): Long? {
        val st = s.settings
        val until = st.getString(SleepSettings.DND_UNTIL, "").toLongOrNull() ?: run { clear(s); return null }
        if (now < until - EARLY_MS) return until
        val state = s.system.state.value
        if (st.getBoolean(BedtimeRoutine.DND_BY_US, false) && state.doNotDisturb) s.systemControls.setDoNotDisturb(false)
        val prev = st.getString(BedtimeRoutine.NIGHT_PREV, "")
        if (prev.isNotEmpty() && state.nightMode == NightMode.ON) {
            val mode = NightMode.values().firstOrNull { it.name == prev } ?: NightMode.AUTO
            s.systemControls.setNightMode(mode)
        }
        clear(s)
        return null
    }

    private fun clear(s: VesperServices) {
        val st = s.settings
        if (st.getString(SleepSettings.DND_UNTIL, "").isNotEmpty()) st.remove(SleepSettings.DND_UNTIL)
        if (st.getBoolean(BedtimeRoutine.DND_BY_US, false)) st.remove(BedtimeRoutine.DND_BY_US)
        if (st.getString(BedtimeRoutine.NIGHT_PREV, "").isNotEmpty()) st.remove(BedtimeRoutine.NIGHT_PREV)
    }

    /** AlarmManager may deliver a little early. */
    private const val EARLY_MS = 60_000L
}
