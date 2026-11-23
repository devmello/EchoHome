package app.vesper.feature.home.glance

import app.vesper.core.model.AirQuality
import app.vesper.core.model.HomeLocation
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherProvider
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

/**
 * Open-Meteo air quality (https://air-quality-api.open-meteo.com, keyless, same terms): current US AQI,
 * UV index, the six CAMS pollen kinds and today's UV maximum. Pollen exists only in Europe (CAMS):
 * elsewhere the values are null and the map stays empty.
 */
object OpenMeteoAir {
    val BASE: HttpUrl = "https://air-quality-api.open-meteo.com/v1/air-quality".toHttpUrl()
    val POLLEN = listOf("grass", "birch", "alder", "olive", "mugwort", "ragweed")

    fun url(place: Place, base: HttpUrl = BASE): HttpUrl = base.newBuilder()
        .addQueryParameter("latitude", "%.3f".format(Locale.US, place.lat))
        .addQueryParameter("longitude", "%.3f".format(Locale.US, place.lon))
        .addQueryParameter("current", (listOf("us_aqi", "uv_index") + POLLEN.map { it + "_pollen" }).joinToString(","))
        .addQueryParameter("daily", "uv_index_max")
        .addQueryParameter("timezone", "auto")
        .addQueryParameter("timeformat", "unixtime")
        .addQueryParameter("forecast_days", "1")
        .build()

    /** Every field is optional (null = not available). Null when the body has nothing usable. */
    fun parse(body: JSONObject, now: Long = System.currentTimeMillis()): AirQuality? {
        val cur = body.optJSONObject("current") ?: return null
        fun num(o: JSONObject, k: String): Double? = if (o.isNull(k)) null else o.optDouble(k).takeUnless { it.isNaN() }
        val pollen = LinkedHashMap<String, Float>()
        for (k in POLLEN) num(cur, k + "_pollen")?.let { pollen[k] = it.toFloat() }
        val max = body.optJSONObject("daily")?.optJSONArray("uv_index_max")?.let { a ->
            if (a.length() == 0 || a.isNull(0)) null else a.optDouble(0).takeUnless { it.isNaN() }
        }
        val air = AirQuality(
            usAqi = num(cur, "us_aqi")?.roundToInt(),
            uvIndex = num(cur, "uv_index")?.toFloat(),
            uvIndexMax = max?.toFloat(),
            pollen = pollen,
            updatedAtMillis = now,
        )
        return if (air.usAqi == null && air.uvIndex == null && air.uvIndexMax == null && air.pollen.isEmpty()) null else air
    }
}

/**
 * [WeatherProvider] on Open-Meteo for `VesperServices.location` ([LocationResolver]: manual > auto >
 * Livermore), so it works out of the box with no settings. While the location is the DEMO one
 * (demo data, nothing manual) it mirrors [fallback], the design's sample weather. Refreshes every
 * 30 min and when the location or `weather.unit` changes; after a failure it retries in 5 min and
 * keeps the last good forecast.
 *
 * Alongside each forecast it asks the keyless air-quality API for [Weather.air] (a failure there
 * keeps the previous value for up to 3 h and never fails the forecast). The rain [Weather.nowcast]
 * rides the forecast call and is refreshed on its own every ~12 min with a tiny request, but only
 * while something collects [weather] (no request and no wakeup for nobody).
 */
class OpenMeteoWeatherProvider(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val fallback: WeatherProvider,
    private val location: StateFlow<HomeLocation>,
    private val client: OkHttpClient = Net.http,
    private val base: HttpUrl = OpenMeteo.BASE,
    private val periodMs: Long = 30 * 60_000L,
    private val retryMs: Long = 5 * 60_000L,
    private val airBase: HttpUrl = OpenMeteoAir.BASE,
    private val nowcastMs: Long = 12 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : WeatherProvider {
    private val _weather = MutableStateFlow(if (location.value.source == HomeLocation.Source.DEMO) fallback.weather.value else null)
    override val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private var loop: Job? = null
    private var nowcastLoop: Job? = null
    private var mirror: Job? = null
    /** What's running: (location, °F). */
    private var active: Pair<HomeLocation, Boolean>? = null
    /** When the nowcast was last refreshed (by the forecast call or the nowcast loop). */
    @Volatile private var nowcastAt = 0L

    fun start() {
        scope.launch { location.collect { restart(force = false) } }
        scope.launch { settings.changes.filter { it == WeatherSettings.UNIT }.collect { restart(force = false) } }
    }

    override fun refresh() = restart(force = true)

    private fun restart(force: Boolean) {
        val loc = location.value
        val key = loc to WeatherSettings.fahrenheit(settings)
        if (!force && key == active) return
        loop?.cancel(); nowcastLoop?.cancel(); mirror?.cancel(); loop = null; nowcastLoop = null; mirror = null
        active = key
        if (loc.source == HomeLocation.Source.DEMO) {
            mirror = scope.launch { fallback.weather.collect { _weather.value = it } }
            return
        }
        val place = loc.toPlace()
        loop = scope.launch {
            while (true) {
                val ok = fetch(place)
                delay(if (ok) periodMs else retryMs)
            }
        }
        nowcastLoop = scope.launch {
            while (true) {
                delay((nowcastAt + nowcastMs - clock()).coerceAtLeast(minOf(60_000L, nowcastMs)))
                // Nobody reads the weather: sleep until somebody does (no request for nobody).
                _weather.subscriptionCount.first { it > 0 }
                if (clock() - nowcastAt >= nowcastMs - 30_000L) fetchNowcast(place)
            }
        }
    }

    private suspend fun fetch(place: Place): Boolean = try {
        val f = WeatherSettings.fahrenheit(settings)
        val body = JSONObject(Net.getText(client, OpenMeteo.url(place, f, base).toString()))
        val w = OpenMeteo.parse(body, place, f, clock())
        val old = _weather.value?.takeIf { it.location == place.name }
        // Air quality is a separate call; its failure keeps the last value for up to 3 h.
        val air = try { OpenMeteoAir.parse(JSONObject(Net.getText(client, OpenMeteoAir.url(place, airBase).toString())), clock()) }
            catch (_: IOException) { null } catch (_: JSONException) { null }
        _weather.value = w.copy(air = air ?: old?.air?.takeIf { clock() - it.updatedAtMillis < 3 * 3_600_000L })
        if (w.nowcast != null) nowcastAt = clock()
        true
    } catch (_: IOException) { false } catch (_: JSONException) { false }

    private suspend fun fetchNowcast(place: Place) {
        try {
            val nc = OpenMeteo.parseNowcast(JSONObject(Net.getText(client, OpenMeteo.nowcastUrl(place, base).toString()))) ?: return
            nowcastAt = clock()
            _weather.update { w -> w?.copy(nowcast = nc) }
        } catch (_: IOException) { } catch (_: JSONException) { }
    }
}
