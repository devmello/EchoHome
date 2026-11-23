package app.vesper.feature.phone

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.feature.phone.ui.PairingScreen

/**
 * `:feature:phone` (agent J — iPhone over Bluetooth).
 *
 *  - iPhone notifications via ANCS (Bluetooth LE) → services.notifications banners; incoming calls
 *    → the island (Decline only: calls are answered on the phone, no call audio on the Echo).
 *  - The Echo as a Bluetooth speaker (A2DP sink + AVRCP controller) → the phone's Now Playing in
 *    services.media as MediaSourceKind.BLUETOOTH ("bluetooth").
 *  - The iPhone's Now Playing over AMS (Bluetooth LE) → services.media "iphone" (OTHER_APP), merged into
 *    "bluetooth" when the same iPhone streams to the Echo.
 *  - "phone-pairing" screen (not in the pager): `services.navigator.show(PhoneFeature.SCREEN_ID)`.
 *
 * Debug: `--es screen phone-pairing --es phone_demo banner|mail|missed|call|media|ams|ams-podcast|connected|pairing|paired`.
 */
object PhoneFeature : VesperFeature {
    override val id = "phone"
    const val SCREEN_ID = "phone-pairing"

    /** The running controller (null before install or if Bluetooth isn't there at all). */
    var controller: PhoneController? = null
        private set

    override fun install(host: FeatureHost) {
        val handler = Handler(Looper.getMainLooper())
        val services = host.services
        val c = PhoneController(host.context, services, handler)
        controller = c
        // Start after every feature is installed (no work during install).
        handler.post { try { c.start() } catch (e: Exception) { Log.e(PhoneController.TAG, "phone start failed", e) } }

        host.registerSettings(settingsSection(c))

        val demo = if (services.config.debug) PhoneDemo(services, handler) else null
        host.registerScreen(ScreenSpec(SCREEN_ID, order = 90, inPager = false) { ctx ->
            object : PairingScreen(ctx, services, c) {
                override fun onScreenHidden() {
                    if (demoState == null) super.onScreenHidden()
                }

                override fun onScreenShown(args: Bundle?) {
                    val kind = args?.getString("phone_demo")
                    // onResume re-shows with null args: keep a demo that's on screen. An intent without phone_demo ends it.
                    if (kind == null && args == null && demoState != null) return
                    if (demo == null || kind == null) { demoState = null; super.onScreenShown(args); return }
                    when (kind) {
                        "pairing" -> demoState = PhoneDemo.pairingState(withPrompt = true)
                        "pairing-privileged" -> demoState = PhoneDemo.pairingState(withPrompt = true, canConfirm = true)
                        "paired" -> demoState = PhoneDemo.pairingState(withPrompt = false)
                        else -> {
                            // Banners and the island show over StandBy.
                            services.navigator.home()
                            handler.postDelayed({ demo.run(kind) }, 400)
                        }
                    }
                }
            }
        })
    }

    /** The "iPhone" card on the web settings page; every value applies live (PhoneController.applySetting). */
    private fun settingsSection(c: PhoneController) = SettingsSection(
        id = "phone", title = "iPhone", order = 40,
        help = "Notifications (ANCS) and Now Playing (AMS) come over Bluetooth LE; calls are answered on the iPhone. Pair from the iPhone's Settings › Bluetooth.",
        settings = listOf(
            SettingSpec(PhoneController.KEY_NOTIFICATIONS, "Show iPhone notifications", SettingType.BOOLEAN, "true",
                help = "Banners for the iPhone's notifications and incoming calls."),
            SettingSpec(PhoneController.KEY_AUDIO, "Bluetooth audio", SettingType.BOOLEAN, "true",
                help = "Show the iPhone's music in Now Playing when it plays to the Echo over Bluetooth."),
            SettingSpec(PhoneController.KEY_NOW_PLAYING, "Show what the iPhone is playing", SettingType.BOOLEAN, "true",
                help = "Now Playing shows the iPhone's music, podcasts or videos, even when they play on AirPods or the car, with play/pause, skip and volume (Apple Media Service)."),
            SettingSpec(PhoneController.KEY_ART_LOOKUP, "Look up album art (sends the song title to Apple)", SettingType.BOOLEAN, "true",
                help = "The iPhone doesn't send cover art over Bluetooth. Asks Apple's iTunes Search for the song's title and artist."),
            SettingSpec(PhoneController.KEY_HIDDEN_APPS, "Hide notifications from", SettingType.TEXT_LIST, "",
                help = "App names or bundle ids, e.g. Mail, com.burbn.instagram. Calls always show."),
            SettingSpec(PhoneController.KEY_CLEAR_ON_DISMISS, "Dismiss on the Echo clears the iPhone", SettingType.BOOLEAN, "true",
                help = "Uses the notification's Clear action. Never declines calls or deletes messages."),
            SettingSpec(PhoneController.KEY_ANSWER_CALLS, "Offer Answer for incoming calls", SettingType.BOOLEAN, "false", advanced = true,
                help = "Adds Answer next to Decline. The call is answered on the iPhone (the Echo has no call audio), so only use it when the phone is within reach."),
            SettingSpec(PhoneController.KEY_SHOW_SILENT, "Show notifications delivered quietly", SettingType.BOOLEAN, "false", advanced = true),
            SettingSpec("phone.forget", "Forget iPhone", SettingType.ACTION,
                help = "Unpairs the iPhone from the Echo. Also remove “Echo Show” in the iPhone's Settings › Bluetooth.",
                onAction = { c.forgetSaved() }),
        ),
    )
}
