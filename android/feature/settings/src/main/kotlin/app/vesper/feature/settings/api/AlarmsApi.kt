package app.vesper.feature.settings.api

import app.vesper.core.model.Alarm
import app.vesper.core.model.AlarmJson
import app.vesper.core.model.AlarmStore
import app.vesper.core.model.NextAlarm
import app.vesper.feature.settings.http.HttpRequest
import app.vesper.feature.settings.http.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.util.UUID

/**
 * The alarm list over [AlarmStore] (services.alarms, `:feature:alarm`), serialised here so no
 * `:core` change is needed:
 *  - `GET /api/alarms` → `{alarms: [...], next: {id, at} | null}`
 *  - `POST /api/alarms` `{time | hour+minute, days: [1..7], label, enabled, sunrise}` → the new alarm
 *  - `PUT /api/alarms/<id>` with any of those fields → the updated alarm
 *  - `DELETE /api/alarms/<id>`
 * Leave-by (`leaveBy`, `readyMinutes`) and `skipHolidays` are edited here too; `next.reason` says why it
 * rings at a different time than set. The sound is global (setting `alarm.sound`, on the schema), not per alarm. Every store call
 * runs on the main thread ([onMain]), as RealAlarmStore requires.
 */
class AlarmsApi(
    private val store: () -> AlarmStore,
    private val onMain: (() -> Any?) -> Any?,
) {
    fun handle(req: HttpRequest): HttpResponse {
        val rest = req.path.removePrefix("/api/alarms").removePrefix("/")
        return try {
            when {
                rest.isEmpty() && req.method == "GET" -> ok(listJson())
                rest.isEmpty() && req.method == "POST" -> {
                    val a = parse(body(req), null)
                    onMain { store().upsert(a) }
                    ok(JSONObject().put("ok", true).put("alarm", toJson(a)))
                }
                rest.isNotEmpty() && req.method == "PUT" -> {
                    val old = find(rest) ?: return HttpResponse.error(404, "No such alarm")
                    val a = parse(body(req), old)
                    onMain {
                        // Only the switch flipped: setEnabled keeps the engine's own bookkeeping.
                        if (a == old.copy(enabled = a.enabled)) store().setEnabled(a.id, a.enabled) else store().upsert(a)
                    }
                    ok(JSONObject().put("ok", true).put("alarm", toJson(find(rest) ?: a)))
                }
                rest.isNotEmpty() && req.method == "DELETE" -> {
                    find(rest) ?: return HttpResponse.error(404, "No such alarm")
                    onMain { store().delete(rest) }
                    ok(JSONObject().put("ok", true))
                }
                else -> HttpResponse.error(405, "Not allowed")
            }
        } catch (e: ValidationException) {
            HttpResponse.error(422, e.message ?: "Invalid alarm")
        }
    }

    fun listJson(): JSONObject {
        val (list, next) = onMain { store().alarms.value to store().next.value } as Pair<*, *>
        @Suppress("UNCHECKED_CAST")
        return listJson(list as List<Alarm>, next as NextAlarm?)
    }

    private fun find(id: String): Alarm? = (onMain { store().alarms.value } as List<*>).filterIsInstance<Alarm>().firstOrNull { it.id == id }

    private fun body(req: HttpRequest): JSONObject = try { JSONObject(req.bodyText()) } catch (e: JSONException) {
        throw ValidationException("Expected a JSON object")
    }

    private fun ok(o: JSONObject) = HttpResponse.json(200, o.toString())

    companion object {
        private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")
        const val MAX_LABEL = 40

        /** [AlarmJson] (days are ISO numbers, Monday = 1) plus "time" ("HH:mm") for the page. */
        fun toJson(a: Alarm): JSONObject = AlarmJson.toJson(a).put("time", "%02d:%02d".format(a.hour, a.minute))
            .put("leaveBy", a.leaveBy).put("readyMinutes", a.readyMinutes).put("skipHolidays", a.skipHolidays)

        fun listJson(list: List<Alarm>, next: NextAlarm?): JSONObject = JSONObject()
            .put("alarms", JSONArray().apply { for (a in list.sortedBy { it.hour * 60 + it.minute }) put(toJson(a)) })
            .put("next", next?.let { JSONObject().put("id", it.alarm.id).put("at", it.triggerAtMillis).putOpt("reason", it.reason) } ?: JSONObject.NULL)

        /** Validates [o] onto [base] (null = a new alarm with the model's defaults). */
        fun parse(o: JSONObject, base: Alarm?): Alarm {
            var a = base ?: Alarm(id = UUID.randomUUID().toString(), hour = 7, minute = 0)
            for (k in o.keys().asSequence().toList()) {
                val v = o.opt(k)
                a = when (k) {
                    "time" -> {
                        val m = (v as? String)?.let { TIME.matchEntire(it.trim()) } ?: throw ValidationException("Time: expected HH:mm")
                        a.copy(hour = m.groupValues[1].toInt(), minute = m.groupValues[2].toInt())
                    }
                    "days" -> {
                        val arr = v as? JSONArray ?: throw ValidationException("Repeat: expected a list of days")
                        a.copy(days = (0 until arr.length()).map { i -> day(arr.opt(i)) }.toSet())
                    }
                    "label" -> {
                        val s = (v as? String)?.trim() ?: throw ValidationException("Label: expected text")
                        if (s.length > MAX_LABEL || s.any { it < ' ' }) throw ValidationException("Label: one line, at most $MAX_LABEL characters")
                        a.copy(label = s.ifEmpty { "Alarm" })
                    }
                    "hour" -> a.copy(hour = int(v, 0..23, "Hour"))
                    "minute" -> a.copy(minute = int(v, 0..59, "Minute"))
                    "sunriseMinutes" -> a.copy(sunriseMinutes = int(v, 0..120, "Sunrise minutes"))
                    "snoozeMinutes" -> a.copy(snoozeMinutes = int(v, 1..60, "Snooze minutes"))
                    "readyMinutes" -> a.copy(readyMinutes = int(v, 0..180, "Getting-ready minutes"))
                    "leaveBy" -> a.copy(leaveBy = v as? Boolean ?: throw ValidationException("Leave by: expected true or false"))
                    "skipHolidays" -> a.copy(skipHolidays = v as? Boolean ?: throw ValidationException("Skip holidays: expected true or false"))
                    "enabled" -> a.copy(enabled = v as? Boolean ?: throw ValidationException("On/off: expected true or false"))
                    "sunrise" -> a.copy(sunrise = v as? Boolean ?: throw ValidationException("Sunrise: expected true or false"))
                    "id" -> a // ignored: the path / a new UUID decides
                    else -> throw ValidationException("Unknown alarm field: ${k.take(40)}")
                }
            }
            return a
        }

        private fun int(v: Any?, range: IntRange, what: String): Int {
            val n = (v as? Number)?.toDouble() ?: throw ValidationException("$what: expected a number")
            if (n != Math.rint(n) || n.toInt() !in range) throw ValidationException("$what: ${range.first}–${range.last}")
            return n.toInt()
        }

        /** 1 (Monday) … 7 (Sunday), as in [AlarmJson], or "MON" … "SUN". */
        private fun day(v: Any?): DayOfWeek {
            if (v is Number) return int(v, 1..7, "Repeat").let(DayOfWeek::of)
            val s = (v as? String)?.uppercase() ?: throw ValidationException("Repeat: days are 1–7 (Monday = 1)")
            return DayOfWeek.values().firstOrNull { it.name.startsWith(s) && s.length >= 3 } ?: throw ValidationException("Repeat: unknown day $s")
        }
    }
}
