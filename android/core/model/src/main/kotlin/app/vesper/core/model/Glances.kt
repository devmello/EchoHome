package app.vesper.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * Data for glance faces and complications. `:feature:home` binds Home Assistant-backed providers;
 * `:feature:clock` reads them (Digital complications, Weather and Calendar faces).
 */
data class Weather(
    /** Temperatures in the user's unit (HA's unit system); [unit] is "°F" or "°C". */
    val temperature: Int,
    val high: Int,
    val low: Int,
    val condition: WeatherCondition,
    val isNight: Boolean,
    val unit: String = "°F",
    /** Hourly forecast from the next hour, covering at least ~36 h (so tomorrow's hours are included). */
    val hourly: List<HourForecast> = emptyList(),
    val sunriseMillis: Long? = null,
    val sunsetMillis: Long? = null,
    /** Summary like "Rain tomorrow from 9 AM". */
    val tomorrowSummary: String? = null,
    val location: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** Air quality, UV and pollen (Open-Meteo air-quality API); null until loaded. */
    val air: AirQuality? = null,
    /** Precipitation over the next ~2 h in 15-minute steps (Open-Meteo `minutely_15`); null until loaded. */
    val nowcast: Nowcast? = null,
)

/**
 * Open-Meteo air quality. Every field is optional: pollen only exists where CAMS Europe covers
 * (it's empty in the US), and any request can fail on its own.
 */
data class AirQuality(
    /** US AQI, 0..500. */
    val usAqi: Int? = null,
    /** Current UV index. */
    val uvIndex: Float? = null,
    /** Today's maximum UV index. */
    val uvIndexMax: Float? = null,
    /** grains/m³ by kind ("grass", "birch", "alder", "olive", "mugwort", "ragweed"); empty when unavailable. */
    val pollen: Map<String, Float> = emptyMap(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val aqiCategory: String? get() = usAqi?.let {
        when { it <= 50 -> "Good"; it <= 100 -> "Moderate"; it <= 150 -> "Unhealthy for some"; it <= 200 -> "Unhealthy"; it <= 300 -> "Very unhealthy"; else -> "Hazardous" }
    }
}

/** Precipitation in mm per [stepMinutes] step, starting at [startMillis]. */
data class Nowcast(val startMillis: Long, val precipMm: List<Float>, val stepMinutes: Int = 15) {
    /** Minutes from [now] until the first step at or above [thresholdMm] (0 = raining now), or null. */
    fun minutesUntilRain(now: Long = System.currentTimeMillis(), thresholdMm: Float = 0.1f): Int? {
        for ((i, mm) in precipMm.withIndex()) {
            val stepEnd = startMillis + (i + 1) * stepMinutes * 60_000L
            if (stepEnd <= now) continue
            if (mm >= thresholdMm) return (((startMillis + i * stepMinutes * 60_000L) - now) / 60_000L).toInt().coerceAtLeast(0)
        }
        return null
    }
}

/**
 * Door-to-door travel time from home. `:feature:home` binds it (its commute routing); others
 * (leave-by alarms, leave-now reminders, the morning brief) read [VesperServices.travel].
 */
interface TravelEstimator {
    /** Minutes from home to [destination] (an address or place name, e.g. an event's location) when leaving around [departAtMillis]; null = unknown. */
    suspend fun minutesTo(destination: String, departAtMillis: Long): Int?
}

object NoTravelEstimator : TravelEstimator {
    override suspend fun minutesTo(destination: String, departAtMillis: Long): Int? = null
}

/**
 * Where the Echo is: weather, sunrise/sunset, the World face's "you are here", commute's start.
 * Precedence (NOTES §5.6): manual settings > auto (IP geolocation) > Livermore fallback.
 * `:feature:home` binds the real one in `VesperServices.location`.
 */
data class HomeLocation(val name: String, val lat: Double, val lon: Double, val source: Source = Source.FALLBACK) {
    enum class Source { MANUAL, AUTO, FALLBACK, DEMO }
    companion object {
        val LIVERMORE = HomeLocation("Livermore", 37.6819, -121.7680, Source.FALLBACK)
        /** The designs' sample location (demo data only). */
        val SANTA_CRUZ = HomeLocation("Santa Cruz", 36.97, -122.03, Source.DEMO)
        fun default(demo: Boolean) = if (demo) SANTA_CRUZ else LIVERMORE
    }
}

data class HourForecast(val timeMillis: Long, val temperature: Int, val condition: WeatherCondition, val precipChance: Int)

/** Mirrors Home Assistant's weather conditions. */
enum class WeatherCondition {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, POURING, LIGHTNING, SNOW, SLEET, HAIL, WIND, EXCEPTIONAL;

    companion object {
        fun fromHa(state: String): WeatherCondition = when (state) {
            "sunny", "clear-night" -> CLEAR
            "partlycloudy" -> PARTLY_CLOUDY
            "cloudy" -> CLOUDY
            "fog" -> FOG
            "rainy" -> RAIN
            "pouring" -> POURING
            "lightning", "lightning-rainy" -> LIGHTNING
            "snowy" -> SNOW
            "snowy-rainy" -> SLEET
            "hail" -> HAIL
            "windy", "windy-variant" -> WIND
            else -> EXCEPTIONAL
        }
    }
}

interface WeatherProvider {
    /** null = not configured / not loaded yet. */
    val weather: StateFlow<Weather?>
    fun refresh() {}
}

data class CalendarEvent(
    val id: String,
    val title: String,
    /** "Downtown · with Maya" */
    val subtitle: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    /** ARGB. */
    val color: Int,
    val location: String? = null,
    /** Whose calendar it came from ([Profile.id]); null = a shared calendar. */
    val profileId: String? = null,
)

interface CalendarProvider {
    /** Today's and tomorrow's events, sorted by start. */
    val events: StateFlow<List<CalendarEvent>>
    fun refresh() {}
}
