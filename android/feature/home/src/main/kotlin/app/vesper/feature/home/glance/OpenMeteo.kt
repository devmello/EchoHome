package app.vesper.feature.home.glance

import app.vesper.core.model.HomeLocation
import app.vesper.core.model.Nowcast
import app.vesper.core.model.HourForecast
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.WeatherProvider
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the weather is for: `weather.lat`, `weather.lon`, `weather.place` in settings. */
data class Place(val lat: Double, val lon: Double, val name: String)

object WeatherSettings {
    /** Manual location, LOCATION type: "lat,lon"; its name is [LOCATION_PLACE]. */
    const val LOCATION = "weather.location"
    const val LOCATION_PLACE = "weather.location.place"
    /** Older manual keys, still read for one release. */
    const val LAT = "weather.lat"
    const val LON = "weather.lon"
    const val PLACE = "weather.place"
    /** Optional "F" or "C"; default from the locale (°F in the US and a few others). */
    const val UNIT = "weather.unit"

    /** The manual location: `weather.location` ("lat,lon", the settings page's LOCATION) + `.place`, else the old keys. */
    fun place(s: SettingsStore): Place? {
        latLon(text(s, LOCATION))?.let { (lat, lon) -> return Place(lat, lon, text(s, LOCATION_PLACE).orEmpty()) }
        // Read for one release: `weather.lat` / `weather.lon` / `weather.place` (the `home.set` era).
        val lat = number(s, LAT) ?: return null
        val lon = number(s, LON) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Place(lat, lon, text(s, PLACE).orEmpty())
    }

    /** "37.77,-122.42" → (lat, lon), range-checked. */
    fun latLon(v: String?): Pair<Double, Double>? {
        val parts = v?.split(',')?.map { it.trim() }?.takeIf { it.size == 2 } ?: return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    fun fahrenheit(s: SettingsStore, locale: Locale = Locale.getDefault()): Boolean = when (text(s, UNIT)?.uppercase()) {
        "F" -> true
        "C" -> false
        else -> locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")
    }

    /** Stored as a string or a float by whichever screen set it. */
    fun number(s: SettingsStore, key: String): Double? =
        try { s.getString(key, "").trim().toDoubleOrNull() } catch (_: ClassCastException) {
            try { s.getFloat(key, Float.NaN).toDouble().takeUnless { it.isNaN() } } catch (_: ClassCastException) { null }
        }

    fun text(s: SettingsStore, key: String): String? = try { s.getString(key, "").trim().ifEmpty { null } } catch (_: ClassCastException) { null }
}

/**
 * Open-Meteo (https://open-meteo.com): free, no key, non-commercial use, < 10 000 calls a day,
 * data CC BY 4.0 (attribution: "Weather data by Open-Meteo.com"). One call returns current
 * conditions, 48 hourly steps and 3 days; we make one every 30 minutes (48 a day).
 */
object OpenMeteo {
    val BASE: HttpUrl = "https://api.open-meteo.com/v1/forecast".toHttpUrl()
    const val NOWCAST_STEPS = 9

    /** Just the precipitation nowcast (small: ~300 bytes). */
    fun nowcastUrl(place: Place, base: HttpUrl = BASE): HttpUrl = base.newBuilder()
        .addQueryParameter("latitude", "%.3f".format(Locale.US, place.lat))
        .addQueryParameter("longitude", "%.3f".format(Locale.US, place.lon))
        .addQueryParameter("minutely_15", "precipitation")
        .addQueryParameter("forecast_minutely_15", NOWCAST_STEPS.toString())
        .addQueryParameter("timeformat", "unixtime")
        .build()

    /**
     * `minutely_15.precipitation` → [Nowcast]; null when the response has none. Open-Meteo's value at a
     * timestamp is the sum over the 15 minutes *before* it, so step i covers (time[i] − 15 min, time[i]]
     * and [Nowcast.startMillis] is the first timestamp minus one step (the contract's step i covers
     * start + i·step … start + (i+1)·step). Steps already over are skipped by `minutesUntilRain`.
     */
    fun parseNowcast(body: JSONObject): Nowcast? {
        val m = body.optJSONObject("minutely_15") ?: return null
        val time = m.optJSONArray("time") ?: return null
        val mm = m.optJSONArray("precipitation") ?: return null
        if (time.length() == 0 || mm.length() == 0) return null
        val n = minOf(time.length(), mm.length())
        val step = if (n > 1) ((time.getLong(1) - time.getLong(0)) / 60).toInt().takeIf { it > 0 } ?: 15 else 15
        // null values (no data) count as dry.
        val values = (0 until n).map { i -> mm.optDouble(i).takeUnless { it.isNaN() }?.toFloat() ?: 0f }
        return Nowcast(startMillis = time.getLong(0) * 1000 - step * 60_000L, precipMm = values, stepMinutes = step)
    }

    fun url(place: Place, fahrenheit: Boolean, base: HttpUrl = BASE): HttpUrl = base.newBuilder()
        // 3 decimals ≈ 100 m: plenty for a forecast, and no more precise than needed.
        .addQueryParameter("latitude", "%.3f".format(Locale.US, place.lat))
        .addQueryParameter("longitude", "%.3f".format(Locale.US, place.lon))
        .addQueryParameter("current", "temperature_2m,weather_code,is_day")
        .addQueryParameter("hourly", "temperature_2m,weather_code,precipitation_probability")
        .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max")
        // Rain nowcast: the next 9 quarter-hours (~2 h). The nowcast loop re-asks for just this every ~12 min.
        .addQueryParameter("minutely_15", "precipitation")
        .addQueryParameter("forecast_minutely_15", NOWCAST_STEPS.toString())
        .addQueryParameter("temperature_unit", if (fahrenheit) "fahrenheit" else "celsius")
        .addQueryParameter("timezone", "auto")
        .addQueryParameter("timeformat", "unixtime")
        .addQueryParameter("forecast_days", "3")
        .build()

