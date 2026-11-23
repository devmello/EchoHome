package app.vesper.feature.home.alerts

import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.IslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Nowcast
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Settings of the alerts and the morning brief (declared in [app.vesper.feature.home.HomeSettingsSchema]). */
object AlertSettings {
    /** "Rain in 20 min" on the island (default on). */
    const val RAIN = "weather.rainAlert"
    /** Persisted so a restart doesn't repeat the alert: millis of the last "rain soon" publish. */
    const val RAIN_LAST = "weather.rainAlert.lastAt"

    /** "Leave now" reminders for events with a place (default on). */
    const val LEAVE = "leave.enabled"
    /** Minutes before the leave time that the reminder appears (default 15). */
    const val LEAVE_LEAD = "leave.leadMin"

    /** The once-a-morning brief (default on). */
    const val BRIEF = "brief.enabled"
    /** Persisted: the date (yyyy-MM-dd) the brief was last shown, and when the Echo last saw somebody. */
    const val BRIEF_LAST_DAY = "brief.lastDay"
    const val BRIEF_LAST_PRESENT = "brief.lastPresentAt"

    fun long(s: SettingsStore, key: String): Long = try { s.getString(key, "").trim().toLongOrNull() ?: 0L } catch (_: ClassCastException) { 0L }
    fun bool(s: SettingsStore, key: String, def: Boolean): Boolean = try { s.getBoolean(key, def) } catch (_: ClassCastException) { def }
    fun int(s: SettingsStore, key: String, def: Int): Int = try { s.getInt(key, def) } catch (_: ClassCastException) {
        try { s.getString(key, "").trim().toIntOrNull() ?: def } catch (_: ClassCastException) { def }
    }
}

/**
 * When to say "Rain in 20 min" on the island (pure, unit-tested). Rules:
 *  - only while it isn't raining now (the nowcast step containing "now" is dry);
 *  - the first wet step starts within [SOON_MIN] minutes;
 *  - never again within [COOLDOWN_MS] of the last time it was shown;
 *  - while shown, the minutes follow the forecast; it ends when the rain starts, or the forecast
 *    drops it (no rain, or later than [DROP_MIN]: a little hysteresis so a shifting forecast doesn't flap).
 */
object RainRule {
    const val SOON_MIN = 30
    const val DROP_MIN = 40
    const val COOLDOWN_MS = 2 * 3_600_000L
    /** ≥ this per 15 minutes is "heavy". */
    const val HEAVY_MM = 2.5f
    const val WET_MM = 0.1f

    sealed interface Decision {
        data object None : Decision
        data class Publish(val minutes: Int, val heavy: Boolean) : Decision
        data class Update(val minutes: Int, val heavy: Boolean) : Decision
        data object End : Decision
    }

    /** Rain falling in the step that contains [now]. */
    fun rainingNow(n: Nowcast, now: Long): Boolean {
        val step = n.stepMinutes * 60_000L
        return n.precipMm.withIndex().any { (i, mm) -> mm >= WET_MM && now >= n.startMillis + i * step && now < n.startMillis + (i + 1) * step }
    }

    /** The first wet step that hasn't ended yet. */
    private fun firstWet(n: Nowcast, now: Long): Float? {
        val step = n.stepMinutes * 60_000L
        return n.precipMm.withIndex().firstOrNull { (i, mm) -> mm >= WET_MM && n.startMillis + (i + 1) * step > now }?.value
    }

    fun decide(nowcast: Nowcast?, now: Long, enabled: Boolean, active: Boolean, lastPublishedAt: Long): Decision {
        if (!enabled || nowcast == null) return if (active) Decision.End else Decision.None
        if (rainingNow(nowcast, now)) return if (active) Decision.End else Decision.None
        val m = nowcast.minutesUntilRain(now, WET_MM) ?: return if (active) Decision.End else Decision.None
        val minutes = m.coerceAtLeast(1)
        val heavy = (firstWet(nowcast, now) ?: 0f) >= HEAVY_MM
        if (active) return if (m > DROP_MIN) Decision.End else Decision.Update(minutes, heavy)
        if (m > SOON_MIN) return Decision.None
        if (lastPublishedAt > 0 && now - lastPublishedAt in 0 until COOLDOWN_MS) return Decision.None
        return Decision.Publish(minutes, heavy)
    }
}

/**
 * Publishes `LiveContent.RainSoon` on the island from the weather's nowcast (`services.weather`).
 * Follows the weather flow (the provider refreshes the nowcast about every 12 min) and the
 * [AlertSettings.RAIN] setting; while the alert is showing, a once-a-minute tick keeps the minutes
 * right (and only then: nothing ticks while there is no alert). Logs `VesperRain` lines.
 */
class RainAlertWatcher(
    private val settings: SettingsStore,
    private val weather: StateFlow<Weather?>,
    private val island: IslandManager,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private var active = false
    private var ticker: Job? = null
    private var started = false
    /** Debug: stands in for the real nowcast (the `rain_demo` launch extra). */
    @Volatile var demoNowcast: Nowcast? = null
        set(v) { field = v; scope.launch { evaluate() } }

    fun start() {
        if (started) return
        started = true
        scope.launch { weather.collect { evaluate() } }
        scope.launch { settings.changes.filter { it == AlertSettings.RAIN }.collect { evaluate() } }
    }

    fun evaluate() {
        val now = clock()
        val nowcast = demoNowcast ?: weather.value?.nowcast
        val last = AlertSettings.long(settings, AlertSettings.RAIN_LAST)
        when (val d = RainRule.decide(nowcast, now, AlertSettings.bool(settings, AlertSettings.RAIN, true), active, last)) {
            RainRule.Decision.None -> {}
            is RainRule.Decision.Publish -> {
                active = true
                if (demoNowcast == null) settings.putString(AlertSettings.RAIN_LAST, now.toString())
                island.publish(activity(d.minutes, d.heavy))
                Log.i(TAG, "rain soon: published, ${d.minutes} min${if (d.heavy) ", heavy" else ""}")
                startTicker()
            }
            is RainRule.Decision.Update -> {
                Log.i(TAG, "rain soon: now ${d.minutes} min")
                island.update(ID) { a -> a.copy(content = LiveContent.RainSoon(d.minutes, d.heavy), staleAfterElapsed = elapsed() + STALE_MS) }
            }
            RainRule.Decision.End -> { active = false; ticker?.cancel(); ticker = null; island.end(ID); Log.i(TAG, "rain soon: ended") }
        }
    }

    private fun activity(minutes: Int, heavy: Boolean) = LiveActivity(
        id = ID, kind = ActivityKind.WEATHER_ALERT, content = LiveContent.RainSoon(minutes, heavy),
        // A safety net: if this process stops evaluating, the island drops it by itself.
        staleAfterElapsed = elapsed() + STALE_MS,
    )

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(60_000L - clock() % 60_000L + 50)
                evaluate()
            }
        }
    }

    companion object {
        const val ID = "weather-rain-soon"
        const val TAG = "VesperRain"
        private const val STALE_MS = 15 * 60_000L
    }
}
