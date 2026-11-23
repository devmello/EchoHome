package app.vesper.feature.clock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.VesperServices
import app.vesper.core.model.NightMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives [app.vesper.core.model.SystemState.nightActive] and `ambientLux` (owned by
 * `:feature:clock`). One per process, created in [ClockFeature.install].
 *
 *  - Night mode setting `clock.night.mode`: `auto` (light sensor), `schedule` (by the clock,
 *    `clock.night.start` → `clock.night.end`), `on`, `off`. In `SystemState.nightMode` both
 *    automatic sources are AUTO; edit mode, Control Center and the settings page stay in step.
 *    A recognised person may have their own mode ([app.vesper.core.model.Profile.nightMode]): it
 *    applies while they're active, and Night changes made then go into it.
 *  - Light: [AutoNight] (default enter < 3 lux for 30 s, leave > 10 lux; `clock.night.*Lux`,
 *    `delaySec`). The sensor (`Sensor.TYPE_LIGHT`, on-change) is registered only while StandBy is
 *    shown ([start] / [stop]) and only for the `auto` source.
 *  - Schedule: one timer to the next start/end (re-checked at least every 5 minutes, so clock
 *    changes are caught), running whether or not StandBy is shown.
 */
class NightController(context: Context, private val services: VesperServices) : SensorEventListener {
    enum class Source { LIGHT, SCHEDULE }

    private val system = services.system
    private val settings = services.settings
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private val auto = AutoNight()
    private var wanted = false // StandBy is shown
    private var listening = false
    private var lastPublishedLux: Float? = null
    private var source = Source.LIGHT
    private var nightSchedule = NightSchedule(22 * 60, 7 * 60)

    private val check = Runnable { auto.onTick(SystemClock.elapsedRealtime()); apply(); schedule() }
    private val scheduleTick = Runnable { apply(); planScheduleTick() }

    val hasSensor: Boolean get() = light != null

    init {
        readSettings()
        services.scope.launch {
            // after install: apply the saved mode, then keep the setting and the state in step
            applySettingMode()
            sourceChanged()
            launch {
                system.state.map { it.nightMode }.distinctUntilChanged().collect { m ->
                    val saved = modeSetting()
                    val v = when (m) {
                        NightMode.ON -> "on"
                        NightMode.OFF -> "off"
                        NightMode.AUTO -> if (saved == "schedule") "schedule" else "auto" // AUTO keeps its source
                    }
                    if (saved != v) storeMode(v)
                    apply()
                }
            }
            launch {
                // A recognised person with their own Night mode (Profile.nightMode): switch to it, and back when they leave.
                combine(services.profiles.active, services.profiles.profiles) { a, l -> PersonPrefs.person(a, l)?.nightMode }
                    .distinctUntilChanged().drop(1).collect { readSettings(); applySettingMode(); sourceChanged() }
            }
            settings.changes.collect { k ->
                when (k) {
                    ClockSettings.KEY_NIGHT_MODE -> { readSettings(); applySettingMode(); sourceChanged() }
                    ClockSettings.KEY_NIGHT_START, ClockSettings.KEY_NIGHT_END -> { readSettings(); sourceChanged() }
                    ClockSettings.KEY_NIGHT_ENTER_LUX, ClockSettings.KEY_NIGHT_LEAVE_LUX, ClockSettings.KEY_NIGHT_DELAY_SEC -> {
                        readSettings()
                        apply(); if (listening) schedule()
                    }
                }
            }
        }
    }

    private fun person() = PersonPrefs.person(services.profiles.active.value, services.profiles.profiles.value)

    /** The Night mode in force: the recognised person's own ([Profile.nightMode]), else `clock.night.mode`. */
    private fun modeSetting(): String = PersonPrefs.nightMode(person(), settings.getString(ClockSettings.KEY_NIGHT_MODE, "auto"))

