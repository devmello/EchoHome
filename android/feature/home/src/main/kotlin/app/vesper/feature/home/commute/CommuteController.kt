package app.vesper.feature.home.commute

import app.vesper.core.model.CalendarProvider
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.WeatherProvider
import app.vesper.core.model.HomeLocation
import app.vesper.feature.home.glance.WeatherSettings
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONException

/** What the Commute screen shows: a plan, or why there isn't one. */
sealed interface CommuteState {
    data class Ready(val ui: CommuteUi) : CommuteState
    data class Empty(val title: String, val detail: String) : CommuteState
}

/** Settings for the commute (all optional). */
object CommuteSettings {
    /** Where to go: "auto" (next calendar event with a place, else [DESTINATION]), "event" or "fixed". */
    const val SOURCE = "commute.source"
    /** Start point, LOCATION ("lat,lon"); default: the home location (manual > IP-detected > Livermore). */
    const val HOME = "commute.home"
    /** Older start-point keys, still read. */
    const val LAT = "commute.lat"
    const val LON = "commute.lon"
    /** Use the next calendar event with a location within this many hours (default 4). */
    const val EVENT_HOURS = "commute.eventHours"
    /** Minutes to arrive early (default 5). */
    const val BUFFER_MIN = "commute.bufferMin"
    /** Optional weekday trip: place text, arrive-by "HH:mm", header label ("work" → "TO WORK"). */
    const val DESTINATION = "commute.destination"
    const val ARRIVE_BY = "commute.arriveBy"
    const val LABEL = "commute.label"
}

/**
 * Builds the commute while the Commute screen is visible ([start]/[stop]):
 * next calendar event with a location (or the weekday default) → Nominatim geocode (cached) →
 * OSRM routes from home (cached 30 min per destination) → "Leave in N min", recomputed each minute.
 * No Home Assistant. No traffic chip: no free keyless source has live traffic.
 */
class CommuteController(
    private val settings: SettingsStore,
    private val calendar: CalendarProvider,
    private val weather: WeatherProvider,
    private val scope: CoroutineScope,
    private val demo: Boolean,
    private val location: StateFlow<HomeLocation>,
    private val geocoder: Geocoder = Geocoder(settings),
    private val router: Router = Router(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val routeTtlMs: Long = 30 * 60_000L,
) {
    private val _state = MutableStateFlow<CommuteState?>(null)
    val state: StateFlow<CommuteState?> = _state.asStateFlow()
    private var jobs = ArrayList<Job>()
    private val cache = HashMap<String, Pair<Long, List<Route>>>()
    private var fetching: Job? = null
    private var failedAt = HashMap<String, Long>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            while (true) {
                recompute()
                delay(60_000L - clock() % 60_000L + 50)
            }
        }
        if (!demo) jobs += scope.launch { calendar.events.collect { recompute() } }
        jobs += scope.launch { location.drop(1).collect { recompute() } }
        // Settings apply live: a new destination or start point recomputes (routes are cached per pair).
        jobs += scope.launch { settings.changes.filter { it.startsWith("commute.") && !it.startsWith("commute.geo.") }.collect { recompute() } }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear(); fetching?.cancel(); fetching = null }

    private fun int(key: String, def: Int) = WeatherSettings.number(settings, key)?.toInt() ?: def

    /**
     * `commute.lat`/`lon`, else `services.location` (manual > IP-detected > Livermore). Null while that
     * is the DEMO location (demo data, nothing manual): the screen shows the design's sample.
     */
    internal fun home(): LatLon? {
        WeatherSettings.latLon(WeatherSettings.text(settings, CommuteSettings.HOME))?.let { (lat, lon) -> return LatLon(lat, lon) }
        val lat = WeatherSettings.number(settings, CommuteSettings.LAT)
        val lon = WeatherSettings.number(settings, CommuteSettings.LON)
        if (lat != null && lon != null) return LatLon(lat, lon)
        return location.value.takeIf { it.source != HomeLocation.Source.DEMO }?.let { LatLon(it.lat, it.lon) }
    }

    fun recompute() {
        val now = clock()
        val home = home() ?: run { _state.value = CommuteState.Ready(CommuteMath.demo(now)); return } // demo only
        val source = WeatherSettings.text(settings, CommuteSettings.SOURCE) ?: "auto"
        val fromCalendar = if (source == "fixed") null else CommuteMath.nextEventTrip(calendar.events.value, now, int(CommuteSettings.EVENT_HOURS, 4))
        val fixed = if (source == "event") null else CommuteMath.defaultTrip(WeatherSettings.text(settings, CommuteSettings.LABEL) ?: "work",
            WeatherSettings.text(settings, CommuteSettings.DESTINATION), WeatherSettings.text(settings, CommuteSettings.ARRIVE_BY) ?: "09:00", now, zone())
        val trip = fromCalendar ?: fixed ?: run {
            _state.value = when {
                source == "fixed" -> CommuteState.Empty("No destination set", "Set one in Settings › Commute")
                else -> CommuteState.Empty("No trips coming up", "Calendar events with a place show here a few hours ahead")
            }
            return
        }
        val key = "${home.lat},${home.lon}>" + trip.destination.lowercase() // a new home means new routes
        val cached = cache[key]
        if (cached == null || now - cached.first > routeTtlMs) fetch(key, trip.destination, home)
        val routes = cached?.second
        _state.value = when {
            routes != null && routes.isNotEmpty() -> CommuteState.Ready(CommuteMath.build(trip, routes, weather.weather.value?.hourly.orEmpty(),
                int(CommuteSettings.BUFFER_MIN, 5), now, zone()))
            routes != null -> CommuteState.Empty(title(trip), "No driving route to ${trip.destination}")
            failedAt[key] != null -> CommuteState.Empty(title(trip), "Couldn’t find a route right now")
            else -> CommuteState.Empty(title(trip), "Finding the route…")
        }
    }

    private fun title(t: Trip) = t.label.removePrefix("TO ").lowercase().replaceFirstChar { it.uppercase() }

    private fun fetch(key: String, place: String, home: LatLon) {
        if (fetching?.isActive == true) return
        // After a failure, wait 5 minutes before asking again.
        failedAt[key]?.let { if (clock() - it < 5 * 60_000L) return }
        fetching = scope.launch {
            try {
                val dest = geocoder.geocode(place)
                cache[key] = clock() to (if (dest == null) emptyList() else router.routes(home, dest))
                failedAt.remove(key)
            } catch (_: IOException) { failedAt[key] = clock() } catch (_: JSONException) { failedAt[key] = clock() }
            recompute()
        }
    }
}
