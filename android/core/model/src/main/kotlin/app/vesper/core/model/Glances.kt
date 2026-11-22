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
)

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
)

interface CalendarProvider {
    /** Today's and tomorrow's events, sorted by start. */
    val events: StateFlow<List<CalendarEvent>>
    fun refresh() {}
}
