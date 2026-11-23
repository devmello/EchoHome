package app.vesper.feature.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.Alarm
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.AlarmStore
import app.vesper.core.model.VesperServices
import app.vesper.core.model.IslandAction
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.NextAlarm
import app.vesper.feature.alarm.engine.AlarmData
import app.vesper.feature.alarm.engine.AlarmEngine
import app.vesper.feature.alarm.engine.Codec
import app.vesper.feature.alarm.engine.Evaluation
import app.vesper.feature.alarm.engine.LeavePlanner
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The real [AlarmStore] (services.alarms): [AlarmEngine] + persistence (filesDir/alarms.json) +
 * exact scheduling + side effects of each phase:
 *  - Sunrise: brightness ramp through services.systemControls (the warm wash is [SunriseOverlay]),
 *    the service kept in the foreground, re-evaluated every [SUNRISE_TICK_MS].
 *  - Ringing: [AlarmService] rings, the alarm screen opens, an ALARM_RINGING island activity.
 *  - Snoozed: a TIMER island activity counting down to the next ring.
 *
 * Leave-by and skip-holidays: [refreshPlan] turns the calendar and `services.travel` into the engine's
 * plan (earlier rings, day-off dates) at a calendar change, an alarm edit, overnight and 3 h before
 * a leave-by alarm; the engine stays pure and every change goes through [evaluate], which moves the
 * exact system alarm.
 *
 * Scheduling: setAlarmClock for the next ring (exact, allowed in idle, the system's "next alarm"),
 * setExactAndAllowWhileIdle for other wake-ups (sunrise start, auto-snooze, timeouts). Both fire
 * [AlarmTriggerReceiver] → [evaluate]. [AlarmSystemReceiver] re-evaluates after boot, clock and
 * time-zone changes and app updates. Main thread only.
 */
class RealAlarmStore(private val ctx: Context, private val services: VesperServices) : AlarmStore {
    private val file = AtomicFile(File(ctx.filesDir, "alarms.json"))
    private val engine = AlarmEngine(load(), adjust = { AlarmSettings.apply(services.settings, it) }, timeText = { AlarmFormat.clock(ctx, it) })
    /** What's on disk. Compared with the engine after every evaluate, so user actions (snooze, stop,
     *  done, edits), which change the engine before evaluate runs, are saved too. */
    private var saved: AlarmData = engine.data
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AlarmManager::class.java)

    private val _alarms = MutableStateFlow(engine.data.alarms)
    private val _next = MutableStateFlow<NextAlarm?>(null)
    private val _phase = MutableStateFlow<AlarmPhase>(AlarmPhase.Idle)
    override val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    override val next: StateFlow<NextAlarm?> = _next.asStateFlow()
    override val phase: StateFlow<AlarmPhase> = _phase.asStateFlow()

    private val tick = Runnable { evaluate("tick") }

    private val startedAt = SystemClock.elapsedRealtime()
    private var planJob: Job? = null
    private var planAgain: Boolean? = null
    /** location|depart-quarter → (minutes, when asked). Spares the travel service on every calendar tick. */
    private val travelCache = HashMap<String, Pair<Int?, Long>>()

    init {
        services.scope.launch { kotlinx.coroutines.yield(); services.calendar.events.collect { refreshPlan(false) } }
        // Settings apply live: a new sunrise / snooze length reschedules at once.
        services.scope.launch {
            services.settings.changes.filter { it.startsWith("alarm.") }.collect { evaluate("settings") }
        }
    }

    override fun upsert(alarm: Alarm) = change { engine.upsert(alarm, now()) }
    override fun delete(id: String) = change { engine.delete(id) }
    override fun setEnabled(id: String, enabled: Boolean) = change { engine.setEnabled(id, enabled, now()) }
    override fun snooze() = change { engine.snooze(now()) }
    override fun stop() = change { engine.stop(now()) }
    override fun dismissGoodMorning() = change { engine.dismissGoodMorning() }

    private inline fun change(block: () -> Unit) {
        block()
        evaluate("change")
        refreshPlan(false)
    }

    /**
     * Recomputes the day-off dates and leave-by shifts from the calendar and travel time. [force]
     * asks the travel service again (overnight, 3 h before) instead of using a recent answer.
     */
    fun refreshPlan(force: Boolean) {
        if (planJob?.isActive == true) { planAgain = (planAgain ?: false) || force; return }
        planJob = services.scope.launch {
            val zone = ZoneId.systemDefault()
            val events = services.calendar.events.value
            val now = now()
            val known = events.isNotEmpty() || SystemClock.elapsedRealtime() - startedAt > CALENDAR_GRACE_MS
            val dayOffs = LeavePlanner.dayOffs(engine.data.dayOffs, events, now, zone, known)
            val alarms = engine.data.alarms.filter { it.enabled && it.leaveBy }
            val shifts = if (alarms.isEmpty()) engine.data.shifts.filterKeys { false } else try {
                LeavePlanner.shifts(alarms, events, engine.data.shifts, now, zone, known) { loc, at -> travelMinutes(loc, at, force) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { Log.w(AlarmRuntime.TAG, "Leave-by planning failed", e); engine.data.shifts }
            if (shifts != engine.data.shifts || dayOffs != engine.data.dayOffs) {
                engine.setPlan(shifts, dayOffs)
                evaluate("plan")
            } else scheduleRefresh()
        }.also { job ->
            job.invokeOnCompletion {
                val again = planAgain
                planAgain = null
                if (again != null) services.scope.launch { refreshPlan(again) }
            }
        }
    }

    private suspend fun travelMinutes(location: String, departAt: Long, force: Boolean): Int? {
        val key = "$location|${departAt / (15 * 60_000L)}"
        val hit = travelCache[key]
        if (!force && hit != null && SystemClock.elapsedRealtime() - hit.second < TRAVEL_CACHE_MS) return hit.first
        val m = try { services.travel.minutesTo(location, departAt) } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Log.w(AlarmRuntime.TAG, "Travel estimate failed", e); null }
        travelCache[key] = m to SystemClock.elapsedRealtime()
        if (travelCache.size > 32) travelCache.keys.firstOrNull()?.let(travelCache::remove)
        return m
    }

    /** An inexact wake-up for the next plan refresh (overnight, 3 h before a leave-by alarm). */
    private fun scheduleRefresh() {
        val pi = trigger(REQ_REFRESH, AlarmTriggerReceiver.ACTION_REFRESH)
        val at = LeavePlanner.nextRefreshAt(engine.data.alarms, now(), ZoneId.systemDefault())
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Advances the state machine to now, applies the phase and reschedules. Safe to call any time. */
    fun evaluate(reason: String) {
        val ev = engine.evaluate(now())
        val oldPhase = _phase.value
        _alarms.value = engine.data.alarms
        _next.value = ev.next
        _phase.value = ev.phase
        if (oldPhase::class != ev.phase::class) Log.i(AlarmRuntime.TAG, "phase ${oldPhase.name()} → ${ev.phase.name()} ($reason)")
        applyPhase(oldPhase, ev.phase)
        if (engine.data != saved) save()
        schedule(ev)
        scheduleRefresh()
    }

    // --- phase side effects ---

    private fun applyPhase(old: AlarmPhase, new: AlarmPhase) {
        handler.removeCallbacks(tick)
        when (new) {
            is AlarmPhase.Sunrise -> {
                if (engine.data.brightnessBefore == null) engine.setBrightnessBefore(services.system.state.value.brightness)
                val from = engine.data.brightnessBefore ?: 0.3f
                // Slow start like a real sunrise; full brightness when it rings.
                val to = AlarmSettings.sunriseBrightness(services.settings).coerceAtLeast(from)
                services.systemControls.setBrightness(from + (to - from) * new.progress.pow(1.6f))
                handler.postDelayed(tick, SUNRISE_TICK_MS)
            }
            is AlarmPhase.Ringing -> {
                if (old !is AlarmPhase.Ringing) {
                    if (new.alarm.sunrise || engine.data.brightnessBefore != null) services.systemControls.setBrightness(AlarmSettings.sunriseBrightness(services.settings))
                    engine.setBrightnessBefore(null)
                    showAlarmScreen()
                }
            }
            AlarmPhase.Idle -> {
                // A sunrise that ended without ringing (alarm switched off, skipped): put the brightness back.
                engine.data.brightnessBefore?.let { services.systemControls.setBrightness(it); engine.setBrightnessBefore(null) }
            }
            else -> {}
        }
        AlarmRuntime.request(ctx) {
            it.copy(
                alarm = new is AlarmPhase.Ringing,
                alarmTitle = (new as? AlarmPhase.Ringing)?.let { r -> "${AlarmFormat.alarmTime(ctx, r.alarm)} · ${r.alarm.label}" } ?: "",
                keepAlive = new is AlarmPhase.Sunrise,
                keepAliveTitle = (new as? AlarmPhase.Sunrise)?.let { s -> "Sunrise · alarm at ${AlarmFormat.alarmTime(ctx, s.alarm)}" } ?: "",
            )
        }
        publishIsland(new)
    }

    private fun publishIsland(p: AlarmPhase) {
        val island = services.island
        when (p) {
            is AlarmPhase.Ringing -> island.publish(LiveActivity(
                ISLAND_ID, ActivityKind.ALARM_RINGING,
                LiveContent.AlarmRinging(p.alarm.label, AlarmFormat.alarmTime(ctx, p.alarm)),
                actions = listOf(
                    IslandAction("snooze", "Snooze", IslandAction.Style.PRIMARY) { snooze() },
                    IslandAction("stop", "Stop") { stop() },
                ),
                ownerScreen = SCREEN_ID,
            ))
            is AlarmPhase.Snoozed -> {
                val snoozeMs = p.alarm.snoozeMinutes * 60_000L
                val endsAt = SystemClock.elapsedRealtime() + (p.untilMillis - now())
                val content = LiveContent.Timer("Snoozed", snoozeMs, endsAt)
                val existing = island.activities.value.firstOrNull { it.id == ISLAND_ID }
                // Don't republish every evaluation (endsAt jitters by a few ms).
                val same = (existing?.content as? LiveContent.Timer)?.let { kotlin.math.abs(it.endsAtElapsed - endsAt) < 1000 } == true
                if (!same) island.publish(LiveActivity(
                    ISLAND_ID, ActivityKind.TIMER, content,
                    actions = listOf(IslandAction("stop", "Stop") { stop() }),
                    ownerScreen = SCREEN_ID,
                ))
            }
            else -> island.end(ISLAND_ID)
        }
    }

    private fun showAlarmScreen() {
        // Brings our launcher to the front on the alarm screen (MainActivity reads `screen`). If
        // another app is in front, Android may block this; the full-screen-intent notification
        // posted by AlarmService and the island cover that case.
        try {
            ctx.startActivity(AlarmService.launchIntent(ctx, SCREEN_ID))
        } catch (e: Exception) {
            Log.w(AlarmRuntime.TAG, "Can't open the alarm screen", e)
            services.navigator.show(SCREEN_ID)
        }
    }

    // --- scheduling ---

    private fun schedule(ev: Evaluation) {
        val clock = trigger(REQ_CLOCK)
        val wake = trigger(REQ_WAKE)
        if (ev.alarmClockAtMillis != null) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(ev.alarmClockAtMillis, AlarmService.openScreen(ctx, SCREEN_ID, 3)), clock)
        } else am.cancel(clock)
        val w = ev.wakeAtMillis
        if (w != null && w != ev.alarmClockAtMillis) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, w, wake) else am.cancel(wake)
    }

    private fun trigger(req: Int, action: String = AlarmTriggerReceiver.ACTION_ALARM): PendingIntent = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, AlarmTriggerReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // --- persistence ---

    private fun load(): AlarmData = try {
        if (file.baseFile.exists()) Codec.alarmsFromJson(String(file.readFully())) else AlarmData()
    } catch (e: Exception) {
        Log.e(AlarmRuntime.TAG, "alarms.json unreadable, starting empty", e)
        AlarmData()
    }

    private fun save() {
        val out = file.startWrite()
        try {
            val data = engine.data
            out.write(Codec.alarmsToJson(data).toByteArray())
            file.finishWrite(out)
            saved = data
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(AlarmRuntime.TAG, "Saving alarms failed", e)
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun AlarmPhase.name() = this::class.simpleName ?: "?"

    companion object {
        const val SCREEN_ID = "alarm"
        const val ISLAND_ID = "alarm"
        const val SUNRISE_TICK_MS = 10_000L
        private const val REQ_CLOCK = 1
        private const val REQ_WAKE = 2
        private const val REQ_REFRESH = 4
        /** An empty calendar this soon after start is "not loaded yet", not "no events". */
        private const val CALENDAR_GRACE_MS = 2 * 60_000L
        private const val TRAVEL_CACHE_MS = 3 * 60 * 60_000L
    }
}
