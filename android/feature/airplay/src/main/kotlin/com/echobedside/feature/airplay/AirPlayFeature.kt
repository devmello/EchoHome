package com.echobedside.feature.airplay

import android.util.Log
import com.echobedside.core.model.DeviceName
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.SettingsStore
import kotlinx.coroutines.launch

/**
 * `:feature:airplay` (agent C — AirPlay audio).
 *
 * An audio-only AirPlay receiver (named after the device, "Echo Show", NOTES §6) built on the `shairplay` Rust crate
 * over JNI. [AirPlayService] runs it as a foreground service, publishes into `services.media`
 * (source id "airplay", [com.echobedside.core.model.MediaSourceKind.AIRPLAY]) and shows an
 * AIRPLAY_CONNECTED live activity while a sender is connected. No screens of its own: Now Playing
 * and the music island (`:feature:media`, `:feature:system`) read the hub.
 *
 * Settings (services.settings): [KEY_ENABLED] and [KEY_CC_AIRPLAY] start / stop the service;
 * [KEY_NAME], [KEY_MODE], [KEY_MDNS] restart the receiver.
 */
object AirPlayFeature : EchoFeature {
    override val id = "airplay"

    const val KEY_ENABLED = "airplay.enabled"
    /** Control Center's AirPlay toggle (written by `:feature:system`); off stops the receiver like [KEY_ENABLED]. */
    const val KEY_CC_AIRPLAY = "system.cc.airplay"
    const val KEY_NAME = "airplay.name"
    /** [MODE_AP1] (default: remote control works, user decision Nov 22, 2026) or [MODE_AP2]. */
    const val KEY_MODE = "airplay.mode"
    /** [MDNS_NSD] (default, Android's daemon) or [MDNS_LIB] (the library's responder). */
    const val KEY_MDNS = "airplay.mdns"
    internal const val KEY_HWADDR = "airplay.hwaddr"

    /** Empty [KEY_NAME] = the device name ([DeviceName], "Echo Show" by default). */
    const val DEFAULT_NAME = DeviceName.DEFAULT
    const val MODE_AP2 = "ap2"
    const val MODE_AP1 = "ap1"
    const val MDNS_NSD = "nsd"
    const val MDNS_LIB = "lib"
    internal val RESTART_KEYS = setOf(KEY_NAME, KEY_MODE, KEY_MDNS, DeviceName.KEY)

    /** The process's services, for [AirPlayService] (same process, started from [install]). */
    @Volatile internal var services: EchoServices? = null
        private set

    /** The receiver runs only while both are on: our own setting and Control Center's AirPlay toggle. */
    internal fun enabled(settings: SettingsStore): Boolean =
        settings.getBoolean(KEY_ENABLED, true) && settings.getBoolean(KEY_CC_AIRPLAY, true)

    override fun install(host: FeatureHost) {
        val s = host.services
        services = s
        val context = host.context
        fun apply(on: Boolean) {
            try {
                if (on) AirPlayService.start(context) else AirPlayService.stop(context)
            } catch (e: Exception) {
                Log.e("EchoAirPlay", "couldn't ${if (on) "start" else "stop"} the AirPlay service", e)
            }
        }
        var on = enabled(s.settings)
        if (on) apply(true)
        s.scope.launch {
            s.settings.changes.collect { key ->
                if (key == KEY_ENABLED || key == KEY_CC_AIRPLAY) {
                    val now = enabled(s.settings)
                    if (now != on) { on = now; Log.i("EchoAirPlay", "AirPlay ${if (now) "on" else "off"} ($key)"); apply(now) }
                }
            }
        }
    }
}
