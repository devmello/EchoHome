package com.echobedside.feature.home.commute

import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.HaEntity
import com.echobedside.core.model.HourForecast
import com.echobedside.core.model.WeatherCondition
import com.echobedside.feature.home.ha.forEachObject
import com.echobedside.feature.home.ha.optDoubleOrNull
import com.echobedside.feature.home.ha.optStringOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt
import org.json.JSONObject

/** One route option. [minutes] includes traffic. */
data class Route(val name: String, val minutes: Int, val distance: Double? = null)

/** A travel-time sensor's reading (Waze travel time or Google Maps travel time). */
data class Travel(val minutes: Int, val freeFlowMinutes: Int?, val route: String?, val origin: String?, val destination: String?)

/** Where we're going and by when. */
data class Trip(val label: String, val arriveBy: Long, val destination: String?, val event: CalendarEvent?)

/** Everything Commute.dc.html draws. */
data class CommuteUi(
    /** "TO WORK" */
    val header: String,
    /** Minutes until you should leave; ≤ 0 means now. */
    val leaveInMin: Int,
    val driveMin: Int,
    /** "Hwy 17" */
    val via: String?,
    val arriveMillis: Long,
    /** "Slow near Scotts Valley · +6 min" — null when traffic is normal. */
    val trafficChip: String?,
    /** "Rain from 8 AM" */
    val rainChip: String?,
    val alternatives: List<Alternative>,
    /** 0..1 share of the drawn route shown as slow (orange). */
    val slowShare: Float,
    val demo: Boolean = false,
) {
    /** "Leave in\n18 min" / "Leave\nnow" / "Leave at\n7:42 AM" (more than 2 h out). */
    fun headline(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): Pair<String, String> = when {
        leaveInMin <= 0 -> "Leave" to "now"
        leaveInMin > 120 -> "Leave at" to com.echobedside.feature.home.glance.clock12(now + leaveInMin * 60_000L, zone)
        leaveInMin >= 60 -> "Leave in" to "${leaveInMin / 60} h ${leaveInMin % 60} min"
        else -> "Leave in" to "$leaveInMin min"
    }
}

data class Alternative(val label: String, val minutes: Int)

/** Pure commute maths (unit-tested). */
object CommuteMath {
    /** Whole minutes until leaving so you arrive [bufferMin] early. Floors, so "Leave in 0" comes on time. */
    fun leaveInMin(now: Long, arriveBy: Long, driveMin: Int, bufferMin: Int): Int =
        floor((arriveBy - bufferMin * 60_000L - driveMin * 60_000L - now) / 60_000.0).toInt()

    /** Arrival if you leave in [leaveInMin] (or now, if that's already past). */
    fun arrival(now: Long, leaveInMin: Int, driveMin: Int): Long = now + (leaveInMin.coerceAtLeast(0) + driveMin) * 60_000L

    /** "24 mins", "1 hour 5 mins", "24.4" → minutes. */
    fun parseMinutes(v: Any?): Int? = when (v) {
        null -> null
        is Number -> v.toDouble().roundToInt()
        else -> {
            val s = v.toString()
            s.toDoubleOrNull()?.roundToInt() ?: run {
                val h = Regex("(\\d+)\\s*(h|hour|hours|hr)").find(s)?.groupValues?.get(1)?.toInt() ?: 0
                val m = Regex("(\\d+)\\s*(m|min|mins|minutes)\\b").find(s)?.groupValues?.get(1)?.toInt()
                if (h == 0 && m == null) null else h * 60 + (m ?: 0)
            }
        }
    }

    /**
     * Waze: state = minutes, attributes duration / distance / route / origin / destination.
     * Google: state = minutes in traffic, attributes duration (free-flow) and duration_in_traffic ("26 mins").
     */
    fun travel(e: HaEntity): Travel? {
        val a = e.attributes
        val inTraffic = parseMinutes(a.opt("duration_in_traffic")) ?: parseMinutes(e.state) ?: parseMinutes(a.opt("duration")) ?: return null
        val freeFlow = if (a.has("duration_in_traffic")) parseMinutes(a.opt("duration")) else null
        return Travel(inTraffic, freeFlow, a.optStringOrNull("route"), a.optStringOrNull("origin"), a.optStringOrNull("destination"))
    }

