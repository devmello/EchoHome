package app.vesper.feature.system

import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore

/**
 * Every user-facing setting `:feature:system` reads, for the web settings page (`:feature:settings`).
 * All apply live: the overlays observe `settings.changes` and re-read these keys.
 */
internal object SystemSettings {
    const val NIGHT_MODE = "system.night.mode"            // NightMode name: AUTO | ON | OFF
    const val ISLAND_LOOK = "system.island.look"          // glass | black
    const val BANNERS_ENABLED = "system.banners.enabled"
    const val BANNERS_ECHO_APPS = "system.banners.android" // notifications from apps on the Echo itself
    const val BANNER_SECONDS = "system.banners.seconds"
    const val DND_CRITICAL = "system.dnd.critical"        // doorbell / alarm / calls still show in DND
    const val HUD_SECONDS = "system.hud.seconds"
    const val HUD_BADGES = "system.hud.badges"            // red mic/camera badges top-right
    const val SCREENOFF_ENABLED = "system.screenoff.enabled" // blank the screen when the room is empty
    const val SCREENOFF_MINUTES = "system.screenoff.minutes"
    const val SCREENOFF_SAVED = "system.screenoff.saved"      // internal: brightness to restore if we died while dark
    const val CC_AIRPLAY = "system.cc.airplay"            // read by :feature:airplay

    fun bannerShowMs(s: SettingsStore) = (s.getFloat(BANNER_SECONDS, 5f).coerceIn(2f, 30f) * 1000).toLong()
    fun hudLevelMs(s: SettingsStore) = (s.getFloat(HUD_SECONDS, 1.5f).coerceIn(0.8f, 6f) * 1000).toLong()

    val sections = listOf(
        SettingsSection("system.display", "Display", 20, listOf(
            SettingSpec(ISLAND_LOOK, "Echo Island look", SettingType.CHOICE, "glass",
                help = "The live-activity capsule at the top of the screen.",
                options = listOf("glass" to "Glass", "black" to "Black")),
            SettingSpec(SCREENOFF_ENABLED, "Turn the screen off when the room is empty", SettingType.BOOLEAN, "true",
                help = "Needs camera presence. The screen goes black and the backlight to minimum; the camera keeps watching, and a touch, someone arriving, an alarm, a call or the doorbell wakes it."),
            SettingSpec(SCREENOFF_MINUTES, "Turn it off after (minutes)", SettingType.FLOAT, "15",
                help = "How long the room has to stay empty first.", min = 1f, max = 120f, step = 1f),
        )),
        SettingsSection("system.notifications", "Notifications", 30, listOf(
            SettingSpec(BANNERS_ENABLED, "Show banners", SettingType.BOOLEAN, "true",
                help = "Glass banners for iPhone notifications, the Echo's own alerts and apps on the Echo."),
            SettingSpec(BANNER_SECONDS, "Banner time on screen", SettingType.FLOAT, "5",
                help = "Seconds a banner stays before it slides away (tap it to keep it open).", min = 2f, max = 30f, step = 0.5f),
            SettingSpec(BANNERS_ECHO_APPS, "Notifications from apps on the Echo", SettingType.BOOLEAN, "true",
                help = "Android apps installed on the Echo. iPhone notifications are set in the Phone section.", advanced = true),
            SettingSpec(DND_CRITICAL, "Doorbell, alarms and calls in Do Not Disturb", SettingType.BOOLEAN, "true",
                help = "Do Not Disturb hides other banners. Turn this off to hide these too."),
        )),
        SettingsSection("system.indicators", "Indicators", 40, listOf(
            SettingSpec(HUD_SECONDS, "Volume / brightness capsule time", SettingType.FLOAT, "1.5",
                help = "Seconds the capsule stays after the last change.", min = 0.8f, max = 6f, step = 0.1f, advanced = true),
            SettingSpec(HUD_BADGES, "Mic and camera off badges", SettingType.BOOLEAN, "true",
                help = "Red badges top-right while the microphone or camera is off."),
        )),
        // NIGHT_MODE isn't on the page: the clock's "clock.night.mode" (with the schedule) is the one Night setting.
        // CC_AIRPLAY isn't on the page: :feature:airplay's own "airplay.enabled" is the page's switch.
    )
}
