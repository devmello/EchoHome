package com.echobedside.feature.alarm.engine

import com.echobedside.core.model.Alarm
import com.echobedside.core.model.AlarmPhase
import com.echobedside.core.model.NextAlarm
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Next occurrence of [alarm] strictly after [afterMillis] (wall clock), in [zone].
 *
 * Works on instants, so DST is handled: a time in the spring-forward gap (2:30 when clocks jump
 * 2:00 → 3:00) rings at the shifted time (3:30), and a time in the fall-back overlap rings once,
 * at its first (earlier-offset) occurrence. Weekday alarms use the local date's day of week.
 */
fun nextTriggerAfter(alarm: Alarm, afterMillis: Long, zone: ZoneId): Long {
    val time = LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59))
    var date = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate().minusDays(1)
    repeat(10) {
        if (alarm.days.isEmpty() || date.dayOfWeek in alarm.days) {
            val t = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (t > afterMillis) return t
        }
        date = date.plusDays(1)
    }
    error("no trigger for $alarm")
}

/** The alarm that's ringing, snoozed or showing Good morning. Persisted. */
data class ActiveAlarm(
    val alarmId: String,
    val state: State,
    /** When this state began (wall clock). */
    val sinceMillis: Long,
    /** SNOOZED: rings again at this time. */
    val untilMillis: Long = 0,
    /** The occurrence that rang (its trigger time). */
    val occurrence: Long = 0,
    /** Times it snoozed itself because nobody answered; after [AlarmEngine.MAX_AUTO_SNOOZES] it gives up. */
    val autoSnoozes: Int = 0,
) {
    enum class State { RINGING, SNOOZED, MORNING }
}

/** Everything the alarm engine persists. */
data class AlarmData(
    val alarms: List<Alarm> = emptyList(),
    val active: ActiveAlarm? = null,
    /** Per alarm: occurrences at or before this time are done (rang, skipped or set after). */
    val handled: Map<String, Long> = emptyMap(),
    /** Screen brightness before a sunrise ramp began, to restore if it's cancelled. */
    val brightnessBefore: Float? = null,
)

data class Evaluation(
    val phase: AlarmPhase,
    val next: NextAlarm?,
    /** Earliest future time the engine must be evaluated again (AlarmManager exact wake-up). */
    val wakeAtMillis: Long?,
    /** Next time an alarm sounds (setAlarmClock: shown as the system's next alarm). */
    val alarmClockAtMillis: Long?,
)

/**
 * The alarm state machine, free of Android so it can be unit-tested:
 *
 *     Idle → Sunrise (sunriseMinutes before, if enabled) → Ringing ⇄ Snoozed → GoodMorning → Idle
 *
 * - Ringing → Snoozed: [snooze] (the alarm's snoozeMinutes, 9 by default), or automatically
 *   after [AUTO_SNOOZE_MS] of ringing with nobody there (at most [MAX_AUTO_SNOOZES] times, then
 *   it goes quiet: Idle). Snoozed → Ringing when the time is up.
 * - Ringing/Snoozed → GoodMorning: [stop]. GoodMorning → Snoozed: [snooze] ("Ring again").
 * - GoodMorning → Idle: [dismissGoodMorning], or after [MORNING_TIMEOUT_MS].
 * - [stop] during Sunrise skips that occurrence.
 * - One-shot alarms switch themselves off when they ring.
 * - An occurrence missed by up to [GRACE_MS] (process restarting, reboot) still rings.
 */
