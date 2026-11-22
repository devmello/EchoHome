package com.echobedside.core.model

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
    val hourly: List<HourForecast> = emptyList(),
    val sunriseMillis: Long? = null,
    val sunsetMillis: Long? = null,
    /** Summary like "Rain tomorrow from 9 AM". */
    val tomorrowSummary: String? = null,
    val location: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

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