    /** WMO weather interpretation codes → our conditions. */
    fun condition(code: Int): WeatherCondition = when (code) {
        0 -> WeatherCondition.CLEAR
        1, 2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOG
        51, 53, 55, 56, 57, 61, 63, 80, 81 -> WeatherCondition.RAIN
        65, 82 -> WeatherCondition.POURING
        66, 67 -> WeatherCondition.SLEET
        71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
        95 -> WeatherCondition.LIGHTNING
        96, 99 -> WeatherCondition.HAIL
        else -> WeatherCondition.EXCEPTIONAL
    }

    private val WET = setOf(WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.SLEET, WeatherCondition.SNOW,
        WeatherCondition.LIGHTNING, WeatherCondition.HAIL)

    /** Parses a forecast response. Throws JSONException on a malformed body. */
    fun parse(body: JSONObject, place: Place, fahrenheit: Boolean, now: Long = System.currentTimeMillis()): Weather {
        val zone = runCatching { ZoneId.of(body.optString("timezone")) }.getOrDefault(ZoneId.systemDefault())
        val cur = body.getJSONObject("current")
        val h = body.getJSONObject("hourly")
        val d = body.getJSONObject("daily")
        val hTime = h.getJSONArray("time"); val hTemp = h.getJSONArray("temperature_2m")
        val hCode = h.getJSONArray("weather_code"); val hRain = h.optJSONArray("precipitation_probability")
        val hours = (0 until hTime.length()).mapNotNull { i ->
            val t = hTime.getLong(i) * 1000
            val temp = hTemp.optDouble(i).takeUnless { it.isNaN() } ?: return@mapNotNull null
            HourForecast(t, temp.roundToInt(), condition(hCode.optInt(i)), hRain?.optInt(i, 0) ?: 0)
        }
        val dTime = d.getJSONArray("time")
        val today = localDate(now, zone)
        val di = (0 until dTime.length()).firstOrNull { localDate(dTime.getLong(it) * 1000, zone) == today } ?: 0
        val temp = cur.getDouble("temperature_2m").roundToInt()
        val high = d.getJSONArray("temperature_2m_max").optDouble(di).takeUnless { it.isNaN() }?.roundToInt() ?: temp
        val low = d.getJSONArray("temperature_2m_min").optDouble(di).takeUnless { it.isNaN() }?.roundToInt() ?: temp
        return Weather(
            temperature = temp,
            high = maxOf(high, temp),
            low = minOf(low, temp),
            condition = condition(cur.optInt("weather_code")),
            isNight = cur.optInt("is_day", 1) == 0,
            unit = if (fahrenheit) "°F" else "°C",
            hourly = hours.filter { it.timeMillis > now - 3_600_000L }.take(48), // ≥ 36 h (the contract): 3 forecast days leave ≥ 49 h at any hour
            sunriseMillis = nextOf(d.optJSONArray("sunrise"), now),
            sunsetMillis = nextOf(d.optJSONArray("sunset"), now),
            tomorrowSummary = tomorrowSummary(hours, d, now, zone),
            location = place.name,
            updatedAtMillis = now,
            nowcast = parseNowcast(body),
        )
    }

    /** The first time in [arr] (unix seconds) after [now]: "next sunrise". */
    private fun nextOf(arr: JSONArray?, now: Long): Long? {
        if (arr == null) return null
        return (0 until arr.length()).map { arr.optLong(it) * 1000 }.firstOrNull { it > now }
    }

    /** "Rain tomorrow from 9 AM" from the first wet hour tomorrow (6 AM–10 PM), else the daily chance. */
    fun tomorrowSummary(hours: List<HourForecast>, daily: JSONObject, now: Long, zone: ZoneId): String? {
        val tomorrow = localDate(now, zone).plusDays(1)
        val wet = hours.firstOrNull { f ->
            val t = Instant.ofEpochMilli(f.timeMillis).atZone(zone)
            t.toLocalDate() == tomorrow && t.hour in 6..22 && (f.precipChance >= 50 || f.condition in WET && f.precipChance >= 30)
        }
        if (wet != null) {
            val hr = Instant.ofEpochMilli(wet.timeMillis).atZone(zone).hour
            val kind = if (wet.condition == WeatherCondition.SNOW) "Snow" else "Rain"
            return "$kind tomorrow from ${if (hr % 12 == 0) 12 else hr % 12} ${if (hr < 12) "AM" else "PM"}"
        }
        val times = daily.optJSONArray("time") ?: return null
        val i = (0 until times.length()).firstOrNull { localDate(times.getLong(it) * 1000, zone) == tomorrow } ?: return null
        val chance = daily.optJSONArray("precipitation_probability_max")?.optInt(i, 0) ?: 0
        val c = condition(daily.optJSONArray("weather_code")?.optInt(i) ?: 0)
        return if (chance >= 50) (if (c == WeatherCondition.SNOW) "Snow likely tomorrow" else "Rain likely tomorrow") else null
    }
}

