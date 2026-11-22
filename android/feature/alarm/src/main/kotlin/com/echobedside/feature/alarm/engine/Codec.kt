package com.echobedside.feature.alarm.engine

import com.echobedside.core.model.Alarm
import com.echobedside.core.model.EchoTimer
import java.time.DayOfWeek
import org.json.JSONArray
import org.json.JSONObject

/** JSON for the alarm and timer files (filesDir/alarms.json, timers.json). Tolerant of missing fields. */
object Codec {
    fun alarmsToJson(d: AlarmData): String = JSONObject().apply {
        put("v", 1)
        put("alarms", JSONArray().apply { d.alarms.forEach { put(alarmToJson(it)) } })
        d.active?.let { a ->
            put("active", JSONObject().apply {
                put("alarmId", a.alarmId); put("state", a.state.name); put("since", a.sinceMillis)
                put("until", a.untilMillis); put("occurrence", a.occurrence); put("autoSnoozes", a.autoSnoozes)
            })
        }
        put("handled", JSONObject().apply { d.handled.forEach { (k, v) -> put(k, v) } })
        d.brightnessBefore?.let { put("brightnessBefore", it.toDouble()) }
    }.toString()

    fun alarmsFromJson(s: String): AlarmData {
        val o = JSONObject(s)
        val alarms = o.optJSONArray("alarms")?.let { arr -> (0 until arr.length()).mapNotNull { alarmFromJson(arr.optJSONObject(it)) } } ?: emptyList()
        val active = o.optJSONObject("active")?.let { a ->
            val state = runCatching { ActiveAlarm.State.valueOf(a.getString("state")) }.getOrNull() ?: return@let null
            ActiveAlarm(a.getString("alarmId"), state, a.optLong("since"), a.optLong("until"), a.optLong("occurrence"), a.optInt("autoSnoozes"))
        }
        val handled = o.optJSONObject("handled")?.let { h -> h.keys().asSequence().associateWith { h.getLong(it) } } ?: emptyMap()
        val bb = if (o.has("brightnessBefore")) o.getDouble("brightnessBefore").toFloat() else null
        return AlarmData(alarms, active, handled, bb)
    }

    fun alarmToJson(a: Alarm) = JSONObject().apply {
        put("id", a.id); put("hour", a.hour); put("minute", a.minute)
        put("days", JSONArray().apply { a.days.sorted().forEach { put(it.value) } })
        put("label", a.label); put("enabled", a.enabled); put("sunrise", a.sunrise)
        put("sunriseMinutes", a.sunriseMinutes); put("snoozeMinutes", a.snoozeMinutes)
        a.profileId?.let { put("profileId", it) }
    }

    fun alarmFromJson(o: JSONObject?): Alarm? {
        o ?: return null
        val id = o.optString("id").ifEmpty { return null }
        val days = o.optJSONArray("days")?.let { arr -> (0 until arr.length()).mapNotNull { i -> arr.optInt(i).takeIf { it in 1..7 }?.let(DayOfWeek::of) }.toSet() } ?: emptySet()
        return Alarm(
            id = id, hour = o.optInt("hour", 7), minute = o.optInt("minute", 0), days = days,
            label = o.optString("label", "Alarm"), enabled = o.optBoolean("enabled", true),
            sunrise = o.optBoolean("sunrise", true), sunriseMinutes = o.optInt("sunriseMinutes", 30),
            snoozeMinutes = o.optInt("snoozeMinutes", 9).coerceAtLeast(1),
            profileId = if (o.has("profileId")) o.getString("profileId") else null,
        )
    }

    fun timersToJson(d: TimerData): String = JSONObject().apply {
        put("v", 1)
        put("bootCount", d.bootCount)
        put("savedElapsed", d.savedElapsed)
        put("savedWall", d.savedWall)
        put("timers", JSONArray().apply {
            d.timers.forEach { t ->
                put(JSONObject().apply {
                    put("id", t.id); put("label", t.label); put("durationMs", t.durationMs)
                    put("endsAtElapsed", t.endsAtElapsed); put("state", t.state.name); put("pausedRemainingMs", t.pausedRemainingMs)
                })
            }
        })
        put("silenced", JSONArray().apply { d.silenced.forEach { put(it) } })
    }.toString()

    fun timersFromJson(s: String): TimerData {
        val o = JSONObject(s)
        val arr = o.optJSONArray("timers") ?: JSONArray()
        val timers = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val state = runCatching { EchoTimer.State.valueOf(t.getString("state")) }.getOrNull() ?: return@mapNotNull null
            EchoTimer(t.getString("id"), t.optString("label"), t.optLong("durationMs"), t.optLong("endsAtElapsed"), state, t.optLong("pausedRemainingMs"))
        }
        val sil = o.optJSONArray("silenced")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return TimerData(timers, o.optInt("bootCount", -1), o.optLong("savedElapsed"), o.optLong("savedWall"), sil)
    }
}
