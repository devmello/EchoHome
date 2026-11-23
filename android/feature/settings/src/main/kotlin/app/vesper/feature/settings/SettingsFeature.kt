package app.vesper.feature.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.DeviceName
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.OverlaySpec
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.Secrets
import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.feature.settings.ui.CodeOverlayView
import app.vesper.feature.settings.ui.PairScreenView

/**
 * `:feature:settings` (agent K): the web settings page.
 *
 * The Echo serves a settings web app on the home Wi-Fi (port 8080; 80 needs a capability apps
 * don't get). A phone or Mac opens it in the browser, pairs once with a 6-digit code shown on
 * the Echo, and then edits every feature's [SettingSpec]s; changes apply live through
 * `services.settings.changes` / `services.secrets.changes`.
 *
 * Registers: screen [SCREEN_PAIR] (URL + QR; open it with `navigator.show("settings-pair")` or
 * `--es screen settings-pair`), overlay "settings-code" (z 650, the 6-digit code), and the
 * General (0) and Accounts & keys (90) sections.
 */
object SettingsFeature : VesperFeature {
    override val id = "settings"
    const val SCREEN_PAIR = "settings-pair"
    const val KEY_RESTART = "settings.restartApp"

    private var web: WebSettings? = null

    override fun install(host: FeatureHost) {
        val ctx = host.context
        val w = WebSettings(ctx, host.services).also { web = it }

        host.registerSettings(SettingsSection("general", "General", 0, listOf(
            SettingSpec(DeviceName.KEY, "Name", SettingType.TEXT, DeviceName.DEFAULT,
                help = "How the Echo shows up in AirPlay, Bluetooth and here."),
            SettingSpec(KEY_RESTART, "Restart app", SettingType.ACTION,
                help = "Restarts EchoHome on the Echo (about 5 seconds). Needed after changing Home Assistant.",
                onAction = { restart(ctx); "Restarting the Echo app…" }),
        )))
        host.registerSettings(accountsSection())

        host.registerScreen(ScreenSpec(SCREEN_PAIR, order = 95, inPager = false) { c -> PairScreenView(c, host.services, w) })
        host.registerOverlay(OverlaySpec("settings-code", 650) { c -> CodeOverlayView(c, host.services, w.pairing) })
        w.start()
    }

    internal fun accountsSection() = SettingsSection("accounts", "Accounts & keys", 90, listOf(
        SettingSpec(Secrets.ANTHROPIC_API_KEY, "Anthropic API key", SettingType.SECRET,
            help = "For the voice assistant (Claude). Create one at console.anthropic.com under API keys.",
            placeholder = "sk-ant-…"),
        SettingSpec(Secrets.CALENDAR_ICS_URLS, "Calendar links", SettingType.SECRET,
            help = "Private iCal addresses, one per line. In Google Calendar: Settings, your calendar, Integrate calendar, " +
                "Secret address in iCal format. Saving replaces every link.",
            multiline = true, placeholder = "https://calendar.google.com/calendar/ical/…/basic.ics"),
        SettingSpec(Secrets.IMMICH_URL, "Immich server", SettingType.SECRET,
            help = "For the photo frame, e.g. http://192.168.10.20:2283.",
            placeholder = "http://immich.local:2283"),
        SettingSpec(Secrets.IMMICH_API_KEY, "Immich API key", SettingType.SECRET,
            help = "In Immich: Account settings, API keys."),
        SettingSpec(Secrets.HA_URL, "Home Assistant URL", SettingType.SECRET,
            help = "Optional, only for smart-home controls. Applies after Restart app.",
            placeholder = "http://homeassistant.local:8123"),
        SettingSpec(Secrets.HA_TOKEN, "Home Assistant token", SettingType.SECRET,
            help = "Optional. In Home Assistant: your profile, Security, Long-lived access tokens. Applies after Restart app."),
        SettingSpec(Secrets.STOCKS_API_KEY, "Stocks API key", SettingType.SECRET, advanced = true,
            help = "Not needed: stocks use a free source with no key."),
    ), help = "Stored encrypted on the Echo and never shown again. Changes apply right away, except Home Assistant (Restart app).")

    /** Kills the process after the HTTP reply has gone out; Android restarts the HOME app. */
    private fun restart(ctx: Context) {
        Thread {
            SystemClock.sleep(700)
            try {
                val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                if (launch != null) {
                    val pi = PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
                    ctx.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1500, pi)
                }
            } catch (e: Exception) {
                Log.w(WebSettings.TAG, "restart intent: ${e.javaClass.simpleName}")
            }
            Log.i(WebSettings.TAG, "restarting app (requested from the settings page)")
            Process.killProcess(Process.myPid())
        }.start()
    }
}
