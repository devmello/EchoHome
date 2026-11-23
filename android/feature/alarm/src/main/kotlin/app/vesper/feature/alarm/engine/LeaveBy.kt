package app.vesper.feature.alarm.engine

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A leave-by alarm that rings earlier on one date: [earlierMin] minutes before the set time,
 * because of [eventTitle] at [eventStart] with [travelMin] minutes of travel. Persisted, so the
 * earlier time survives a process restart (the engine would otherwise ring at the set time).
 */
data class LeaveShift(val earlierMin: Int, val eventTitle: String, val eventStart: Long, val travelMin: Int)

/** Pure planning for leave-by alarms. */
object LeaveBy {
    /** The alarm never moves more than this much earlier. */
    const val MAX_EARLIER_MIN = 120
    /** Travel time is estimated for leaving about this long before the event. */
    const val TRAVEL_LEAD_MS = 60 * 60_000L
    val READY_CHOICES = listOf(15, 30, 45, 60, 90)

    fun key(alarmId: String, date: LocalDate) = "$alarmId|$date"

    /** The event [alarm] plans around on [date]: the first timed event with a location. */
    fun eventFor(alarm: Alarm, date: LocalDate, events: List<CalendarEvent>, zone: ZoneId): CalendarEvent? =
        events.filter { e ->
            !e.allDay && !e.location.isNullOrBlank() &&
                Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalDate() == date &&
                (alarm.profileId == null || e.profileId == null || e.profileId == alarm.profileId)
        }.minByOrNull { it.startMillis }

    /**
     * min(set time, start − travel − ready), at most [MAX_EARLIER_MIN] earlier than the set time.
     * Null when the travel time is unknown or the set time already leaves enough time.
     */
    fun plan(alarm: Alarm, date: LocalDate, event: CalendarEvent, travelMin: Int?, zone: ZoneId): LeaveShift? {
        travelMin ?: return null
        val travel = travelMin.coerceAtLeast(0)
        val base = ZonedDateTime.of(date, LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59)), zone).toInstant().toEpochMilli()
        val wanted = event.startMillis - (travel + alarm.readyMinutes.coerceAtLeast(0)) * 60_000L
        val earlierMs = base - wanted
        if (earlierMs <= 0) return null
        val earlier = ((earlierMs + 59_999) / 60_000).toInt().coerceIn(1, MAX_EARLIER_MIN)
        return LeaveShift(earlier, event.title.trim(), event.startMillis, travel)
    }

    /** "Earlier for Dentist 9:00 · 25 min drive". */
    fun reason(s: LeaveShift, timeText: (Long) -> String): String {
        val title = if (s.eventTitle.length > 24) s.eventTitle.take(23).trimEnd() + "…" else s.eventTitle
        return "Earlier for $title ${timeText(s.eventStart)} · ${s.travelMin} min drive"
    }
}