    /** `waze_travel_time.get_travel_times` response: `{"routes": [{"name","duration","distance","street_names"}]}`. */
    fun wazeRoutes(response: JSONObject): List<Route> {
        val out = ArrayList<Route>()
        response.optJSONArray("routes")?.forEachObject { r ->
            val min = r.optDoubleOrNull("duration")?.roundToInt() ?: return@forEachObject
            out += Route(r.optStringOrNull("name") ?: "Route ${out.size + 1}", min, r.optDoubleOrNull("distance"))
        }
        return out.sortedBy { it.minutes }
    }

    /** The next timed event with a location that starts within [hours] (and hasn't started). */
    fun nextEventTrip(events: List<CalendarEvent>, now: Long, hours: Int): Trip? {
        if (hours <= 0) return null
        val e = events.filter { !it.allDay && !it.location.isNullOrBlank() && it.startMillis > now && it.startMillis - now <= hours * 3_600_000L }
            .minByOrNull { it.startMillis } ?: return null
        return Trip("TO " + e.title.uppercase(), e.startMillis, e.location, e)
    }

    /** The default commute: weekdays, arrive by [arriveBy] ("09:00"). Today if it's still ahead, else the next weekday. */
    fun defaultTrip(label: String, arriveBy: String?, now: Long, zone: ZoneId): Trip? {
        val t = arriveBy?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(8) {
            val at = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
            if (at > now && day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY)
                return Trip("TO " + label.uppercase(), at, null, null)
            day = day.plusDays(1)
        }
        return null
    }

    /** Extra minutes over free-flow; uses the learned [baseline] when the sensor has none. */
    fun delayMin(t: Travel, baseline: Int?): Int = ((t.freeFlowMinutes ?: baseline)?.let { t.minutes - it } ?: 0).coerceAtLeast(0)

    fun trafficChip(delay: Int, via: String?): String? =
        if (delay < 3) null else if (via != null) "Slow on $via · +$delay min" else "Heavier traffic · +$delay min"

    /** First rainy hour in the next 12 h → "Rain from 8 AM" (or "Rain now"). */
    fun rainChip(hourly: List<HourForecast>, now: Long, zone: ZoneId): String? {
        val wet = setOf(WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING, WeatherCondition.SLEET, WeatherCondition.SNOW, WeatherCondition.HAIL)
        val h = hourly.filter { it.timeMillis in (now - 3_600_000L)..(now + 12 * 3_600_000L) }
            .firstOrNull { it.precipChance >= 50 || it.condition in wet } ?: return null
        val kind = if (h.condition == WeatherCondition.SNOW) "Snow" else "Rain"
        if (h.timeMillis <= now) return "$kind now"
        val hr = Instant.ofEpochMilli(h.timeMillis).atZone(zone).hour
        return "$kind from ${if (hr % 12 == 0) 12 else hr % 12} ${if (hr < 12) "AM" else "PM"}"
    }

    fun build(
        trip: Trip,
        main: Route,
        alternatives: List<Route>,
        delay: Int,
        hourly: List<HourForecast>,
        bufferMin: Int,
        now: Long,
        zone: ZoneId,
    ): CommuteUi {
        val leave = leaveInMin(now, trip.arriveBy, main.minutes, bufferMin)
        return CommuteUi(
            header = trip.label,
            leaveInMin = leave,
            driveMin = main.minutes,
            via = main.name.takeIf { it.isNotBlank() },
            arriveMillis = arrival(now, leave, main.minutes),
            trafficChip = trafficChip(delay, main.name.takeIf { it.isNotBlank() }),
            rainChip = rainChip(hourly, now, zone),
            alternatives = alternatives.filter { it.name != main.name }.take(2).map { Alternative("VIA " + it.name.uppercase(), it.minutes) },
            slowShare = if (delay < 3) 0f else (delay.toFloat() / main.minutes).coerceIn(0.08f, 0.5f),
        )
    }

    /** Commute.dc.html sample: leave in 18, 24 min via Hwy 17, slow near Scotts Valley +6, rain from 8 AM. */
    fun demo(now: Long): CommuteUi = CommuteUi(
        header = "TO WORK", leaveInMin = 18, driveMin = 24, via = "Hwy 17", arriveMillis = now + 42 * 60_000L,
        trafficChip = "Slow near Scotts Valley · +6 min", rainChip = "Rain from 8 AM",
        alternatives = listOf(Alternative("VIA SOQUEL DR", 31), Alternative("BUS 35 · 8:20 AM", 41)),
        slowShare = 0.2f, demo = true,
    )
}
