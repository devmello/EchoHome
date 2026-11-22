package com.echobedside.feature.home.glance

import com.echobedside.core.model.CalendarEvent
import com.echobedside.feature.home.ha.forEachObject
import com.echobedside.feature.home.ha.optStringOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** `calendar.get_events` → [CalendarEvent]s. Pure, for unit tests. */
object CalendarParse {
    /** Calendar.dc.html event colours, assigned to calendars in order. */
    val PALETTE = intArrayOf(0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFFFF9F0A.toInt(), 0xFFBF5AF2.toInt(),
        0xFFFF375F.toInt(), 0xFF64D2FF.toInt(), 0xFFFFD60A.toInt())

    /** Today 00:00 → the day after tomorrow 00:00 (device zone), as `calendar.get_events` data. */
    fun range(now: Long, zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val today = localDate(now, zone)
        val f = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        return JSONObject()
            .put("start_date_time", today.atStartOfDay(zone).format(f))
            .put("end_date_time", today.plusDays(2).atStartOfDay(zone).format(f))
    }

    /**
     * Response: `{"calendar.family": {"events": [{"start","end","summary","description","location"}]}}`.
     * Timed events carry an offset; all-day ones are bare dates (end exclusive).
     * @param colors colour per calendar entity id
     * @param names friendly names, used as the subtitle when an event has no location
     */
    fun events(
        response: JSONObject,
        colors: Map<String, Int>,
        names: Map<String, String> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarEvent> {
        val out = ArrayList<CalendarEvent>()
        for (cal in response.keys()) {
            val arr = response.optJSONObject(cal)?.optJSONArray("events") ?: continue
            arr.forEachObject { e ->
                val startS = e.optString("start"); val endS = e.optString("end")
                val allDay = startS.length == 10
                val start = WeatherParse.parseTime(startS, zone) ?: return@forEachObject
                val end = WeatherParse.parseTime(endS, zone) ?: start
                val title = e.optStringOrNull("summary") ?: "Busy"
                val location = e.optStringOrNull("location")
                val subtitle = location?.substringBefore(',')?.trim()
                    ?: e.optStringOrNull("description")?.lineSequence()?.firstOrNull()?.trim()?.take(60)
                    ?: names[cal].orEmpty()
                out += CalendarEvent(
                    id = "$cal|$startS|$title",
                    title = title,
                    subtitle = subtitle,
                    startMillis = start,
                    endMillis = end,
                    allDay = allDay,
                    color = colors[cal] ?: PALETTE[0],
                    location = location,
                )
            }
        }
        // All-day events first within a day, then by start.
        return out.sortedWith(compareBy({ localDate(it.startMillis, zone) }, { !it.allDay }, { it.startMillis }, { it.title }))
    }

    fun parseColor(s: String?): Int? = s?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }

    /** Is [day] today or tomorrow relative to [now]? */
    fun inWindow(day: LocalDate, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val today = localDate(now, zone)
        return day == today || day == today.plusDays(1)
    }
}
