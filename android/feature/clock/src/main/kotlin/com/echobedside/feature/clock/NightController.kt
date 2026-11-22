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
import com.echobedside.core.model.SystemStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Drives [com.echobedside.core.model.SystemState.nightActive] and `ambientLux` (owned by
 * `:feature:clock`). One per process, created in [ClockFeature.install].
 *
 *  - Night mode setting (`SystemState.nightMode`, set by edit mode or Control Center):
 *    ON / OFF force it; AUTO follows the light sensor through [AutoNight]
 *    (enter < 3 lux for 30 s, leave > 10 lux).
 *  - The light sensor (`Sensor.TYPE_LIGHT`, on-change) is registered only while StandBy is
 *    shown ([start] / [stop]); a mode change is applied at any time.
 */
class NightController(context: Context, private val system: SystemStateStore, scope: CoroutineScope) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val light: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private val auto = AutoNight()
    private var listening = false
    private var lastPublishedLux: Float? = null

    private val check = Runnable { auto.onTick(SystemClock.elapsedRealtime()); apply(); schedule() }

    val hasSensor: Boolean get() = light != null

    init {
        scope.launch { system.state.map { it.nightMode }.distinctUntilChanged().collect { apply() } }
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
