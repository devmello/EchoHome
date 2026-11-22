package com.echobedside.feature.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.Alarm
import com.echobedside.core.model.AlarmPhase
import com.echobedside.core.model.AlarmStore
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.NextAlarm
import com.echobedside.feature.alarm.engine.AlarmData
import com.echobedside.feature.alarm.engine.AlarmEngine
import com.echobedside.feature.alarm.engine.Codec
import com.echobedside.feature.alarm.engine.Evaluation
import java.io.File
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
 * Scheduling: setAlarmClock for the next ring (exact, allowed in idle, the system's "next alarm"),
 * setExactAndAllowWhileIdle for other wake-ups (sunrise start, auto-snooze, timeouts). Both fire
 * [AlarmTriggerReceiver] → [evaluate]. [AlarmSystemReceiver] re-evaluates after boot, clock and
 * time-zone changes and app updates. Main thread only.
 */
class RealAlarmStore(private val ctx: Context, private val services: EchoServices) : AlarmStore {
    private val file = AtomicFile(File(ctx.filesDir, "alarms.json"))
    private val engine = AlarmEngine(load(), adjust = { AlarmSettings.apply(services.settings, it) })
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AlarmManager::class.java)

    private val _alarms = MutableStateFlow(engine.data.alarms)
    private val _next = MutableStateFlow<NextAlarm?>(null)
    private val _phase = MutableStateFlow<AlarmPhase>(AlarmPhase.Idle)
    override val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()
    override val next: StateFlow<NextAlarm?> = _next.asStateFlow()
    override val phase: StateFlow<AlarmPhase> = _phase.asStateFlow()

    private val tick = Runnable { evaluate("tick") }

    init {
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
    }

    /** Advances the state machine to now, applies the phase and reschedules. Safe to call any time. */
    fun evaluate(reason: String) {
        val before = engine.data
        val ev = engine.evaluate(now())
        val oldPhase = _phase.value
        _alarms.value = engine.data.alarms
        _next.value = ev.next
        _phase.value = ev.phase
        if (oldPhase::class != ev.phase::class) Log.i(AlarmRuntime.TAG, "phase ${oldPhase.name()} → ${ev.phase.name()} ($reason)")
        applyPhase(oldPhase, ev.phase)
        if (engine.data != before) save()
        schedule(ev)
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

    private fun trigger(req: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, AlarmTriggerReceiver::class.java).setAction(AlarmTriggerReceiver.ACTION_ALARM),
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
            out.write(Codec.alarmsToJson(engine.data).toByteArray())
            file.finishWrite(out)
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
    }
}
