package app.vesper.feature.spotify

import android.util.Log
import app.vesper.core.model.DeviceName
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.VesperServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * `:feature:spotify`: a Spotify Connect receiver (librespot over JNI).
 *
 * The Echo shows up in the Spotify app's device list under the device name ("Echo Show"); picking
 * it plays on the Echo. [SpotifyService] runs it as a foreground service and publishes into
 * `services.media` (source id "spotify", [app.vesper.core.model.MediaSourceKind.SPOTIFY]), so Now
 * Playing, the music island and Control Center show it with no code of their own. Spotify
 * Connect needs a Premium account; a free account gets a clear message (banner + settings page).
 *
 * Settings: [KEY_ENABLED] and [KEY_CC_SPOTIFY] (Control Center's tile) start / stop the service;
 * [RESTART_KEYS] restart the receiver.
 */
object SpotifyFeature : VesperFeature {
    override val id = "spotify"

    const val KEY_ENABLED = "spotify.enabled"
    /** Control Center's Spotify tile (written by `:feature:system`); off stops the receiver like [KEY_ENABLED]. */
    const val KEY_CC_SPOTIFY = "system.cc.spotify"
    /** Blank = the device name ([DeviceName], "Echo Show" by default). */
    const val KEY_NAME = "spotify.name"
    /** "96" or "160" (default) kbps. */
    const val KEY_BITRATE = "spotify.bitrate"
    /** [MDNS_NSD] (default, Android's daemon) or [MDNS_LIB] (librespot's libmdns responder). */
    const val KEY_MDNS = "spotify.mdns"
    const val KEY_SIGN_OUT = "spotify.signOut"
    internal const val KEY_DEVICE_ID = "spotify.deviceId"
    /** Unfinished native starts (see [CrashGuard]). */
    internal const val KEY_START_ATTEMPTS = "spotify.startAttempts"

    const val DEFAULT_BITRATE = 160
    const val MDNS_NSD = "nsd"
    const val MDNS_LIB = "lib"
    internal val RESTART_KEYS = setOf(KEY_NAME, KEY_BITRATE, KEY_MDNS, DeviceName.KEY)

    /** The process's services, for [SpotifyService] (same process, started from [install]). */
    @Volatile internal var services: VesperServices? = null
        private set

    private val _status = MutableStateFlow(SpotifyStatus.text(null, null, DeviceName.DEFAULT))
    /** The receiver's status line (settings page). Written by [SpotifyService]. */
    val status: StateFlow<String> = _status.asStateFlow()
    internal fun setStatus(text: String) { _status.value = text }

    /** Set by the running service: signs out and deletes the cached credentials. */
    @Volatile internal var signOut: (() -> Unit)? = null

    /** The receiver runs only while both are on: our own setting and Control Center's tile. */
    internal fun enabled(settings: SettingsStore): Boolean =
        settings.getBoolean(KEY_ENABLED, true) && settings.getBoolean(KEY_CC_SPOTIFY, true)

    internal fun name(settings: SettingsStore): String = settings.getString(KEY_NAME, "").ifBlank { DeviceName.get(settings) }

    internal fun bitrate(settings: SettingsStore): Int =
        if (settings.getString(KEY_BITRATE, DEFAULT_BITRATE.toString()) == "96") 96 else DEFAULT_BITRATE

    /** The Spotify card on the web settings page; every key here is applied live. */
    internal fun settingsSection(status: String) = SettingsSection(
        id = "spotify",
        title = "Spotify Connect",
        order = 31,
        help = "Play from the Spotify app on a phone or computer on the same Wi-Fi: pick the Echo in its " +
            "list of devices. Spotify Connect needs Spotify Premium. Status: $status",
        settings = listOf(
            SettingSpec(
                KEY_ENABLED, "Spotify Connect receiver", SettingType.BOOLEAN, default = "true",
                help = "Show the Echo in Spotify's device list. Control Center's Spotify tile also turns it off.",
            ),
            SettingSpec(
                KEY_NAME, "Spotify name", SettingType.TEXT, default = "",
                help = "The name Spotify shows. Leave blank to use the device name.",
            ),
            SettingSpec(
                KEY_BITRATE, "Streaming quality", SettingType.CHOICE, default = DEFAULT_BITRATE.toString(),
                help = "160 kbps sounds better; 96 kbps uses less data and memory.",
                options = listOf("160" to "Normal (160 kbps)", "96" to "Low (96 kbps)"),
            ),
            SettingSpec(
                KEY_MDNS, "Discovery (mDNS)", SettingType.CHOICE, default = MDNS_NSD, advanced = true,
                help = "How the Echo announces itself. Android's service (default) costs nothing while idle; " +
                    "the built-in responder is a fallback if Spotify can't find the Echo.",
                options = listOf(MDNS_NSD to "Android (NsdManager)", MDNS_LIB to "Built-in responder"),
            ),
            SettingSpec(
                KEY_SIGN_OUT, "Sign out of Spotify", SettingType.ACTION,
                help = "Forgets the Spotify account the Echo signed in with. Picking the Echo in the Spotify app signs in again.",
                onAction = {
                    val f = signOut
                    if (f != null) { f(); "Signed out." } else "The receiver is off."
                },
            ),
        ),
    )

    override fun install(host: FeatureHost) {
        val s = host.services
        services = s
        host.registerSettings(settingsSection(status.value))
        val context = host.context
        fun apply(on: Boolean) {
            try {
                if (on) SpotifyService.start(context) else SpotifyService.stop(context)
            } catch (e: Exception) {
                Log.e(TAG, "couldn't ${if (on) "start" else "stop"} the Spotify service", e)
            }
            if (!on) setStatus(SpotifyStatus.text(null, null, name(s.settings)))
        }
        var on = enabled(s.settings)
        if (on) apply(true)
        s.scope.launch {
            s.settings.changes.collect { key ->
                if (key == KEY_ENABLED || key == KEY_CC_SPOTIFY) {
                    val now = enabled(s.settings)
                    if (now != on) {
                        on = now
                        // Switching it on again is the way out of the crash guard.
                        if (now) s.settings.putInt(KEY_START_ATTEMPTS, 0)
                        Log.i(TAG, "Spotify Connect ${if (now) "on" else "off"} ($key)")
                        apply(now)
                    }
                }
            }
        }
        // The status line is part of the card's help text.
        s.scope.launch {
            status.collect { s.settingsSchema.register(settingsSection(it)) }
        }
    }

    private const val TAG = "VesperSpotify"
}
