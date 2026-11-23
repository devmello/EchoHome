package app.vesper.feature.home.brief

import app.vesper.core.model.AirQuality
import app.vesper.core.model.Banner
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.Profile
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.feature.home.alerts.LeaveRule
import app.vesper.feature.home.alerts.RainRule
import app.vesper.feature.home.glance.clock12
import app.vesper.feature.home.glance.localDate
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** A coloured line under the weather ("Rain in 20 min", "AQI 42 · Good"). [color] is ARGB. */
data class BriefChip(val text: String, val color: Int)

data class BriefWeather(val place: String, val temp: String, val summary: String, val condition: WeatherCondition, val chips: List<BriefChip>)

/** [leave] = "Leave by 7:50 AM · 25 min drive" when the event has a place and the travel is known. */
data class BriefEvent(val title: String, val whenText: String, val leave: String?, val leaveAtMillis: Long? = null)

data class OvernightGroup(val app: String, val color: Int, val count: Int, /** newest first, at most 2. */ val titles: List<String>)

data class Overnight(val total: Int, val groups: List<OvernightGroup>, val more: Int)

/** Everything the brief shows. */
data class BriefData(
    val greeting: String,
    val dateLine: String,
    val weather: BriefWeather?,
    val event: BriefEvent?,
    val overnight: Overnight,
    val demo: Boolean = false,
)

/** Builds [BriefData] from the live services' values (pure, unit-tested). */
object BriefBuilder {
    const val OVERNIGHT_FROM_HOUR = 22
    const val MAX_GROUPS = 3

    private val GREEN = 0xFF30D158.toInt()
    private val YELLOW = 0xFFFFD60A.toInt()
    private val ORANGE = 0xFFFF9F0A.toInt()
    private val RED = 0xFFFF453A.toInt()
    private val CYAN = 0xFF64D2FF.toInt()

    fun greeting(profile: Profile?): String =
        if (profile == null || profile.id == Profile.SHARED_ID || profile.name.isBlank()) "Good morning" else "Good morning, ${profile.name.trim()}"

    /** "Sunday, November 22". */
    fun dateLine(now: Long, zone: ZoneId): String {
        val d = localDate(now, zone)
        return "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, ${d.month.getDisplayName(TextStyle.FULL, Locale.US)} ${d.dayOfMonth}"
    }

    /** The most recent 22:00 at or before [now]: where "overnight" starts. */
    fun overnightStart(now: Long, zone: ZoneId): Long {
        val t = Instant.ofEpochMilli(now).atZone(zone)
        var start = t.toLocalDate().atTime(LocalTime.of(OVERNIGHT_FROM_HOUR, 0)).atZone(zone)
        if (start.toInstant().toEpochMilli() > now) start = start.minusDays(1)
        return start.toInstant().toEpochMilli()
    }

    fun overnight(banners: List<Banner>, now: Long, zone: ZoneId): Overnight {
        val from = overnightStart(now, zone)
        val recent = banners.filter { it.postedAtMillis in from..now }
        val groups = recent.groupBy { it.appName.ifBlank { "Notifications" } }.map { (app, list) ->
            val sorted = list.sortedByDescending { it.postedAtMillis }
            OvernightGroup(app, sorted.first().appColor, list.size,
                sorted.map { it.title.ifBlank { it.body } }.filter { it.isNotBlank() }.distinct().take(2))
        }.sortedWith(compareByDescending<OvernightGroup> { it.count }.thenBy { it.app })
        return Overnight(recent.size, groups.take(MAX_GROUPS), (groups.size - MAX_GROUPS).coerceAtLeast(0))
    }

    /** The next timed event (today) that hasn't ended, for [active]'s calendar and the shared ones. */
    fun firstEvent(events: List<CalendarEvent>, now: Long, active: Profile?): CalendarEvent? = events
        .filter { e ->
            !e.allDay && e.endMillis > now &&
                (e.profileId == null || active == null || active.id == Profile.SHARED_ID || e.profileId == active.id)
        }.minByOrNull { it.startMillis }

    fun event(e: CalendarEvent?, travelMinutes: Int?, bufferMin: Int, zone: ZoneId): BriefEvent? {
        e ?: return null
        val place = e.location?.substringBefore('\n')?.substringBefore(',')?.trim()?.ifEmpty { null } ?: e.subtitle.ifBlank { null }
        val whenText = clock12(e.startMillis, zone) + (place?.let { " · $it" } ?: "")
        val leaveAt = travelMinutes?.let { LeaveRule.leaveAt(e.startMillis, it, bufferMin) }
        val leave = leaveAt?.let { "Leave by ${clock12(it, zone)} · $travelMinutes min drive" }
        return BriefEvent(e.title, whenText, leave, leaveAt)
    }

