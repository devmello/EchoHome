package com.echobedside.feature.alarm.engine

import com.echobedside.core.model.EchoTimer
import com.echobedside.core.model.EchoTimer.State
import com.echobedside.core.model.TimerStore

/**
 * Timers as persisted. End times are SystemClock.elapsedRealtime() (immune to wall-clock changes),
 * stamped with the boot they belong to: after a reboot elapsedRealtime restarts from 0, so [load]
 * moves them using the wall clock saved alongside.
 */
data class TimerData(
    val timers: List<EchoTimer> = emptyList(),
    val bootCount: Int = -1,
    val savedElapsed: Long = 0,
    val savedWall: Long = 0,
    /** DONE timers whose alert has been silenced (auto after [TimerEngine.ALERT_MS]). */
    val silenced: Set<String> = emptySet(),
)

/** Timer logic without Android. [elapsed] = SystemClock.elapsedRealtime, [wall] = System.currentTimeMillis. */
class TimerEngine(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val newId: () -> String,
    /** How long a finished timer sounds (setting "timer.alert.minutes"). */
    private val alertMs: () -> Long = { ALERT_MS },
) {
    var timers: List<EchoTimer> = emptyList()
        private set
    var silenced: Set<String> = emptySet()
        private set

    fun start(durationMs: Long, label: String): EchoTimer? {
        if (timers.size >= TimerStore.MAX_TIMERS || durationMs <= 0) return null
        val t = EchoTimer(newId(), label.ifBlank { "Timer" }, durationMs, elapsed() + durationMs, State.RUNNING)
        timers = timers + t
        return t
    }

    /** Demo / restore: a running timer with [remainingMs] of [durationMs] left. */
    fun startPartial(durationMs: Long, remainingMs: Long, label: String): EchoTimer? {
        if (timers.size >= TimerStore.MAX_TIMERS) return null
        val t = EchoTimer(newId(), label.ifBlank { "Timer" }, durationMs, elapsed() + remainingMs, State.RUNNING)
        timers = timers + t
        return t
    }

    fun pause(id: String) = mutate(id) { if (it.state == State.RUNNING) it.copy(state = State.PAUSED, pausedRemainingMs = it.remainingMs(elapsed())) else it }

    fun resume(id: String) = mutate(id) { if (it.state == State.PAUSED) it.copy(state = State.RUNNING, endsAtElapsed = elapsed() + it.pausedRemainingMs, pausedRemainingMs = 0) else it }

    fun addTime(id: String, ms: Long) = mutate(id) {
        when (it.state) {
            State.RUNNING -> it.copy(endsAtElapsed = it.endsAtElapsed + ms, durationMs = it.durationMs + ms)
            State.PAUSED -> it.copy(pausedRemainingMs = it.pausedRemainingMs + ms, durationMs = it.durationMs + ms)
            // "+1 min" on a finished timer runs it again for that long.
            State.DONE -> it.copy(state = State.RUNNING, endsAtElapsed = elapsed() + ms, durationMs = ms)
        }
    }

    /** Runs a timer again for its full duration (the Repeat button on a finished timer). */
    fun restart(id: String) = mutate(id) { it.copy(state = State.RUNNING, endsAtElapsed = elapsed() + it.durationMs, pausedRemainingMs = 0) }

    fun cancel(id: String): Boolean {
        val before = timers.size
        timers = timers.filterNot { it.id == id }
        silenced = silenced - id
        return timers.size != before
    }

    fun silence(id: String) { if (timers.any { it.id == id && it.state == State.DONE }) silenced = silenced + id }

    /** Marks finished timers DONE. Returns true if anything changed. */
    fun tick(): Boolean {
        val now = elapsed()
        var changed = false
        timers = timers.map {
            if (it.state == State.RUNNING && it.endsAtElapsed <= now) { changed = true; it.copy(state = State.DONE) } else it
        }
        // Finished long enough ago: stop alerting.
        timers.filter { it.state == State.DONE && it.id !in silenced && now - it.endsAtElapsed >= alertMs() }.forEach { silenced = silenced + it.id; changed = true }
        return changed
    }

    /** DONE timers that should be sounding now. */
    fun alerting(): List<EchoTimer> = timers.filter { it.state == State.DONE && it.id !in silenced }

    /** Next time something changes on its own: a running timer ends, or an alert times out. */
    fun nextWakeElapsed(): Long? {
        val ends = timers.filter { it.state == State.RUNNING }.map { it.endsAtElapsed }
        val alerts = alerting().map { it.endsAtElapsed + alertMs() }
        return (ends + alerts).minOrNull()
    }

    fun save(bootCount: Int) = TimerData(timers, bootCount, elapsed(), wall(), silenced)

    /** Restores [d]; if it was saved during another boot, end times are moved via the wall clock. */
    fun load(d: TimerData, bootCount: Int) {
        val now = elapsed()
        val sameBoot = d.bootCount == bootCount && bootCount >= 0 && now >= d.savedElapsed
        val shift = if (sameBoot) 0L else {
            // elapsed-at-save corresponds to wall-at-save; map it onto this boot's elapsed clock.
            (now - (wall() - d.savedWall)) - d.savedElapsed
        }
        timers = d.timers.take(TimerStore.MAX_TIMERS).map { if (it.state == State.PAUSED) it else it.copy(endsAtElapsed = it.endsAtElapsed + shift) }
        silenced = d.silenced.filter { id -> timers.any { it.id == id } }.toSet()
        tick()
    }

    private fun mutate(id: String, f: (EchoTimer) -> EchoTimer): Boolean {
        var changed = false
        timers = timers.map { if (it.id == id) f(it).also { n -> if (n != it) changed = true } else it }
        if (changed) silenced = silenced - id
        return changed
    }

    companion object {
        /** A finished timer sounds for this long, then goes quiet (the card stays until dismissed). */
        const val ALERT_MS = 3 * 60_000L
    }
}
