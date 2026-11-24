package app.vesper.core.model

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import kotlinx.coroutines.flow.StateFlow

/**
 * How a feature module plugs into the launcher shell without editing `:app`.
 *
 * Every `:feature:*` module exposes one `object XxxFeature : VesperFeature`. `:app` lists them once
 * (FeatureList) and calls [install] on each at process start, in this order:
 * system, phone, airplay, media, alarm, home, assistant, camera, photos, clock.
 *
 * Rules:
 *  - In [install], only *register* things and *bind* services you implement
 *    (e.g. `host.services.alarms = RealAlarmStore(...)`). Don't read other features' services
 *    there: they may still be fakes. Read `host.services.x` lazily, in view factories / on attach.
 *  - Never block in [install]; it runs on the main thread during startup.
 */
interface VesperFeature {
    /** Short id, the same as the module name: "clock", "system", ... */
    val id: String
    fun install(host: FeatureHost)
}

interface FeatureHost {
    /** The Application context. */
    val context: Context
    val services: VesperServices

    /** A full-screen page in the launcher's horizontal pager (or reachable only by [Navigator.show]). */
    fun registerScreen(spec: ScreenSpec)
    /** A clock face for the StandBy screen (see [FaceRegistry]). */
    fun registerFace(spec: FaceSpec) = services.faces.register(spec)
    /** A layer drawn above every screen: island, HUDs, banners, Control Center. */
    fun registerOverlay(spec: OverlaySpec)
    /** Hardware keys reach handlers before the system (volume keys, the remapped mic button). */
    fun registerKeyHandler(handler: KeyHandler)
    /** User settings this feature reads, shown on the web settings page (see [SettingSpec]). */
    fun registerSettings(section: SettingsSection) = services.settingsSchema.register(section)
}

/**
 * @param order position in the horizontal pager (lower = further left). StandBy faces are 0.
 * @param inPager false = not swipeable, only opened with [Navigator.show] (e.g. "alarm-ringing").
 *   A pager screen the user switched off (setting [PagerScreens.KEY], the web page's Screens
 *   section) is left out of the pager and never built there, but still opens with [Navigator.show].
 * @param title the name people see for a pager screen (the web page's Screens list): "Now Playing".
 * @param factory builds the view once; it's kept while the activity lives. If the view implements
 *   [VesperScreen] it's told when it is shown/hidden so it can stop animations off screen.
 */
class ScreenSpec(
    val id: String,
    val order: Int,
    val inPager: Boolean = true,
    val title: String? = null,
    val factory: (Context) -> View,
)

/** Optional interface for screen and overlay views. */
interface VesperScreen {
    /** Became the visible screen. [args] are extras from [Navigator.show] or the launch intent. */
    fun onScreenShown(args: Bundle?) {}
    /** Left the screen: stop animations, release the camera, etc. */
    fun onScreenHidden() {}
}

/** @param z higher draws on top. Island 300, banners 400, HUDs 500, Control Center 600. */
class OverlaySpec(
    val id: String,
    val z: Int,
    val factory: (Context) -> View,
)

fun interface KeyHandler {
    /** Return true to consume the event (the system never sees it). */
    fun onKey(event: KeyEvent): Boolean
}

/**
 * Screen routing, owned by `:app`.
 *
 * Debug / test: `adb shell am start -n app.vesper/.MainActivity --es screen <id> [--es k v ...]`
 * opens a screen with the other extras as args. `--es face <id>` picks the StandBy face.
 */
interface Navigator {
    val current: StateFlow<String>
    fun show(screenId: String, args: Bundle? = null)
    /** Back to the StandBy screen. */
    fun home()
}
