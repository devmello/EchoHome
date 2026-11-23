package app.vesper.feature.alarm.engine

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Turns the calendar and travel times into what the [AlarmEngine] needs: leave-by shifts and
 * day-off dates. The calendar holds today and tomorrow, so that's the planning window. Free of
 * Android (the travel lookup is a parameter), so it's unit-tested.
 */
object LeavePlanner {
    /** The leave-by shifts for [now]'s window, starting from [old] (which keeps what it can't improve on). */
    suspend fun shifts(
        alarms: List<Alarm>,
        events: List<CalendarEvent>,
        old: Map<String, LeaveShift>,
        now: Long,
        zone: ZoneId,
        /** False until the calendar has delivered something: an empty list then proves nothing. */
        calendarKnown: Boolean,
        travel: suspend (location: String, departAtMillis: Long) -> Int?,
    ): Map<String, LeaveShift> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // Dates before yesterday are history.
        val out = old.filterKeys { k -> k.substringAfterLast('|').let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { !it.isBefore(today.minusDays(1)) } == true }.toMutableMap()
        for (alarm in alarms) {
            if (!alarm.enabled || !alarm.leaveBy) continue
            for (date in listOf(today, today.plusDays(1))) {
                val key = LeaveBy.key(alarm.id, date)
                val base = baseMillis(alarm, date, zone)
                if (base <= now) continue // already past: what's planned stays as it was
                val onDay = alarm.days.isEmpty() || date.dayOfWeek in alarm.days
                val skipped = alarm.skipHolidays && alarm.days.isNotEmpty() && Holidays.federalName(date) != null
                if (!onDay || skipped) { out.remove(key); continue }
                val oldShift = out[key]
                val event = LeaveBy.eventFor(alarm, date, events, zone)
                if (event == null) {
                    // The event is gone: back to the set time, unless that morning's alarm already rang.
                    if (calendarKnown && oldShift != null && triggerOf(base, oldShift) > now) out.remove(key)
                    continue
                }
                val minutes = travel(event.location.orEmpty(), event.startMillis - LeaveBy.TRAVEL_LEAD_MS)
                val shift = if (minutes == null) oldShift?.takeIf { it.eventStart == event.startMillis } // a stale estimate beats none
                else LeaveBy.plan(alarm, date, event, minutes, zone)
                when {
                    shift == null -> if (oldShift != null && triggerOf(base, oldShift) > now) out.remove(key)
                    triggerOf(base, shift) > now -> out[key] = shift
                    // The new time has passed already (the plan changed late): ring at the old one, once.
                    else -> {}
                }
            }
        }
        return out
    }

    /** The day-off dates: [old] ones outside the window stay, today's and tomorrow's follow [events]. */
    fun dayOffs(old: Map<String, String>, events: List<CalendarEvent>, now: Long, zone: ZoneId, calendarKnown: Boolean): Map<String, String> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val out = old.filterKeys { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(today.minusDays(1)) } == true }.toMutableMap()
        if (!calendarKnown) return out
        listOf(today, today.plusDays(1)).forEach { out.remove(it.toString()) }
        Holidays.dayOffDays(events, zone).forEach { (d, title) -> if (!d.isBefore(today.minusDays(1))) out[d.toString()] = title }
        return out
    }

    private fun baseMillis(alarm: Alarm, date: LocalDate, zone: ZoneId) =
        ZonedDateTime.of(date, LocalTime.of(alarm.hour.coerceIn(0, 23), alarm.minute.coerceIn(0, 59)), zone).toInstant().toEpochMilli()

    private fun triggerOf(base: Long, s: LeaveShift) = base - s.earlierMin * 60_000L

    /** When the planner should run again for [alarms]: 3 h before the next set time (the travel estimate is fresh then). */
    fun nextRefreshAt(alarms: List<Alarm>, now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5 * 60_000L
        var best = midnight
        for (a in alarms) {
            if (!a.enabled || !a.leaveBy) continue
            for (date in listOf(today, today.plusDays(1))) {
                val t = baseMillis(a, date, zone) - REFRESH_LEAD_MS
                if (t > now && t < best) best = t
            }
        }
        return best
    }

    const val REFRESH_LEAD_MS = 3 * 60 * 60_000L
}
