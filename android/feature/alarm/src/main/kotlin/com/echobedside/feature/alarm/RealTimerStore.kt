package com.echobedside.feature.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.EchoTimer
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.TimerStore
import com.echobedside.feature.alarm.engine.Codec
import com.echobedside.feature.alarm.engine.TimerEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [TimerStore] (services.timers). Survives the process being killed: end times are
 * persisted (filesDir/timers.json) and the next end is an exact ELAPSED_REALTIME_WAKEUP alarm that
 * restarts the process and marks it DONE ([AlarmTriggerReceiver]). A DONE timer chimes for
 * [TimerEngine.ALERT_MS] through [AlarmService] and stays as a card until dismissed.
 * Island: a TIMER activity per running/paused timer, TIMER_DONE when it finishes. Main thread only.
 */
class RealTimerStore(private val ctx: Context, private val services: EchoServices) : TimerStore {
    private val file = AtomicFile(File(ctx.filesDir, "timers.json"))
    private val engine = TimerEngine(SystemClock::elapsedRealtime, System::currentTimeMillis) { java.util.UUID.randomUUID().toString().take(8) }
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AlarmManager::class.java)
    private val bootCount = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private val _timers = MutableStateFlow<List<EchoTimer>>(emptyList())
    override val timers: StateFlow<List<EchoTimer>> = _timers.asStateFlow()

    private val tickRunnable = Runnable { tick() }

    init {
        try {
            if (file.baseFile.exists()) engine.load(Codec.timersFromJson(String(file.readFully())), bootCount)
        } catch (e: Exception) {
            Log.e(AlarmRuntime.TAG, "timers.json unreadable, starting empty", e)
        }
    }

    /** Applies the loaded state (island, sound, wake-ups). Called once after every feature installed. */
    fun start() = changed()

    override fun start(durationMs: Long, label: String): EchoTimer? = engine.start(durationMs, label).also { if (it != null) changed() }
    override fun pause(id: String) { if (engine.pause(id)) changed() }
    override fun resume(id: String) { if (engine.resume(id)) changed() }
    override fun addTime(id: String, ms: Long) { if (engine.addTime(id, ms)) changed() }
    override fun cancel(id: String) { if (engine.cancel(id)) changed() }
    override fun dismiss(id: String) = cancel(id)

    /** Runs a finished timer again for its full length (the card's Repeat button). */
    fun restart(id: String) { if (engine.restart(id)) changed() }

    /** Demo data (Timers.dc.html): Pasta 7:42 of 10 min, Oven 18:05 of 25 min, Tea 0:21 of 4 min. */
    fun seedDemo() {
        if (engine.timers.isNotEmpty()) return
        engine.startPartial(600_000, 462_000, "Pasta")
        engine.startPartial(1_500_000, 1_085_000, "Oven")
        engine.startPartial(240_000, 21_000, "Tea")
        changed()
    }

    fun tick() {
        if (engine.tick()) changed() else schedule()
    }

    private fun changed() {
        engine.tick()
        _timers.value = engine.timers
        save()
        syncIsland()
        val alerting = engine.alerting()
        AlarmRuntime.request(ctx) {
            it.copy(timer = alerting.isNotEmpty(), timerTitle = alerting.joinToString { t -> "${t.label} timer done" })
        }
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tickRunnable)
        val pi = PendingIntent.getBroadcast(ctx, REQ_TIMER, Intent(ctx, AlarmTriggerReceiver::class.java).setAction(AlarmTriggerReceiver.ACTION_TIMER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = engine.nextWakeElapsed()
        if (next == null) { am.cancel(pi); return }
        // In-process for precision while we're alive; AlarmManager for when we're not.
        handler.postDelayed(tickRunnable, (next - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 5)
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, next, pi)
    }

    private fun syncIsland() {
        val island = services.island
        val ids = engine.timers.map { ISLAND_PREFIX + it.id }.toSet()
        island.activities.value.filter { it.id.startsWith(ISLAND_PREFIX) && it.id !in ids }.forEach { island.end(it.id) }
        for (t in engine.timers) {
            val done = t.state == EchoTimer.State.DONE
            val content = if (done) LiveContent.TimerDone(t.label)
                else LiveContent.Timer(t.label, t.durationMs, t.endsAtElapsed, if (t.state == EchoTimer.State.PAUSED) t.pausedRemainingMs else null)
            val existing = island.activities.value.firstOrNull { it.id == ISLAND_PREFIX + t.id }
            if (existing != null && existing.content == content) continue
            val actions = when (t.state) {
                EchoTimer.State.RUNNING -> listOf(
                    IslandAction("pause", "Pause", IslandAction.Style.PRIMARY) { pause(t.id) },
                    IslandAction("cancel", "Cancel") { cancel(t.id) },
                )
                EchoTimer.State.PAUSED -> listOf(
                    IslandAction("resume", "Resume", IslandAction.Style.PRIMARY) { resume(t.id) },
                    IslandAction("cancel", "Cancel") { cancel(t.id) },
                )
                EchoTimer.State.DONE -> listOf(
                    IslandAction("stop", "Stop", IslandAction.Style.PRIMARY) { dismiss(t.id) },
                    IslandAction("repeat", "Repeat") { restart(t.id) },
                )
            }
            island.publish(LiveActivity(
                ISLAND_PREFIX + t.id,
                if (done) ActivityKind.TIMER_DONE else ActivityKind.TIMER,
                content, actions,
                // Finished timers stay as cards on the Timers screen but leave the island after the alert.
                staleAfterElapsed = if (done) t.endsAtElapsed + TimerEngine.ALERT_MS else null,
                ownerScreen = SCREEN_ID,
            ))
        }
    }

    private fun save() {
        val out = file.startWrite()
        try {
            out.write(Codec.timersToJson(engine.save(bootCount)).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(AlarmRuntime.TAG, "Saving timers failed", e)
        }
    }

    companion object {
        const val SCREEN_ID = "timers"
        const val ISLAND_PREFIX = "timer:"
        private const val REQ_TIMER = 10
    }
}
