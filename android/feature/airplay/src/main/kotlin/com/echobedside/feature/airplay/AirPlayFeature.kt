package com.echobedside.feature.airplay

import android.util.Log
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FeatureHost

/**
 * `:feature:airplay` (agent C — AirPlay audio).
 *
 * An audio-only AirPlay receiver ("Echo Bedside", NOTES §6) built on the `shairplay` Rust crate
 * over JNI. [AirPlayService] runs it as a foreground service, publishes into `services.media`
 * (source id "airplay", [com.echobedside.core.model.MediaSourceKind.AIRPLAY]) and shows an
 * AIRPLAY_CONNECTED live activity while a sender is connected. No screens of its own: Now Playing
 * and the music island (`:feature:media`, `:feature:system`) read the hub.
 *
 * Settings (services.settings): [KEY_ENABLED], [KEY_NAME], [KEY_MODE], [KEY_MDNS]; changing one
 * restarts the receiver.
 */
object AirPlayFeature : EchoFeature {
    override val id = "airplay"

    const val KEY_ENABLED = "airplay.enabled"
    const val KEY_NAME = "airplay.name"
    /** [MODE_AP2] (default) or [MODE_AP1]. */
    const val KEY_MODE = "airplay.mode"
    /** [MDNS_NSD] (default, Android's daemon) or [MDNS_LIB] (the library's responder). */
    const val KEY_MDNS = "airplay.mdns"
    internal const val KEY_HWADDR = "airplay.hwaddr"

    const val DEFAULT_NAME = "Echo Bedside"
    const val MODE_AP2 = "ap2"
    const val MODE_AP1 = "ap1"
    const val MDNS_NSD = "nsd"
    const val MDNS_LIB = "lib"
    internal val RESTART_KEYS = setOf(KEY_NAME, KEY_MODE, KEY_MDNS)

    /** The process's services, for [AirPlayService] (same process, started from [install]). */
    @Volatile internal var services: EchoServices? = null
        private set

    override fun install(host: FeatureHost) {
        services = host.services
        if (host.services.settings.getBoolean(KEY_ENABLED, true)) {
            try {
                AirPlayService.start(host.context)
            } catch (e: Exception) {
                Log.e("EchoAirPlay", "couldn't start the AirPlay service", e)
            }
        }
    }
}
