package com.echobedside.feature.home.glance

import com.echobedside.core.model.HaEntity
import com.echobedside.core.model.HourForecast
import com.echobedside.core.model.Weather
import com.echobedside.core.model.WeatherCondition
import com.echobedside.feature.home.ha.forEachObject
import com.echobedside.feature.home.ha.optDoubleOrNull
import com.echobedside.feature.home.ha.optStringOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import org.json.JSONObject

/** One entry of `weather.get_forecasts` (hourly or daily). */
data class Forecast(val timeMillis: Long, val temperature: Double, val low: Double?, val condition: String, val precipChance: Int?)

/** Home Assistant weather entity + forecasts → the [Weather] contract. Pure, for unit tests. */
object WeatherParse {
    /** `weather.get_forecasts` response: `{"weather.home": {"forecast": [ ... ]}}`. */
    fun forecasts(response: JSONObject, entityId: String): List<Forecast> {
        val arr = response.optJSONObject(entityId)?.optJSONArray("forecast") ?: return emptyList()
        val out = ArrayList<Forecast>()
        arr.forEachObject { f ->
            val t = parseTime(f.optString("datetime")) ?: return@forEachObject
            val temp = f.optDoubleOrNull("temperature") ?: return@forEachObject
            out += Forecast(t, temp, f.optDoubleOrNull("templow"), f.optString("condition"), f.optDoubleOrNull("precipitation_probability")?.roundToInt())
        }
        return out.sortedBy { it.timeMillis }
    }

    fun weather(
        entity: HaEntity,
        sun: HaEntity?,
        hourly: List<Forecast>,
        daily: List<Forecast>,
        location: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Weather {
        val a = entity.attributes
        val current = a.optDoubleOrNull("temperature") ?: hourly.firstOrNull()?.temperature ?: 0.0
        val today = localDate(now, zone)
        val todayDaily = daily.firstOrNull { localDate(it.timeMillis, zone) == today } ?: daily.firstOrNull()
        val todaysHours = hourly.filter { localDate(it.timeMillis, zone) == today }.map { it.temperature }
        val high = todayDaily?.temperature ?: (todaysHours + current).max()
        val low = todayDaily?.low ?: (todaysHours + current).min()
        val night = sun?.state?.let { it == "below_horizon" } ?: (entity.state == "clear-night")
        val unit = a.optStringOrNull("temperature_unit") ?: "°F"
        val nextHours = hourly.filter { it.timeMillis > now - 3_600_000L }.take(24)
        return Weather(
            temperature = current.roundToInt(),
            high = high.roundToInt().coerceAtLeast(current.roundToInt()),
            low = low.roundToInt().coerceAtMost(current.roundToInt()),
            condition = WeatherCondition.fromHa(entity.state),
            isNight = night,
            unit = unit,
            hourly = nextHours.map { HourForecast(it.timeMillis, it.temperature.roundToInt(), WeatherCondition.fromHa(it.condition), it.precipChance ?: 0) },
            sunriseMillis = sun?.attributes?.optStringOrNull("next_rising")?.let(::parseTime),
            sunsetMillis = sun?.attributes?.optStringOrNull("next_setting")?.let(::parseTime),
            tomorrowSummary = tomorrowSummary(hourly, daily, now, zone),
            location = location,
            updatedAtMillis = now,
        )
    }

    private val WET = setOf("rainy", "pouring", "lightning-rainy", "snowy", "snowy-rainy", "hail")

    /** "Rain tomorrow from 9 AM" from the first wet hour tomorrow (6 AM–10 PM), else the daily chance. */
    fun tomorrowSummary(hourly: List<Forecast>, daily: List<Forecast>, now: Long, zone: ZoneId): String? {
        val tomorrow = localDate(now, zone).plusDays(1)
        val wet = hourly.firstOrNull { f ->
            val t = Instant.ofEpochMilli(f.timeMillis).atZone(zone)
            t.toLocalDate() == tomorrow && t.hour in 6..22 && ((f.precipChance ?: 0) >= 50 || f.condition in WET)
        }
        if (wet != null) {
            val h = Instant.ofEpochMilli(wet.timeMillis).atZone(zone).hour
            val kind = if (wet.condition.startsWith("snow")) "Snow" else "Rain"
            return "$kind tomorrow from ${(h % 12).let { if (it == 0) 12 else it }} ${if (h < 12) "AM" else "PM"}"
        }
        val d = daily.firstOrNull { localDate(it.timeMillis, zone) == tomorrow } ?: return null
        return if ((d.precipChance ?: 0) >= 50 || d.condition in WET) (if (d.condition.startsWith("snow")) "Snow likely tomorrow" else "Rain likely tomorrow") else null
    }

    /** ISO date-time with offset ("2026-11-22T20:00:00+00:00"), or a bare date (local midnight). */
    fun parseTime(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try { LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
}
