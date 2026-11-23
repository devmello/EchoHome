package app.vesper.feature.home.commute

import app.vesper.core.model.HomeLocation
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.TravelEstimator
import app.vesper.feature.home.glance.WeatherSettings
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException

/**
 * `services.travel`: door-to-door driving minutes from home, using the commute's routing
 * ([Geocoder] = Nominatim, [Router] = OSRM; no key, no live traffic, so these are typical times).
 *
 *  - The destination is free text (an event's location). It's geocoded once (Geocoder caches hits
 *    and misses forever) and then routed.
 *  - Results are cached per (home, destination) for [ttlMs] (default 3 h: free-flow times don't
 *    change); failures are cached [failTtlMs] (5 min) so a flaky network isn't hammered.
 *  - Requests are serialised and at least [gapMs] apart (OSRM's demo server is for light use).
 *  - Anything wrong (no destination, not found, no route, no network) gives null.
 */
class HomeTravelEstimator(
    private val settings: SettingsStore,
    private val location: StateFlow<HomeLocation>,
    private val geocoder: Geocoder = Geocoder.shared(settings),
    private val router: Router = Router(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 3 * 3_600_000L,
    private val failTtlMs: Long = 5 * 60_000L,
    private val gapMs: Long = 1_000L,
) : TravelEstimator {
    private data class Entry(val at: Long, val minutes: Int?)

    private val cache = HashMap<String, Entry>()
    private val lock = Mutex()
    private var lastRequestAt = 0L

    override suspend fun minutesTo(destination: String, departAtMillis: Long): Int? {
        val place = destination.trim().replace(Regex("\\s+"), " ")
        if (place.isEmpty() || CommuteMath.looksVirtual(place)) return null
        val home = CommuteHome.resolve(settings, location, allowDemo = true) ?: return null
        val key = "${"%.3f".format(java.util.Locale.US, home.lat)},${"%.3f".format(java.util.Locale.US, home.lon)}>" + place.lowercase()
        return lock.withLock {
            val now = clock()
            cache[key]?.let { e -> if (now - e.at < (if (e.minutes == null) failTtlMs else ttlMs)) return@withLock e.minutes }
            val wait = lastRequestAt + gapMs - now
            if (wait > 0) delay(wait)
            lastRequestAt = clock()
            val minutes = try {
                val dest = geocoder.geocode(place)
                if (dest == null) null else router.routes(home, dest).firstOrNull()?.minutes
            } catch (_: IOException) { null } catch (_: JSONException) { null }
            cache[key] = Entry(clock(), minutes)
            minutes
        }
    }
}

/** Where trips start. */
object CommuteHome {
    /**
     * `commute.home` ("lat,lon"), the older `commute.lat`/`lon`, else `services.location` (manual >
     * IP-detected > San Francisco). The DEMO location counts only when [allowDemo] (the commute screen shows
     * its own sample then; routing for travel estimates can use Santa Cruz like any other place).
     */
    fun resolve(settings: SettingsStore, location: StateFlow<HomeLocation>, allowDemo: Boolean = false): LatLon? {
        WeatherSettings.latLon(WeatherSettings.text(settings, CommuteSettings.HOME))?.let { (lat, lon) -> return LatLon(lat, lon) }
        val lat = WeatherSettings.number(settings, CommuteSettings.LAT)
        val lon = WeatherSettings.number(settings, CommuteSettings.LON)
        if (lat != null && lon != null) return LatLon(lat, lon)
        return location.value.takeIf { allowDemo || it.source != HomeLocation.Source.DEMO }?.let { LatLon(it.lat, it.lon) }
    }
}
