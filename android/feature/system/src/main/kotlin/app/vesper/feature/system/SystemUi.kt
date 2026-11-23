package app.vesper.feature.system

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Bundle
import app.vesper.core.model.VesperServices
import app.vesper.feature.system.device.Connectivity
import app.vesper.feature.system.device.HardwareMonitor
import app.vesper.feature.system.device.KeyRouter
import app.vesper.feature.system.device.RealSystemControls
import app.vesper.feature.system.hud.Hud
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The system layer's shared state, one per process: the four overlays (island, banners, HUDs,
 * Control Center) coordinate through it, and the device side (keys, gating button, shutter,
 * controls) posts into it. Main-thread only unless noted.
 */
internal class SystemUi(val app: Context, val services: VesperServices) {
    val connectivity = Connectivity(app, services.scope)
    val controls = RealSystemControls(this)
    val monitor = HardwareMonitor(this)
    val keys = KeyRouter(this)

    // --- HUD ---
    private val _hud = MutableSharedFlow<Hud>(extraBufferCapacity = 16)
    val hud: SharedFlow<Hud> = _hud
    /** Thread-safe. */
    fun showHud(h: Hud) { _hud.tryEmit(h) }

    // --- Control Center ---
    val ccOpen = MutableStateFlow(false)
    private val _ccRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val ccRequests: SharedFlow<Boolean> = _ccRequests
    fun requestControlCenter(open: Boolean) { _ccRequests.tryEmit(open) }

    // --- banners / island coordination ---
    /** A compact or expanded banner is on screen: the island fades out meanwhile. */
    val bannerShowing = MutableStateFlow(false)
    /** Set by the island while it's attached: shows a HUD (volume, brightness, mic/camera) inside the island if the island is on screen (true = handled, no HUD capsule). */
    var islandHud: ((app.vesper.feature.system.hud.Hud) -> Boolean)? = null
    /**
     * The island's shape in window px while it's on screen (its target box, not mid-animation), else
     * null. A banner grows out of it and shrinks back into it.
     */
    var islandBox: RectF? = null
    var islandRadius = 22f
    /** Island hit areas in window px (main + bead); Control Center's edge pull ignores touches there. */
    val islandHitAreas = mutableListOf<RectF>()

    /** The "screen off when the room is empty" overlay (set while attached): keys wake it. */
    var screenOff: app.vesper.feature.system.screenoff.ScreenOffOverlay? = null

    /** Debug commands (see DebugReceiver): "island" → scene, "hud" → kind, "banner" → index, "cc" → open/close. */
    val debug = MutableStateFlow<Bundle?>(null)

    /** Back key: overlays register "close me" handlers; the topmost open one consumes it. */
    val backHandlers = mutableListOf<() -> Boolean>()

    private var activityRef: WeakReference<Activity>? = null
    var activity: Activity?
        get() = activityRef?.get()
        set(v) { activityRef = v?.let { WeakReference(it) } }

    val demo get() = services.config.demoData
    val debugBuild get() = services.config.debug
}
