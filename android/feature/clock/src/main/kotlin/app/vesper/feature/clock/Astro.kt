package app.vesper.feature.clock

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The moon's phase for a moment in time, computed locally (Meeus, "Astronomical Algorithms" ch. 48:
 * elongation corrected by the main solar / lunar anomaly terms; the phase angle is good to well under
 * a degree, so the lit fraction to about a percent and the phase moments to an hour or two).
 */
object MoonMath {
    class Phase(
        /** 0 (new) … 1 (full). */
        val illumination: Double,
        /** Growing towards full (lit on the right in the northern hemisphere). */
        val waxing: Boolean,
        /** Mean elongation, degrees 0..360 (0 = new, 180 = full). */
        val elongation: Double,
    ) {
        val percent: Int get() = (illumination * 100 + 0.5).toInt()

        /** New / Waxing crescent / First quarter / Waxing gibbous / Full / Waning gibbous / Last quarter / Waning crescent. */
        val name: String get() = nameFor(elongation)
    }

    private const val J2000 = 946_728_000_000L // 2000-01-01 12:00 UTC (JD 2451545.0) in epoch millis

    private fun rad(d: Double) = d * PI / 180
    private fun norm(d: Double): Double = ((d % 360) + 360) % 360

    fun phase(utcMillis: Long): Phase {
        val t = (utcMillis - J2000) / 86_400_000.0 / 36525.0
        val d = norm(297.8501921 + 445267.1114034 * t)
        val m = norm(357.5291092 + 35999.0502909 * t)
        val mp = norm(134.9633964 + 477198.8675055 * t)
        // phase angle i (0 = full, 180 = new)
        val i = 180 - d - 6.289 * sin(rad(mp)) + 2.100 * sin(rad(m)) - 1.274 * sin(rad(2 * d - mp)) -
            0.658 * sin(rad(2 * d)) - 0.214 * sin(rad(2 * mp)) - 0.110 * sin(rad(d))
        val k = (1 + cos(rad(i))) / 2
        // elongation of the moon from the sun, in the same corrected terms
        val elong = norm(180 - i)
        return Phase(k.coerceIn(0.0, 1.0), waxing = elong < 180.0, elongation = elong)
    }

    fun nameFor(elongation: Double): String = when {
        elongation < 6.0 || elongation >= 354.0 -> "New moon"
        elongation < 84.0 -> "Waxing crescent"
        elongation < 96.0 -> "First quarter"
        elongation < 174.0 -> "Waxing gibbous"
        elongation < 186.0 -> "Full moon"
        elongation < 264.0 -> "Waning gibbous"
        elongation < 276.0 -> "Last quarter"
        else -> "Waning crescent"
    }
}

/** The pair of sun times shown at night. */
object SunTimes {
    /**
     * Sunrise and sunset to show at [now]: today's pair until sunset, tomorrow's after it.
     * [reported] is the weather source's pair for today, used when present (otherwise computed
     * with [SunMath.sunriseSunset], the NOAA-style algorithm, for lat/lon).
     * Null in polar day / night.
     */
    fun next(now: Long, lat: Double, lon: Double, zone: TimeZone, reported: Pair<Long, Long>?): Pair<Long, Long>? {
        val today = reported ?: SunMath.sunriseSunset(now, lat, lon, zone)
        if (today != null && now < today.second) return today
        return SunMath.sunriseSunset(now + 86_400_000L, lat, lon, zone)
    }
}

/** Alarm triggers and a person's next event, for the StandBy personal line. */
object Personal {
    /** The next time [a] rings after [now] (local zone), or null when it's off. Ignores leave-by / holiday skips. */
    fun nextTrigger(a: Alarm, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (!a.enabled) return null
        val c = Calendar.getInstance(zone)
        for (add in 0..7) {
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, add)
            c.set(Calendar.HOUR_OF_DAY, a.hour); c.set(Calendar.MINUTE, a.minute)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now) continue
            if (a.days.isNotEmpty()) {
                // java.time.DayOfWeek: MONDAY = 1 … SUNDAY = 7; Calendar: SUNDAY = 1 … SATURDAY = 7
                val dow = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
                if (a.days.none { it.value == dow }) continue
            }
            return c.timeInMillis
        }
        return null
    }

    /**
     * [personId]'s next alarm: their own alarms, or the shared ones (profileId null) when they have none.
     * [next] is the store's own next alarm; when it is one of the candidates its trigger is used (it knows
     * about leave-by and holidays).
     */
    fun alarmFor(personId: String, alarms: List<Alarm>, next: app.vesper.core.model.NextAlarm?, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        val own = alarms.filter { it.enabled && it.profileId == personId }
        val pool = own.ifEmpty { alarms.filter { it.enabled && it.profileId == null } }
        // the store's own next wins when it is theirs (or shared, if they have none of their own)
        if (next != null && next.triggerAtMillis > now &&
            (next.alarm.profileId == personId || (own.isEmpty() && next.alarm.profileId == null))) return next.triggerAtMillis
        if (pool.isEmpty()) return null
        return pool.mapNotNull { nextTrigger(it, now, zone) }.minOrNull()
    }

    /** [personId]'s next (or current) timed event: from their calendar, else the shared one. */
    fun eventFor(personId: String, events: List<CalendarEvent>, now: Long): CalendarEvent? {
        val upcoming = events.filter { !it.allDay && it.endMillis > now }.sortedBy { it.startMillis }
        return upcoming.firstOrNull { it.profileId == personId } ?: upcoming.firstOrNull { it.profileId == null }
    }

    /** True when [millis] is on a later local day than [now]. */
    fun isTomorrowOrLater(millis: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val a = Calendar.getInstance(zone).apply { timeInMillis = now }
        val b = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return b.get(Calendar.YEAR) * 400 + b.get(Calendar.DAY_OF_YEAR) > a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR)
    }
}
