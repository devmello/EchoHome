package com.echobedside.feature.home.glance

import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.CalendarProvider
import com.echobedside.core.model.HaConnection
import com.echobedside.core.model.HaEntity
import com.echobedside.core.model.HaException
import com.echobedside.core.model.HomeAssistant
import com.echobedside.core.model.SettingsStore
import com.echobedside.core.model.Weather
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * [WeatherProvider] from a Home Assistant `weather.*` entity (NOTES §5.6).
 *
 * Current conditions come from the entity (pushed by HA's state_changed). Forecasts come from
 * `weather.get_forecasts` (hourly + daily, return_response): on connect, when the entity updates
 * (at most every 10 min) and every 30 min. Sunrise/sunset and day/night come from `sun.sun`.
 */
class HaWeatherProvider(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val minForecastIntervalMs: Long = 10 * 60_000L,
    private val periodMs: Long = 30 * 60_000L,
) : WeatherProvider {
    private val _weather = MutableStateFlow<Weather?>(null)
    override val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private var hourly: List<Forecast> = emptyList()
    private var daily: List<Forecast> = emptyList()
    private var lastFetch = 0L
    private var location: String? = null
    private val lock = Mutex()
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { ha.connection.filter { it == HaConnection.CONNECTED }.collect { fetch(force = true) } }
        scope.launch {
            // The entity (or sun.sun) changed: recompute now, refresh forecasts if they're stale.
            ha.entities.map { m -> entityId(m)?.let { m[it] } to m["sun.sun"] }.distinctUntilChanged { a, b -> a.first === b.first && a.second === b.second }
                .collect { (e, _) -> if (e != null) { publish(); fetch(force = false) } }
        }
        scope.launch { while (true) { delay(periodMs); fetch(force = true) } }
    }

    override fun refresh() { scope.launch { fetch(force = true) } }

    private fun entityId(m: Map<String, HaEntity> = ha.entities.value): String? =
        HomeSettings.string(settings, HomeSettings.WEATHER_ENTITY) ?: m.keys.filter { it.startsWith("weather.") }.minOrNull()

    private suspend fun fetch(force: Boolean) = lock.withLock {
        val id = entityId() ?: return@withLock
        val now = System.currentTimeMillis()
        // Connect + first entity update arrive together: one fetch is enough.
        if (now - lastFetch < (if (force) 30_000L else minForecastIntervalMs)) return@withLock
        lastFetch = now
        try {
            if (location == null) location = HomeSettings.string(settings, HomeSettings.WEATHER_LOCATION)
                ?: ha.command(JSONObject().put("type", "get_config")).optJSONObject("result")?.optString("location_name")
            hourly = WeatherParse.forecasts(ha.callService("weather", "get_forecasts", id, JSONObject().put("type", "hourly"), returnResponse = true), id)
            daily = WeatherParse.forecasts(ha.callService("weather", "get_forecasts", id, JSONObject().put("type", "daily"), returnResponse = true), id)
        } catch (_: HaException) {
            lastFetch = 0 // retry on the next trigger; keep showing what we had
        }
        publish()
    }

    private fun publish() {
        val m = ha.entities.value
        val e = entityId(m)?.let { m[it] } ?: return
        _weather.value = WeatherParse.weather(e, m["sun.sun"], hourly, daily, location.orEmpty())
    }
}

/**
 * [CalendarProvider] from Home Assistant `calendar.*` entities: `calendar.get_events` for today and
 * tomorrow across the configured calendars (default: all), one colour per calendar.
 * Refreshes on connect, every 15 min, at midnight, and when a calendar entity changes.
 */
class HaCalendarProvider(
    private val ha: HomeAssistant,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val periodMs: Long = 15 * 60_000L,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : CalendarProvider {
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    override val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()
    private val lock = Mutex()
    private var started = false
    private var debounce: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch { ha.connection.filter { it == HaConnection.CONNECTED }.collect { fetch() } }
        scope.launch {
            ha.entities.map { m -> calendars(m).map { m[it] } }
                .distinctUntilChanged { a, b -> a.size == b.size && a.indices.all { a[it] === b[it] } }
                .collect { debounce?.cancel(); debounce = scope.launch { delay(5000); fetch() } }
        }
        scope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val midnight = localDate(now, zone()).plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
                delay(minOf(periodMs, midnight - now + 1000))
                fetch()
            }
        }
    }

    override fun refresh() { scope.launch { fetch() } }

    fun calendars(m: Map<String, HaEntity> = ha.entities.value): List<String> =
        HomeSettings.list(settings, HomeSettings.CALENDARS) ?: m.keys.filter { it.startsWith("calendar.") }.sorted()

    private suspend fun fetch() = lock.withLock {
        val cals = calendars()
        if (cals.isEmpty()) { _events.value = emptyList(); return@withLock }
        val colors = cals.withIndex().associate { (i, id) ->
            id to (CalendarParse.parseColor(settings.getString(HomeSettings.calendarColorKey(id), "")) ?: CalendarParse.PALETTE[i % CalendarParse.PALETTE.size])
        }
        val names = cals.associateWith { ha.entities.value[it]?.friendlyName.orEmpty() }
        try {
            val resp = ha.callService("calendar", "get_events", cals.joinToString(","), CalendarParse.range(System.currentTimeMillis(), zone()), returnResponse = true)
            _events.value = CalendarParse.events(resp, colors, names, zone())
        } catch (_: HaException) {
            // Keep the last list; the next trigger retries.
        }
    }
}