    /** A Night change (edit mode, Control Center): into the person's own choice if they have one, else the setting. */
    private fun storeMode(v: String) {
        val p = person()
        if (p?.nightMode != null) services.profiles.upsert(p.copy(nightMode = v))
        else settings.putString(ClockSettings.KEY_NIGHT_MODE, v)
    }

    private fun applySettingMode() {
        val m = when (modeSetting()) {
            "on" -> NightMode.ON
            "off" -> NightMode.OFF
            else -> NightMode.AUTO // auto, schedule
        }
        if (m != system.state.value.nightMode) services.systemControls.setNightMode(m)
    }

    private fun readSettings() {
        val enter = settings.getFloat(ClockSettings.KEY_NIGHT_ENTER_LUX, 3f).coerceAtLeast(0f)
        auto.enterLux = enter
        auto.leaveLux = settings.getFloat(ClockSettings.KEY_NIGHT_LEAVE_LUX, 10f).coerceAtLeast(enter + 1f)
        auto.enterDelayMs = settings.getInt(ClockSettings.KEY_NIGHT_DELAY_SEC, 30).coerceIn(0, 3600) * 1000L
        source = if (modeSetting() == "schedule") Source.SCHEDULE else Source.LIGHT
        nightSchedule = NightSchedule(
            NightSchedule.parse(settings.getString(ClockSettings.KEY_NIGHT_START, ClockSettings.DEFAULT_NIGHT_START)) ?: 22 * 60,
            NightSchedule.parse(settings.getString(ClockSettings.KEY_NIGHT_END, ClockSettings.DEFAULT_NIGHT_END)) ?: 7 * 60)
    }

    /** Registers the sensor or the schedule timer for the current source. */
    private fun sourceChanged() {
        if (source == Source.SCHEDULE) { unregister(); planScheduleTick() }
        else { handler.removeCallbacks(scheduleTick); if (wanted) register() }
        apply()
    }

    fun start() {
        wanted = true
        if (source == Source.LIGHT) register() else planScheduleTick()
    }

    fun stop() {
        wanted = false
        unregister()
    }

    private fun register() {
        if (listening) return
        val s = light ?: run { Log.w(TAG, "no light sensor"); return }
        listening = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        schedule()
    }

    private fun unregister() {
        if (listening) sensors.unregisterListener(this)
        listening = false
        handler.removeCallbacks(check)
    }

    private fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun planScheduleTick() {
        handler.removeCallbacks(scheduleTick)
        if (source != Source.SCHEDULE) return
        val c = Calendar.getInstance()
        val toNextMinute = 60_000L - (c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND))
        val minutes = nightSchedule.minutesToNextChange(minuteOfDay()).coerceAtMost(5)
        handler.postDelayed(scheduleTick, toNextMinute + (minutes - 1) * 60_000L + 200)
    }

    /** A reading (from the sensor, or injected by a debug launch extra). */
    fun onLux(lux: Float) {
        auto.onLux(lux, SystemClock.elapsedRealtime())
        val last = lastPublishedLux
        // Only publish meaningful changes so every SystemState observer isn't woken per reading.
        if (last == null || abs(lux - last) >= max(1f, last * 0.1f)) {
            lastPublishedLux = lux
            system.update { it.copy(ambientLux = lux) }
        }
        apply()
        schedule()
    }

    override fun onSensorChanged(event: SensorEvent) = onLux(event.values[0])

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun schedule() {
        handler.removeCallbacks(check)
        val at = auto.nextCheckAt() ?: return
        handler.postDelayed(check, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 50)
    }

    private fun apply() {
        val st = system.state.value
        val automatic = if (source == Source.SCHEDULE) nightSchedule.isNight(minuteOfDay()) else auto.active
        val active = AutoNight.resolve(st.nightMode, automatic)
        if (st.nightActive != active) {
            Log.i(TAG, "night ${if (active) "on" else "off"} (mode ${st.nightMode}, source $source, lux ${st.ambientLux})")
            system.update { it.copy(nightActive = active) }
        }
    }

    companion object { private const val TAG = "VesperClock" }
}