    fun weather(w: Weather?, now: Long): BriefWeather? {
        w ?: return null
        val cond = when (w.condition) {
            WeatherCondition.CLEAR -> if (w.isNight) "Clear" else "Sunny"
            WeatherCondition.PARTLY_CLOUDY -> "Partly cloudy"
            WeatherCondition.CLOUDY -> "Cloudy"
            WeatherCondition.FOG -> "Fog"
            WeatherCondition.RAIN -> "Rain"
            WeatherCondition.POURING -> "Heavy rain"
            WeatherCondition.LIGHTNING -> "Thunderstorms"
            WeatherCondition.SNOW -> "Snow"
            WeatherCondition.SLEET -> "Sleet"
            WeatherCondition.HAIL -> "Hail"
            WeatherCondition.WIND -> "Windy"
            WeatherCondition.EXCEPTIONAL -> "Weather"
        }
        val chips = ArrayList<BriefChip>()
        rainLine(w, now)?.let { chips += BriefChip(it, CYAN) }
        w.air?.let { air ->
            air.usAqi?.let { chips += BriefChip("AQI $it · ${air.aqiCategory}", aqiColor(it)) }
            (air.uvIndexMax ?: air.uvIndex)?.let { uv -> if (uv >= 1f) chips += BriefChip("UV ${if (air.uvIndexMax != null) "peak " else ""}${uv.roundToInt()} · ${uvCategory(uv)}", uvColor(uv)) }
            pollenLine(air)?.let { chips += BriefChip(it, YELLOW) }
        }
        return BriefWeather(w.location, "${w.temperature}°", "$cond · H:${w.high}° L:${w.low}°", w.condition, chips)
    }

    /** "Raining now", "Rain in 20 min", "Dry for the next 2 h"; null without a nowcast. */
    fun rainLine(w: Weather, now: Long): String? {
        val n = w.nowcast ?: return null
        if (RainRule.rainingNow(n, now)) return "Raining now"
        val m = n.minutesUntilRain(now, RainRule.WET_MM) ?: return "Dry for the next 2 h"
        val mm = m.coerceAtLeast(1)
        return "Rain in " + if (mm >= 60) "${mm / 60} h ${mm % 60} min".replace(" 0 min", "") else "$mm min"
    }

    fun aqiColor(aqi: Int) = when { aqi <= 50 -> GREEN; aqi <= 100 -> YELLOW; aqi <= 150 -> ORANGE; else -> RED }
    fun uvCategory(uv: Float) = when { uv < 3f -> "Low"; uv < 6f -> "Moderate"; uv < 8f -> "High"; uv < 11f -> "Very high"; else -> "Extreme" }
    fun uvColor(uv: Float) = when { uv < 3f -> GREEN; uv < 6f -> YELLOW; uv < 8f -> ORANGE; else -> RED }

    /** "Grass pollen 35" for the highest kind at 1 grain/m³ or more; null when there's none (outside Europe: always). */
    fun pollenLine(air: AirQuality): String? {
        val top = air.pollen.maxByOrNull { it.value } ?: return null
        if (top.value < 1f) return null
        return "${top.key.replaceFirstChar { it.uppercase() }} pollen ${top.value.roundToInt()}"
    }

    fun build(
        now: Long, zone: ZoneId, profile: Profile?, weather: Weather?, events: List<CalendarEvent>,
        travelMinutes: Int?, bufferMin: Int, banners: List<Banner>,
    ): BriefData = BriefData(
        greeting = greeting(profile),
        dateLine = dateLine(now, zone),
        weather = weather(weather, now),
        event = event(firstEvent(events, now, profile), travelMinutes, bufferMin, zone),
        overnight = overnight(banners, now, zone),
    )

    /** The designs' sample data (`--es brief demo`). */
    fun demo(now: Long, zone: ZoneId): BriefData {
        val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        val orange = 0xFFFF9F0A.toInt(); val green = 0xFF30D158.toInt(); val blue = 0xFF0A84FF.toInt()
        return BriefData(
            greeting = "Good morning, Maya",
            dateLine = dateLine(now, zone),
            weather = BriefWeather("Santa Cruz", "52°", "Sunny · H:63° L:46°", WeatherCondition.CLEAR, listOf(
                BriefChip("Rain in 45 min", CYAN), BriefChip("AQI 42 · Good", GREEN), BriefChip("UV peak 6 · High", ORANGE))),
            event = BriefEvent("Dentist", "8:30 AM · Pacific Ave", "Leave by 8:00 AM · 25 min drive", start - 30 * 60_000L),
            overnight = Overnight(7, listOf(
                OvernightGroup("Messages", green, 4, listOf("Sam: See you at 9?", "Mom")),
                OvernightGroup("Mail", blue, 2, listOf("Your order has shipped")),
                OvernightGroup("Calendar", orange, 1, listOf("Dentist tomorrow")),
            ), 0),
            demo = true,
        )
    }

}
