package com.echobedside.feature.phone

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.echobedside.core.model.EchoFeature
import com.echobedside.core.model.FeatureHost
import com.echobedside.core.model.ScreenSpec
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.feature.phone.ui.PairingScreen

/**
 * `:feature:phone` (agent J — iPhone over Bluetooth).
 *
 *  - iPhone notifications via ANCS (Bluetooth LE) → services.notifications banners; incoming calls
 *    → the island (Decline only: calls are answered on the phone, no call audio on the Echo).
 *  - The Echo as a Bluetooth speaker (A2DP sink + AVRCP controller) → the phone's Now Playing in
 *    services.media as MediaSourceKind.BLUETOOTH ("bluetooth").
 *  - "phone-pairing" screen (not in the pager): `services.navigator.show(PhoneFeature.SCREEN_ID)`.
 *
 * Debug: `--es screen phone-pairing --es phone_demo banner|mail|missed|call|media|connected|pairing|paired`.
 */
object PhoneFeature : EchoFeature {
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
        help = "Notifications come over Bluetooth LE (ANCS); calls are answered on the iPhone. Pair from the Echo's Bluetooth screen.",
        settings = listOf(
            SettingSpec(PhoneController.KEY_NOTIFICATIONS, "Show iPhone notifications", SettingType.BOOLEAN, "true",
                help = "Banners for the iPhone's notifications and incoming calls."),
            SettingSpec(PhoneController.KEY_AUDIO, "Bluetooth audio", SettingType.BOOLEAN, "true",
                help = "Show the iPhone's music in Now Playing when it plays to the Echo over Bluetooth."),
            SettingSpec(PhoneController.KEY_HIDDEN_APPS, "Hide notifications from", SettingType.TEXT_LIST, "",
                help = "App names or bundle ids, e.g. Mail, com.burbn.instagram. Calls always show."),
            SettingSpec(PhoneController.KEY_CLEAR_ON_DISMISS, "Dismiss on the Echo clears the iPhone", SettingType.BOOLEAN, "true",
                help = "Uses the notification's Clear action. Never declines calls or deletes messages."),
            SettingSpec(PhoneController.KEY_SHOW_SILENT, "Show notifications delivered quietly", SettingType.BOOLEAN, "false", advanced = true),
            SettingSpec("phone.forget", "Forget iPhone", SettingType.ACTION,
                help = "Unpairs the iPhone from the Echo. Also remove “Echo Show” in the iPhone's Settings › Bluetooth.",
                onAction = { c.forgetSaved() }),
        ),
    )
}
