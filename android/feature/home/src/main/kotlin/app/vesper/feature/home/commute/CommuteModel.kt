package app.vesper.feature.home.commute

import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.HourForecast
import app.vesper.core.model.WeatherCondition
import app.vesper.feature.home.glance.clock12
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/** A lat/lon. */
data class LatLon(val lat: Double, val lon: Double)

/** One drivable route. [path] is the simplified geometry (lat/lon), [via] the main road ("CA 17"). */
data class Route(val minutes: Int, val km: Double, val via: String?, val path: List<LatLon> = emptyList())

/** Where we're going and by when. [destination] is the place text to geocode. */
data class Trip(val label: String, val arriveBy: Long, val destination: String, val event: CalendarEvent?)

data class Alternative(val label: String, val minutes: Int)

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
    /** "Slow near Scotts Valley · +6 min": only the demo has one; no free keyless source gives live traffic. */
    val trafficChip: String?,
    /** "Rain from 8 AM" */
    val rainChip: String?,
    val alternatives: List<Alternative>,
    /** Route shape in 0..1 map space (x right, y down), or null to draw the design's stylised route. */
    val route: List<Pair<Float, Float>>?,
    /** The first alternative's shape, same space. */
    val altRoute: List<Pair<Float, Float>>?,
    val demo: Boolean = false,
) {
    /** ("Leave in", "18 min") / ("Leave", "now") / ("Leave at", "7:42 AM") when more than 2 h out. */
    fun headline(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> = when {
        leaveInMin <= 0 -> "Leave" to "now"
        leaveInMin > 120 -> "Leave at" to clock12(now + leaveInMin * 60_000L, zone)
        leaveInMin >= 60 -> "Leave in" to "${leaveInMin / 60} h ${leaveInMin % 60} min"
        else -> "Leave in" to "$leaveInMin min"
    }
}

/** Pure commute maths (unit-tested). */
object CommuteMath {
    /** Whole minutes until leaving so you arrive [bufferMin] early. Floors, so it never says you have longer than you do. */
    fun leaveInMin(now: Long, arriveBy: Long, driveMin: Int, bufferMin: Int): Int =
        floor((arriveBy - bufferMin * 60_000L - driveMin * 60_000L - now) / 60_000.0).toInt()

    /** Arrival if you leave in [leaveInMin] (or now, if that's already past). */
    fun arrival(now: Long, leaveInMin: Int, driveMin: Int): Long = now + (leaveInMin.coerceAtLeast(0) + driveMin) * 60_000L

    /** The next timed event with a location that starts within [hours] and hasn't started. */
    fun nextEventTrip(events: List<CalendarEvent>, now: Long, hours: Int): Trip? {
        if (hours <= 0) return null
        val e = events.filter { !it.allDay && !it.location.isNullOrBlank() && !looksVirtual(it.location.orEmpty()) && it.startMillis > now && it.startMillis - now <= hours * 3_600_000L }
            .minByOrNull { it.startMillis } ?: return null
        return Trip("TO " + e.title.uppercase(), e.startMillis, e.location.orEmpty().trim(), e)
    }

    /** "Zoom", "https://meet.google.com/…", "Video call": nothing to drive to. */
    fun looksVirtual(location: String): Boolean {
        val l = location.lowercase()
        return "http://" in l || "https://" in l || listOf("zoom", "google meet", "teams", "video call", "facetime", "webex", "phone").any { l.trim() == it || l.startsWith("$it ") }
    }

    /** The default trip: weekdays, arrive by [arriveBy] ("09:00"). Today if still ahead, else the next weekday. */
    fun defaultTrip(label: String, destination: String?, arriveBy: String?, now: Long, zone: ZoneId): Trip? {
        if (destination.isNullOrBlank()) return null
        val t = arriveBy?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(8) {
            val at = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
            if (at > now && day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY)
                return Trip("TO " + label.uppercase(), at, destination, null)
            day = day.plusDays(1)
        }
        return null
    }

    /** First rainy hour in the next 12 h → "Rain from 8 AM" (or "Rain now"). */
    fun rainChip(hourly: List<HourForecast>, now: Long, zone: ZoneId): String? {
        val wet = setOf(WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING, WeatherCondition.SLEET, WeatherCondition.SNOW, WeatherCondition.HAIL)
        val h = hourly.filter { it.timeMillis in (now - 3_600_000L)..(now + 12 * 3_600_000L) }
            .firstOrNull { it.precipChance >= 50 || (it.condition in wet && it.precipChance >= 30) } ?: return null
        val kind = if (h.condition == WeatherCondition.SNOW) "Snow" else "Rain"
        if (h.timeMillis <= now) return "$kind now"
        val hr = Instant.ofEpochMilli(h.timeMillis).atZone(zone).hour
        return "$kind from ${if (hr % 12 == 0) 12 else hr % 12} ${if (hr < 12) "AM" else "PM"}"
    }

    /**
     * Fits [path] into the unit square keeping its shape (longitude scaled by cos(latitude)),
     * centred; y grows downwards. [also] is fitted with the same transform.
     */
    fun normalize(path: List<LatLon>, also: List<LatLon> = emptyList()): Pair<List<Pair<Float, Float>>, List<Pair<Float, Float>>> {
        if (path.size < 2) return emptyList<Pair<Float, Float>>() to emptyList()
        val all = path + also
        val k = cos(Math.toRadians(all.map { it.lat }.average()))
        val xs = all.map { it.lon * k }; val ys = all.map { -it.lat }
        val minX = xs.min(); val minY = ys.min()
        val span = max(xs.max() - minX, ys.max() - minY).takeIf { it > 0 } ?: 1.0
        val offX = (1 - (xs.max() - minX) / span) / 2; val offY = (1 - (ys.max() - minY) / span) / 2
        fun f(p: LatLon) = ((p.lon * k - minX) / span + offX).toFloat() to ((-p.lat - minY) / span + offY).toFloat()
        return path.map(::f) to also.map(::f)
    }

    fun build(trip: Trip, routes: List<Route>, hourly: List<HourForecast>, bufferMin: Int, now: Long, zone: ZoneId): CommuteUi {
        val main = routes.minBy { it.minutes }
        val alts = routes.filter { it !== main }
        val leave = leaveInMin(now, trip.arriveBy, main.minutes, bufferMin)
        val (shape, altShape) = normalize(main.path, alts.firstOrNull()?.path.orEmpty())
        return CommuteUi(
            header = trip.label,
            leaveInMin = leave,
            driveMin = main.minutes,
            via = main.via,
            arriveMillis = arrival(now, leave, main.minutes),
            trafficChip = null,
            rainChip = rainChip(hourly, now, zone),
            alternatives = alts.take(2).map { Alternative(if (it.via != null) "VIA " + it.via.uppercase() else "OTHER ROUTE", it.minutes) },
            route = shape.ifEmpty { null },
            altRoute = altShape.ifEmpty { null },
        )
    }

    /** Commute.dc.html sample: leave in 18, 24 min via Hwy 17, slow near Scotts Valley +6, rain from 8 AM. */
    fun demo(now: Long): CommuteUi = CommuteUi(
        header = "TO WORK", leaveInMin = 18, driveMin = 24, via = "Hwy 17", arriveMillis = now + 42 * 60_000L,
        trafficChip = "Slow near Scotts Valley · +6 min", rainChip = "Rain from 8 AM",
        alternatives = listOf(Alternative("VIA SOQUEL DR", 31), Alternative("BUS 35 · 8:20 AM", 41)),
        route = null, altRoute = null, demo = true,
    )
}
