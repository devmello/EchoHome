package app.vesper.feature.airplay

import android.util.Log
import app.vesper.core.model.DeviceName
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import kotlinx.coroutines.launch

/**
 * `:feature:airplay` (agent C — AirPlay audio).
 *
 * An audio-only AirPlay receiver (named after the device, "Echo Show", NOTES §6) built on the `shairplay` Rust crate
 * over JNI. [AirPlayService] runs it as a foreground service, publishes into `services.media`
 * (source id "airplay", [app.vesper.core.model.MediaSourceKind.AIRPLAY]) and shows an
 * AIRPLAY_CONNECTED live activity while a sender is connected. No screens of its own: Now Playing
 * and the music island (`:feature:media`, `:feature:system`) read the hub.
 *
 * Settings (services.settings): [KEY_ENABLED] and [KEY_CC_AIRPLAY] start / stop the service;
 * [KEY_NAME], [KEY_MODE], [KEY_MDNS] restart the receiver.
 */
object AirPlayFeature : VesperFeature {
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
    @Volatile internal var services: VesperServices? = null
        private set

    /** The receiver runs only while both are on: our own setting and Control Center's AirPlay toggle. */
    internal fun enabled(settings: SettingsStore): Boolean =
        settings.getBoolean(KEY_ENABLED, true) && settings.getBoolean(KEY_CC_AIRPLAY, true)

    /** The AirPlay card on the web settings page. Every key here is applied live by [install] / [AirPlayService]. */
    internal fun settingsSection() = SettingsSection(
        id = "airplay",
        title = "AirPlay",
        order = 30,
        help = "Play audio from an iPhone, iPad or Mac on the Echo. Audio only, no screen mirroring.",
        settings = listOf(
            SettingSpec(
                KEY_ENABLED, "AirPlay receiver", SettingType.BOOLEAN, default = "true",
                help = "Show the Echo in AirPlay lists. Control Center's AirPlay switch also turns it off.",
            ),
            SettingSpec(
                KEY_NAME, "AirPlay name", SettingType.TEXT, default = "",
                help = "The name iPhones and Macs show. Leave blank to use the device name.",
            ),
            SettingSpec(
                KEY_MODE, "Mode", SettingType.CHOICE, default = MODE_AP1,
                help = "AirPlay lets the Echo pause, skip and change the volume on the phone. " +
                    "AirPlay 2 lists the Echo as an AirPlay 2 speaker, but the Echo can't control playback " +
                    "(pause only mutes the Echo). Neither keeps several speakers in sync.",
                options = listOf(MODE_AP1 to "AirPlay (remote control)", MODE_AP2 to "AirPlay 2 (no remote control)"),
            ),
            SettingSpec(
                KEY_MDNS, "Discovery (mDNS)", SettingType.CHOICE, default = MDNS_NSD, advanced = true,
                help = "How the Echo announces itself. Android's service (default) costs nothing while idle; " +
                    "the library's own responder is a fallback if iPhones can't find the Echo.",
                options = listOf(MDNS_NSD to "Android (NsdManager)", MDNS_LIB to "Built-in responder"),
            ),
        ),
    )

    override fun install(host: FeatureHost) {
        val s = host.services
        services = s
        host.registerSettings(settingsSection())
        val context = host.context
        fun apply(on: Boolean) {
            try {
                if (on) AirPlayService.start(context) else AirPlayService.stop(context)
            } catch (e: Exception) {
                Log.e("VesperAirPlay", "couldn't ${if (on) "start" else "stop"} the AirPlay service", e)
            }
        }
        var on = enabled(s.settings)
        if (on) apply(true)
        s.scope.launch {
            s.settings.changes.collect { key ->
                if (key == KEY_ENABLED || key == KEY_CC_AIRPLAY) {
                    val now = enabled(s.settings)
                    if (now != on) { on = now; Log.i("VesperAirPlay", "AirPlay ${if (now) "on" else "off"} ($key)"); apply(now) }
                }
            }
        }
    }
}
