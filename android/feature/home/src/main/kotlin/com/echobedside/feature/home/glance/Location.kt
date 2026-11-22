package com.echobedside.feature.home.glance

import com.echobedside.core.model.HomeLocation
import com.echobedside.core.model.SettingsStore
import java.io.IOException
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject

/**
 * Where "here" is ([HomeLocation], bound to `EchoServices.location`), for weather, the commute's
 * home and other features. Precedence:
 *  1. MANUAL: `weather.lat` / `weather.lon` / `weather.place` (a settings screen or `home.set`);
 *  2. DEMO: Santa Cruz, the designs' sample, only while demo data is on and nothing is manual;
 *  3. AUTO: detected from the public IP, stored under `weather.auto.*` (never touches the manual keys);
 *  4. FALLBACK: Livermore, CA.
 */
object Locations {
    val LIVERMORE = HomeLocation.LIVERMORE.toPlace()

    const val AUTO_LAT = "weather.auto.lat"
    const val AUTO_LON = "weather.auto.lon"
    const val AUTO_PLACE = "weather.auto.place"
    /** Epoch millis of the last lookup attempt that answered (accepted or held back). */
    const val AUTO_AT = "weather.auto.at"
    /** A far-away result held back once ("lat,lon,place"); accepted if the next lookup agrees. */
    const val AUTO_PENDING = "weather.auto.pending"

    /** The cached auto-detected place, if any. */
    fun auto(s: SettingsStore): Place? {
        val lat = WeatherSettings.number(s, AUTO_LAT) ?: return null
        val lon = WeatherSettings.number(s, AUTO_LON) ?: return null
        return Place(lat, lon, WeatherSettings.text(s, AUTO_PLACE).orEmpty())
    }

    /** manual > (demo: Santa Cruz) > auto > Livermore. */
    fun resolve(s: SettingsStore, demo: Boolean): HomeLocation {
        WeatherSettings.place(s)?.let { return HomeLocation(it.name, it.lat, it.lon, HomeLocation.Source.MANUAL) }
        if (demo) return HomeLocation.SANTA_CRUZ
        auto(s)?.let { return HomeLocation(it.name, it.lat, it.lon, HomeLocation.Source.AUTO) }
        return HomeLocation.LIVERMORE
    }

    /** Great-circle distance in km. */
    fun km(a: Place, b: Place): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}

fun HomeLocation.toPlace() = Place(lat, lon, name)

/**
 * Publishes [Locations.resolve] as a flow (bound to `EchoServices.location`). Recomputes when the
 * `weather.*` location keys change (debounced), keeps the auto location fresh with [AutoLocation]
 * (a local check each hour; a network lookup at most daily) and looks again after a network change.
 * No lookups while a manual location is set or in demo mode.
 */
class LocationResolver(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val demo: Boolean,
    private val auto: AutoLocation?,
    private val networkChanges: Flow<Unit> = emptyFlow(),
    private val checkEveryMs: Long = 3_600_000L,
) {
    private val _location = MutableStateFlow(Locations.resolve(settings, demo))
    val location: StateFlow<HomeLocation> = _location.asStateFlow()
    private var started = false

    private fun needsAuto() = auto != null && !demo && WeatherSettings.place(settings) == null

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            settings.changes.filter { it in KEYS }.debounce(400).collect { _location.value = Locations.resolve(settings, demo) }
        }
        scope.launch {
            while (true) {
                if (needsAuto()) { auto!!.refresh(); _location.value = Locations.resolve(settings, demo) }
                delay(checkEveryMs)
            }
        }
        if (auto != null) scope.launch {
            networkChanges.collect { if (needsAuto()) { auto.refresh(force = true); _location.value = Locations.resolve(settings, demo) } }
        }
    }

    companion object {
        private val KEYS = setOf(WeatherSettings.LAT, WeatherSettings.LON, WeatherSettings.PLACE,
            Locations.AUTO_LAT, Locations.AUTO_LON, Locations.AUTO_PLACE)
    }
}

/**
 * Keyless HTTPS IP geolocation: ipapi.co first, ipwho.is if that fails. Each request is a bare GET
 * with the app's generic User-Agent: no query, no device name, no user data (the services see the
 * public IP, which any request does). Free tiers: ipapi.co ~1000/day, ipwho.is non-commercial;
 * we ask at most once a day plus after a network change.
 */
