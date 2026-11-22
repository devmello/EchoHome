package app.vesper.core.model

import android.os.SystemClock
import java.time.DayOfWeek
import kotlinx.coroutines.flow.StateFlow

/** NOTES §6 "Alarms": exact alarms, sunrise ramp, 9-minute snooze, Good-morning summary. */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    /** Empty = one-shot (next occurrence). */
    val days: Set<DayOfWeek> = emptySet(),
    val label: String = "Alarm",
    val enabled: Boolean = true,
    /** Ramp screen brightness + warm colour before the alarm. */
    val sunrise: Boolean = true,
    val sunriseMinutes: Int = 30,
    val snoozeMinutes: Int = 9,
    /** Owner profile id; null = shared. */
    val profileId: String? = null,
)

/** The next time an alarm fires. [triggerAtMillis] is wall-clock (System.currentTimeMillis). */
data class NextAlarm(val alarm: Alarm, val triggerAtMillis: Long)

sealed interface AlarmPhase {
    data object Idle : AlarmPhase
    data class Sunrise(val alarm: Alarm, /** 0..1 */ val progress: Float) : AlarmPhase
    data class Ringing(val alarm: Alarm, val sinceMillis: Long) : AlarmPhase
    data class Snoozed(val alarm: Alarm, val untilMillis: Long) : AlarmPhase
    /** After Stop: the Good-morning screen is shown until dismissed. */
    data class GoodMorning(val alarm: Alarm) : AlarmPhase
}

interface AlarmStore {
    val alarms: StateFlow<List<Alarm>>
    /** Drives the Digital face's alarm complication and the analog face's 3 o'clock label. */
    val next: StateFlow<NextAlarm?>
    val phase: StateFlow<AlarmPhase>

    fun upsert(alarm: Alarm)
    fun delete(id: String)
    fun setEnabled(id: String, enabled: Boolean)
    fun snooze()
    fun stop()
    fun dismissGoodMorning()
}

data class VesperTimer(
    val id: String,
    val label: String,
    val durationMs: Long,
    /** SystemClock.elapsedRealtime() at zero while running. */
    val endsAtElapsed: Long,
    val state: State,
    val pausedRemainingMs: Long = 0,
) {
    enum class State { RUNNING, PAUSED, DONE }

    fun remainingMs(now: Long = SystemClock.elapsedRealtime()): Long = when (state) {
        State.RUNNING -> (endsAtElapsed - now).coerceAtLeast(0)
        State.PAUSED -> pausedRemainingMs
        State.DONE -> 0
    }
}

interface TimerStore {
    /** At most [MAX_TIMERS], oldest first. */
    val timers: StateFlow<List<VesperTimer>>
    /** Returns null when [MAX_TIMERS] are already running. */
    fun start(durationMs: Long, label: String = ""): VesperTimer?
    fun pause(id: String)
    fun resume(id: String)
    fun addTime(id: String, ms: Long)
    fun cancel(id: String)
    /** Silences a DONE timer and removes it. */
    fun dismiss(id: String)

    companion object { const val MAX_TIMERS = 3 }
}

/** JSON form of [Alarm] for the web settings page (same format as `:feature:alarm`'s store). */
object AlarmJson {
    fun toJson(a: Alarm): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", a.id); put("hour", a.hour); put("minute", a.minute)
        put("days", org.json.JSONArray().apply { a.days.sorted().forEach { put(it.value) } })
        put("label", a.label); put("enabled", a.enabled); put("sunrise", a.sunrise)
        put("sunriseMinutes", a.sunriseMinutes); put("snoozeMinutes", a.snoozeMinutes)
        a.profileId?.let { put("profileId", it) }
    }

    /** null when [o] has no id or an out-of-range time. */
    fun fromJson(o: org.json.JSONObject?): Alarm? {
        o ?: return null
        val id = o.optString("id").ifEmpty { return null }
        val hour = o.optInt("hour", 7); val minute = o.optInt("minute", 0)
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = o.optJSONArray("days")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optInt(i).takeIf { it in 1..7 }?.let(java.time.DayOfWeek::of) }.toSet()
        } ?: emptySet()
        return Alarm(
            id = id, hour = hour, minute = minute, days = days,
            label = o.optString("label", "Alarm"), enabled = o.optBoolean("enabled", true),
            sunrise = o.optBoolean("sunrise", true), sunriseMinutes = o.optInt("sunriseMinutes", 30).coerceIn(0, 120),
            snoozeMinutes = o.optInt("snoozeMinutes", 9).coerceIn(1, 60),
            profileId = if (o.has("profileId") && !o.isNull("profileId")) o.getString("profileId") else null,
        )
    }
}
