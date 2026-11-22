package com.echobedside.feature.home.commute

import com.echobedside.core.model.CalendarProvider
import com.echobedside.core.model.HaException
import com.echobedside.core.model.HomeAssistant
import com.echobedside.core.model.SettingsStore
import com.echobedside.core.model.WeatherProvider
import com.echobedside.feature.home.HomeSettings
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the Commute screen shows: a plan, or why there isn't one. */
sealed interface CommuteState {
    data class Ready(val ui: CommuteUi) : CommuteState
    data class Empty(val title: String, val detail: String) : CommuteState
}

/**
 * Builds the commute while the Commute screen is visible ([start]/[stop]):
 *  - destination: the next calendar event with a location within `home.commute.eventHours`,
 *    else the default trip (`home.commute.arriveBy` on weekdays, travel-time sensor's destination);
 *  - drive time: the HA travel-time sensor (pushed by HA) for the default trip, and
 *    `waze_travel_time.get_travel_times` for routes + alternatives (every 5 min while shown);
 *  - rain chip from [weather], recomputed every minute so "Leave in N min" counts down.
 */
class CommuteController(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val calendar: CalendarProvider,
    private val weather: WeatherProvider,
    private val scope: CoroutineScope,
    private val demo: Boolean,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<CommuteState?>(null)
    val state: StateFlow<CommuteState?> = _state.asStateFlow()

    private var jobs = ArrayList<Job>()
    /** Waze routes per "origin→destination". */
    private val routes = HashMap<String, Pair<Long, List<Route>>>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            while (true) {
                recompute()
                val now = clock()
                delay(60_000L - now % 60_000L + 50)
            }
        }
        if (demo) return
        // Only the travel-time sensor matters, not every state change in the house.
        jobs += scope.launch { ha.entities.map { m -> sensorId()?.let { m[it] } }.distinctUntilChanged { a, b -> a === b }.collect { recompute() } }
        jobs += scope.launch { calendar.events.collect { recompute() } }
        jobs += scope.launch { while (true) { refreshRoutes(); delay(5 * 60_000L) } }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear() }

    private fun int(key: String, def: Int) = settings.getString(key, "").toIntOrNull() ?: def

    private fun sensorId(): String? = HomeSettings.string(settings, HomeSettings.COMMUTE_SENSOR)
        ?: ha.entities.value.keys.filter { it.startsWith("sensor.") && (ha.entities.value[it]?.attributes?.has("route") == true ||
            ha.entities.value[it]?.attributes?.has("duration_in_traffic") == true) }.minOrNull()

    private fun trip(now: Long) = CommuteMath.nextEventTrip(calendar.events.value, now, int(HomeSettings.COMMUTE_EVENT_HOURS, 4))
        ?: CommuteMath.defaultTrip(HomeSettings.string(settings, HomeSettings.COMMUTE_LABEL) ?: "work",
            HomeSettings.string(settings, HomeSettings.COMMUTE_ARRIVE_BY), now, zone())

    /** "zone.home" / "person.maya" → "lat,lon"; anything else is passed through as an address. */
    private fun place(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val e = ha.entities.value[s] ?: return s
        val lat = e.attributes.optDouble("latitude"); val lon = e.attributes.optDouble("longitude")
        return if (lat.isNaN() || lon.isNaN()) s else "$lat,$lon"
    }

    private fun routeKey(trip: com.echobedside.feature.home.commute.Trip): Pair<String, String>? {
        val origin = place(HomeSettings.string(settings, HomeSettings.COMMUTE_ORIGIN) ?: "zone.home") ?: return null
        val dest = place(trip.destination ?: sensorId()?.let { ha.entities.value[it] }?.let(CommuteMath::travel)?.destination) ?: return null
        return origin to dest
    }

    private suspend fun refreshRoutes() {
        val t = trip(clock()) ?: return
        val (origin, dest) = routeKey(t) ?: return
        try {
            val resp = ha.callService("waze_travel_time", "get_travel_times", null, JSONObject()
                .put("origin", origin).put("destination", dest)
                .put("region", HomeSettings.string(settings, HomeSettings.COMMUTE_REGION) ?: "us"), returnResponse = true)
            routes["$origin→$dest"] = clock() to CommuteMath.wazeRoutes(resp)
            recompute()
        } catch (_: HaException) {
            // No Waze integration (or offline): the sensor alone still works for the default trip.
        }
    }

    private fun recompute() {
        val now = clock()
        if (demo) { _state.value = CommuteState.Ready(CommuteMath.demo(now)); return }
        val t = trip(now) ?: run {
            _state.value = CommuteState.Empty("No trips coming up", "Events with a location show here a few hours ahead")
            return
        }
        val key = routeKey(t)?.let { "${it.first}→${it.second}" }
        val waze = key?.let { routes[it]?.second }.orEmpty()
        val sensor = if (t.event == null) sensorId()?.let { ha.entities.value[it] }?.let(CommuteMath::travel) else null
        val main = when {
            sensor != null -> Route(sensor.route ?: waze.firstOrNull()?.name ?: "", sensor.minutes)
            waze.isNotEmpty() -> waze.first()
            else -> {
                _state.value = CommuteState.Empty(t.label.lowercase().replaceFirstChar { it.uppercase() },
                    if (t.event == null) "Add a travel-time sensor (Waze) in Home Assistant" else "Checking traffic…")
                return
            }
        }
        val baselineKey = HomeSettings.commuteBaselineKey(sensorId().takeIf { t.event == null } ?: (key ?: "trip"))
        val baseline = learnBaseline(baselineKey, main.minutes, now)
        val delay = CommuteMath.delayMin(sensor ?: Travel(main.minutes, null, main.name, null, null), baseline)
        _state.value = CommuteState.Ready(CommuteMath.build(t, main, waze, delay, weather.weather.value?.hourly.orEmpty(),
            int(HomeSettings.COMMUTE_BUFFER_MIN, 5), now, zone()))
    }

    /** The lowest drive time seen in the last 14 days stands in for free-flow. Stored as "min|epochMillis". */
    private fun learnBaseline(key: String, minutes: Int, now: Long): Int {
        val raw = settings.getString(key, "")
        val min = raw.substringBefore('|').toIntOrNull()
        val at = raw.substringAfter('|', "").toLongOrNull() ?: 0L
        if (min == null || minutes < min || now - at > 14 * 86_400_000L || (minutes == min && now - at > 86_400_000L)) {
            settings.putString(key, "$minutes|$now")
            return minutes
        }
        return min
    }
}
