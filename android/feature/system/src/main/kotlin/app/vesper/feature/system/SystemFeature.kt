package app.vesper.feature.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.NightMode
import app.vesper.core.model.OverlaySpec
import app.vesper.feature.system.banner.BannerOverlay
import app.vesper.feature.system.cc.ControlCenterOverlay
import app.vesper.feature.system.hud.HudOverlay
import app.vesper.feature.system.island.IslandOverlay

/**
 * `:feature:system` (agent B — System layer): volume keys in-app (no system panel), the mic/camera
 * button (gating latch + KEY_POWER remap plan), the lens shutter, HUDs, Control Center, banners and
 * the Echo Island renderer. Binds `services.systemControls`. See docs/status/system.md.
 *
 * Overlays (full-screen, empty areas pass touches through): island 300, banners 400, HUDs 500,
 * Control Center 600.
 *
 * Debug (debug builds): `adb shell am broadcast -a app.vesper.system.DEBUG --es island story`
 * (also `--es hud volume|brightness|micoff|micon|camoff`, `--es banner 0..3`, `--es cc open|close`,
 * `--es look glass|black`), or the same extras on the launch intent.
 */
object SystemFeature : VesperFeature {
    override val id = "system"
    internal var ui: SystemUi? = null; private set

    override fun install(host: FeatureHost) {
        val ui = SystemUi(host.context, host.services)
        this.ui = ui
        host.services.systemControls = ui.controls
        applyNightSetting(host.services)
        SystemSettings.sections.forEach { host.registerSettings(it) }
        host.registerKeyHandler { ui.keys.onKey(it) }
        host.registerOverlay(OverlaySpec("island", 300) { IslandOverlay(it, ui) })
        host.registerOverlay(OverlaySpec("banners", 400) { BannerOverlay(it, ui) })
        host.registerOverlay(OverlaySpec("huds", 500) { HudOverlay(it, ui) })
        host.registerOverlay(OverlaySpec("controlcenter", 600) { ControlCenterOverlay(it, ui) })
        if (host.services.config.debug) registerDebug(host.context, ui)
    }

    /** `system.night.mode` (CC button or the settings page) → SystemState.nightMode. Also called live on change. */
    internal fun applyNightSetting(services: app.vesper.core.model.VesperServices) {
        val m = services.settings.getString(SystemSettings.NIGHT_MODE, "").takeIf { it.isNotEmpty() } ?: return
        val mode = runCatching { NightMode.valueOf(m.uppercase()) }.getOrNull() ?: return
        if (services.system.state.value.nightMode != mode) services.system.update { it.copy(nightMode = mode) }
    }

    private fun registerDebug(ctx: Context, ui: SystemUi) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = Bundle(i.extras ?: Bundle())
                b.putLong("_t", System.nanoTime()) // distinct each time
                Log.i("VesperSystem", "debug: ${b.keySet().filter { it != "_t" }.associateWith { b.get(it) }} | ${ui.monitor.describe()}")
                ui.debug.value = b
            }
        }, IntentFilter(DEBUG_ACTION))
    }

    const val DEBUG_ACTION = "app.vesper.system.DEBUG"
}
