package com.echobedside.feature.alarm

import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:alarm` (agent E — Alarms + timers).
 *
 * Exact alarms surviving reboot, sunrise ramp, Ring/Snooze/Stop + Good morning (Alarm.dc.html), Timers (Timers.dc.html), island activities. Binds services.alarms and services.timers.
 *
 * Register screens/overlays/key handlers and bind owned services in [install]; see EchoFeature.
 */
object AlarmFeature : EchoFeature {
    override val id = "alarm"

    override fun install(host: FeatureHost) {
        // Not built yet.
    }
}
