package com.echobedside.feature.settings.api

import com.echobedside.core.model.Alarm
import com.echobedside.core.model.AlarmStore
import com.echobedside.core.model.NextAlarm
import com.echobedside.feature.settings.http.HttpRequest
import com.echobedside.feature.settings.http.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek
import java.util.UUID

/**
 * The alarm list over [AlarmStore] (services.alarms, `:feature:alarm`), serialised here so no
 * `:core` change is needed:
 *  - `GET /api/alarms` → `{alarms: [...], next: {id, at} | null}`
 *  - `POST /api/alarms` `{time, days, label, enabled, sunrise}` → the new alarm
 *  - `PUT /api/alarms/<id>` with any of those fields → the updated alarm
 *  - `DELETE /api/alarms/<id>`
 * The sound is global (setting `alarm.sound`, on the schema), not per alarm. Every store call
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

        fun toJson(a: Alarm): JSONObject = JSONObject()
            .put("id", a.id)
            .put("time", "%02d:%02d".format(a.hour, a.minute))
            .put("days", JSONArray(DayOfWeek.values().filter { it in a.days }.map { it.name.take(3) }))
            .put("label", a.label)
            .put("enabled", a.enabled)
            .put("sunrise", a.sunrise)
            .putOpt("profileId", a.profileId)

        fun listJson(list: List<Alarm>, next: NextAlarm?): JSONObject = JSONObject()
            .put("alarms", JSONArray().apply { for (a in list.sortedBy { it.hour * 60 + it.minute }) put(toJson(a)) })
            .put("next", next?.let { JSONObject().put("id", it.alarm.id).put("at", it.triggerAtMillis) } ?: JSONObject.NULL)

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
                    "enabled" -> a.copy(enabled = v as? Boolean ?: throw ValidationException("On/off: expected true or false"))
                    "sunrise" -> a.copy(sunrise = v as? Boolean ?: throw ValidationException("Sunrise: expected true or false"))
                    "id" -> a // ignored: the path / a new UUID decides
                    else -> throw ValidationException("Unknown alarm field: ${k.take(40)}")
                }
            }
            return a
        }

        private fun day(v: Any?): DayOfWeek {
            val s = (v as? String)?.uppercase() ?: throw ValidationException("Repeat: days are MON…SUN")
            return DayOfWeek.values().firstOrNull { it.name.startsWith(s) && s.length >= 3 } ?: throw ValidationException("Repeat: unknown day $s")
        }
    }
}
