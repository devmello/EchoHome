package com.echobedside.feature.clock

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.NightMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives [com.echobedside.core.model.SystemState.nightActive] and `ambientLux` (owned by
 * `:feature:clock`). One per process, created in [ClockFeature.install].
 *
 *  - Night mode (`SystemState.nightMode`, set by edit mode, Control Center or the settings page,
 *    persisted as `clock.night.mode`): ON / OFF force it; AUTO follows the light sensor through
 *    [AutoNight] (default: enter < 3 lux for 30 s, leave > 10 lux; `clock.night.*` settings).
 *  - The light sensor (`Sensor.TYPE_LIGHT`, on-change) is registered only while StandBy is
 *    shown ([start] / [stop]); a mode change is applied at any time.
 */
class NightController(context: Context, private val services: EchoServices) : SensorEventListener {
    private val system = services.system
    private val settings = services.settings
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private val auto = AutoNight()
    private var listening = false
    private var lastPublishedLux: Float? = null

    private val check = Runnable { auto.onTick(SystemClock.elapsedRealtime()); apply(); schedule() }

    val hasSensor: Boolean get() = light != null

    init {
        readThresholds()
        services.scope.launch {
            // after install: apply the saved mode, then keep the setting and the state in step
            settingMode()?.let { if (it != system.state.value.nightMode) services.systemControls.setNightMode(it) }
            launch {
                system.state.map { it.nightMode }.distinctUntilChanged().collect { m ->
                    val v = m.name.lowercase()
                    if (settings.getString(ClockSettings.KEY_NIGHT_MODE, "auto") != v) settings.putString(ClockSettings.KEY_NIGHT_MODE, v)
                    apply()
                }
            }
            settings.changes.collect { k ->
                when (k) {
                    ClockSettings.KEY_NIGHT_MODE -> settingMode()?.let { if (it != system.state.value.nightMode) services.systemControls.setNightMode(it) }
                    ClockSettings.KEY_NIGHT_ENTER_LUX, ClockSettings.KEY_NIGHT_LEAVE_LUX, ClockSettings.KEY_NIGHT_DELAY_SEC -> {
                        readThresholds()
                        apply(); if (listening) schedule()
                    }
                }
            }
        }
    }

    private fun settingMode(): NightMode? =
        settings.getString(ClockSettings.KEY_NIGHT_MODE, "auto").let { v -> NightMode.entries.firstOrNull { it.name.equals(v, true) } }

    private fun readThresholds() {
        val enter = settings.getFloat(ClockSettings.KEY_NIGHT_ENTER_LUX, 3f).coerceAtLeast(0f)
        auto.enterLux = enter
        auto.leaveLux = settings.getFloat(ClockSettings.KEY_NIGHT_LEAVE_LUX, 10f).coerceAtLeast(enter + 1f)
        auto.enterDelayMs = settings.getInt(ClockSettings.KEY_NIGHT_DELAY_SEC, 30).coerceIn(0, 3600) * 1000L
    }

    fun start() {
        if (listening) return
        val s = light ?: run { Log.w(TAG, "no light sensor"); return }
        listening = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, handler)
        schedule()
    }

    fun stop() {
        if (listening) sensors.unregisterListener(this)
        listening = false
        handler.removeCallbacks(check)
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
        val active = AutoNight.resolve(st.nightMode, auto.active)
        if (st.nightActive != active) {
            Log.i(TAG, "night ${if (active) "on" else "off"} (mode ${st.nightMode}, lux ${st.ambientLux})")
            system.update { it.copy(nightActive = active) }
        }
    }

    companion object { private const val TAG = "EchoClock" }
}