class AlarmEngine(data: AlarmData = AlarmData(), private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    var data: AlarmData = data
        private set

    fun replace(d: AlarmData) { data = d }

    fun upsert(alarm: Alarm, now: Long) {
        val old = data.alarms.firstOrNull { it.id == alarm.id }
        val list = if (old == null) data.alarms + alarm else data.alarms.map { if (it.id == alarm.id) alarm else it }
        // Anything at or before now doesn't count: setting an alarm for 7:00 at 7:02 must not ring.
        data = data.copy(alarms = list, handled = data.handled + (alarm.id to maxOf(now, data.handled[alarm.id] ?: Long.MIN_VALUE)))
    }

    fun delete(id: String) {
        data = data.copy(
            alarms = data.alarms.filterNot { it.id == id },
            handled = data.handled - id,
            active = data.active?.takeIf { it.alarmId != id },
        )
    }

    fun setEnabled(id: String, enabled: Boolean, now: Long) {
        val a = data.alarms.firstOrNull { it.id == id } ?: return
        if (a.enabled == enabled) return
        data = data.copy(alarms = data.alarms.map { if (it.id == id) it.copy(enabled = enabled) else it })
        if (enabled) data = data.copy(handled = data.handled + (id to now))
        // Turning off the alarm that's snoozed ends it (not one that's ringing: Stop does that).
        val act = data.active
        if (!enabled && act != null && act.alarmId == id && act.state == ActiveAlarm.State.SNOOZED) data = data.copy(active = null)
    }

    fun snooze(now: Long): Boolean {
        val act = data.active ?: return false
        if (act.state == ActiveAlarm.State.SNOOZED) return false
        val alarm = alarm(act.alarmId) ?: return false
        data = data.copy(active = act.copy(state = ActiveAlarm.State.SNOOZED, sinceMillis = now, untilMillis = now + alarm.snoozeMinutes * MIN, autoSnoozes = 0))
        return true
    }

    fun stop(now: Long): Boolean {
        val act = data.active
        if (act != null) {
            if (act.state == ActiveAlarm.State.MORNING) return false
            data = data.copy(active = act.copy(state = ActiveAlarm.State.MORNING, sinceMillis = now, untilMillis = 0))
            return true
        }
        // Sunrise: skip this occurrence.
        val sunrise = upcoming(now).firstOrNull { (a, t) -> inSunrise(a, t, now) } ?: return false
        data = data.copy(handled = data.handled + (sunrise.first.id to sunrise.second))
        return true
    }

    fun dismissGoodMorning(): Boolean {
        if (data.active?.state != ActiveAlarm.State.MORNING) return false
        data = data.copy(active = null)
        return true
    }

    fun setBrightnessBefore(v: Float?) { data = data.copy(brightnessBefore = v) }

    /**
     * Advances the state machine to [now] (rings due alarms, ends snoozes, times out) and
     * reports the phase and when to wake up next.
     */
    fun evaluate(now: Long): Evaluation {
        // 1. Due occurrences (rang at/just before now). The latest one wins; all are marked handled.
        val due = data.alarms.filter { it.enabled }.mapNotNull { a ->
            val from = maxOf(now - GRACE_MS, data.handled[a.id] ?: Long.MIN_VALUE)
            val t = nextTriggerAfter(a, from, zone())
            if (t <= now) a to t else null
        }
        if (due.isNotEmpty()) {
            var handled = data.handled
            due.forEach { (a, t) -> handled = handled + (a.id to t) }
            val (alarm, t) = due.maxBy { it.second }
            val ringingAlready = data.active?.state == ActiveAlarm.State.RINGING
            val list = data.alarms.map { a -> if (due.any { it.first.id == a.id } && a.days.isEmpty()) a.copy(enabled = false) else a }
            data = data.copy(alarms = list, handled = handled,
                active = if (ringingAlready) data.active else ActiveAlarm(alarm.id, ActiveAlarm.State.RINGING, now, occurrence = t))
        }

        // 2. Timeouts of the active alarm.
        data.active?.let { act ->
            if (alarm(act.alarmId) == null) { data = data.copy(active = null); return@let }
            when (act.state) {
                ActiveAlarm.State.RINGING -> if (now - act.sinceMillis >= AUTO_SNOOZE_MS) {
                    if (act.autoSnoozes >= MAX_AUTO_SNOOZES) data = data.copy(active = null)
                    else { snooze(now); data = data.copy(active = data.active?.copy(autoSnoozes = act.autoSnoozes + 1)) }
                }
                ActiveAlarm.State.SNOOZED -> if (now >= act.untilMillis) data = data.copy(active = act.copy(state = ActiveAlarm.State.RINGING, sinceMillis = now, untilMillis = 0))
                ActiveAlarm.State.MORNING -> if (now - act.sinceMillis >= MORNING_TIMEOUT_MS) data = data.copy(active = null)
            }
        }

        // 3. Phase, next alarm, wake-ups.
        val upcoming = upcoming(now)
        val act = data.active
        val activeAlarm = act?.let { alarm(it.alarmId) }
        val phase: AlarmPhase = when {
            act != null && activeAlarm != null -> when (act.state) {
                ActiveAlarm.State.RINGING -> AlarmPhase.Ringing(activeAlarm, act.sinceMillis)
                ActiveAlarm.State.SNOOZED -> AlarmPhase.Snoozed(activeAlarm, act.untilMillis)
                ActiveAlarm.State.MORNING -> AlarmPhase.GoodMorning(activeAlarm)
            }
            else -> upcoming.firstOrNull { (a, t) -> inSunrise(a, t, now) }?.let { (a, t) ->
                val start = t - a.sunriseMinutes * MIN
                AlarmPhase.Sunrise(a, ((now - start).toFloat() / (t - start)).coerceIn(0f, 1f))
            } ?: AlarmPhase.Idle
        }

        val snoozeUntil = act?.takeIf { it.state == ActiveAlarm.State.SNOOZED }?.untilMillis
        val first = upcoming.firstOrNull()
        val next = when {
            snoozeUntil != null && activeAlarm != null && (first == null || snoozeUntil <= first.second) -> NextAlarm(activeAlarm, snoozeUntil)
            first != null -> NextAlarm(first.first, first.second)
            else -> null
        }
        val wakes = buildList {
            upcoming.forEach { (a, t) ->
                add(t)
                if (a.sunrise && a.sunriseMinutes > 0) (t - a.sunriseMinutes * MIN).takeIf { it > now }?.let(::add)
            }
            when (act?.state) {
                ActiveAlarm.State.RINGING -> add(act.sinceMillis + AUTO_SNOOZE_MS)
                ActiveAlarm.State.SNOOZED -> add(act.untilMillis)
                ActiveAlarm.State.MORNING -> add(act.sinceMillis + MORNING_TIMEOUT_MS)
                null -> {}
            }
        }
        return Evaluation(phase, next, wakes.filter { it > now }.minOrNull(), next?.triggerAtMillis)
    }

    /** Enabled alarms' next future occurrences, soonest first. */
    private fun upcoming(now: Long): List<Pair<Alarm, Long>> = data.alarms.filter { it.enabled }
        .map { a -> a to nextTriggerAfter(a, maxOf(now, data.handled[a.id] ?: Long.MIN_VALUE), zone()) }
        .sortedBy { it.second }

    private fun inSunrise(a: Alarm, t: Long, now: Long) =
        a.sunrise && a.sunriseMinutes > 0 && now >= t - a.sunriseMinutes * MIN && now < t

    private fun alarm(id: String) = data.alarms.firstOrNull { it.id == id }

    companion object {
        const val MIN = 60_000L
        /** An occurrence missed by up to this long (restart, reboot) still rings. */
        const val GRACE_MS = 10 * MIN
        /** Nobody pressed anything: snooze automatically (Android's default "silence after 10 min"). */
        const val AUTO_SNOOZE_MS = 10 * MIN
        /** Good morning returns to StandBy on its own after this. */
        const val MORNING_TIMEOUT_MS = 60 * MIN
        const val MAX_AUTO_SNOOZES = 3
    }
}