class IpLocator(
    private val client: OkHttpClient = Net.http,
    private val sources: List<HttpUrl> = listOf("https://ipapi.co/json/".toHttpUrl(), "https://ipwho.is/".toHttpUrl()),
    /** Fills in a missing city name (Nominatim reverse); null = leave it blank. */
    private val reverse: (suspend (Double, Double) -> String?)? = null,
) {
    suspend fun lookup(): Place? {
        for (url in sources) {
            val p = try { parse(JSONObject(Net.getText(client, url.toString()))) } catch (_: IOException) { null } catch (_: JSONException) { null }
            if (p != null) {
                if (p.name.isNotEmpty() || reverse == null) return p
                val name = try { reverse.invoke(p.lat, p.lon) } catch (_: IOException) { null } catch (_: JSONException) { null }
                return p.copy(name = name.orEmpty())
            }
        }
        return null
    }

    companion object {
        /** Both services: `latitude`, `longitude`, `city`. ipapi.co errors with `error: true`, ipwho.is with `success: false`. */
        fun parse(o: JSONObject): Place? {
            if (o.optBoolean("error", false) || !o.optBoolean("success", true)) return null
            val lat = o.optDouble("latitude").takeUnless { it.isNaN() } ?: return null
            val lon = o.optDouble("longitude").takeUnless { it.isNaN() } ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
            return Place(lat, lon, o.optString("city").trim())
        }

        /** Nominatim reverse geocoding (same policy as [com.echobedside.feature.home.commute.Geocoder]): town-level name. */
        fun nominatimReverse(client: OkHttpClient = Net.http, base: HttpUrl = "https://nominatim.openstreetmap.org/reverse".toHttpUrl()): suspend (Double, Double) -> String? = { lat, lon ->
            val url = base.newBuilder().addQueryParameter("lat", "%.3f".format(Locale.US, lat)).addQueryParameter("lon", "%.3f".format(Locale.US, lon))
                .addQueryParameter("format", "jsonv2").addQueryParameter("zoom", "10").build()
            val a = JSONObject(Net.getText(client, url.toString())).optJSONObject("address")
            listOf("city", "town", "village", "municipality", "county").firstNotNullOfOrNull { k -> a?.optString(k)?.takeIf { it.isNotBlank() } }
        }
    }
}

/**
 * The auto-detected location, cached in settings. [refresh] looks up at most once per [maxAgeMs]
 * (a day) unless forced (network change, still rate-limited by [minGapMs]). A result more than
 * [jumpKm] from the cached place is held back once (VPN, carrier routing) and accepted only if the
 * next lookup lands near it again. A failed lookup keeps the cached value.
 */
class AutoLocation(
    private val settings: SettingsStore,
    private val lookup: suspend () -> Place? = IpLocator(reverse = IpLocator.nominatimReverse())::lookup,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 24 * 3_600_000L,
    private val minGapMs: Long = 10 * 60_000L,
    private val jumpKm: Double = 150.0,
) {
    private var lastAttempt = Long.MIN_VALUE / 2

    fun cached(): Place? = Locations.auto(settings)

    /** Returns the (possibly updated) cached place, or null if none was ever found. */
    suspend fun refresh(force: Boolean = false): Place? {
        val now = clock()
        val at = WeatherSettings.number(settings, Locations.AUTO_AT)?.toLong()
        val fresh = at != null && now - at < maxAgeMs && cached() != null
        if ((fresh && !force) || now - lastAttempt < minGapMs) return cached()
        lastAttempt = now
        val found = lookup() ?: return cached()
        val old = cached()
        val pending = pendingPlace()
        val accept = old == null || Locations.km(old, found) <= jumpKm || (pending != null && Locations.km(pending, found) <= jumpKm)
        if (accept) {
            settings.remove(Locations.AUTO_PENDING)
            if (old == null || old.lat != found.lat || old.lon != found.lon || old.name != found.name) {
                settings.putString(Locations.AUTO_LAT, found.lat.toString())
                settings.putString(Locations.AUTO_LON, found.lon.toString())
                settings.putString(Locations.AUTO_PLACE, found.name)
            }
        } else {
            settings.putString(Locations.AUTO_PENDING, "${found.lat},${found.lon},${found.name}")
        }
        settings.putString(Locations.AUTO_AT, now.toString())
        return cached()
    }

    private fun pendingPlace(): Place? {
        val parts = WeatherSettings.text(settings, Locations.AUTO_PENDING)?.split(',', limit = 3) ?: return null
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Place(lat, lon, parts.getOrNull(2).orEmpty())
    }
}
