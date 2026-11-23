package app.vesper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.ScreenSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The HOME activity and launcher shell: a horizontal pager of the registered screens, screens
 * that open on top (not in the pager), then the overlays (island, banners, HUDs, Control Center).
 * Plain platform Views, no AndroidX, to keep RAM and GPU cost low on the Echo.
 *
 * Launch extras (adb): `--es screen <id>` open a screen (other extras go to it as args),
 * `--es face <id>` pick the StandBy face, `--ez demo true|false` toggle sample data (restarts).
 */
class MainActivity : Activity() {

    private val app get() = application as VesperApp
    private lateinit var root: FrameLayout
    private lateinit var pager: ScreenPager
    private lateinit var pagerScreens: List<ScreenSpec>
    private lateinit var topLayer: FrameLayout
    private var topScreen: Pair<String, View>? = null
    private val offPagerViews = HashMap<String, View>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shownId: String? = null
    private var askedMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A bedside clock stays on while it's in front. (Night mode dims it; it doesn't turn it off.)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // A bedside display never sits behind the (swipe) keyguard, e.g. after the mic button's KEY_POWER.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.setDecorFitsSystemWindows(false)

        pagerScreens = app.screens.filter { it.inPager }
        root = FrameLayout(this)
        pager = ScreenPager(this, pagerScreens.size.coerceAtLeast(1)) { i ->
            pagerScreens.getOrNull(i)?.factory?.invoke(this) ?: View(this)
        }
        pager.onPageSelected = { i -> onShown(pagerScreens[i].id, null) }
        root.addView(pager, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        topLayer = FrameLayout(this)
        root.addView(topLayer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        for (o in app.overlays) {
            try {
                root.addView(o.factory(this), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            } catch (e: Exception) {
                Log.e(VesperApp.TAG, "Overlay ${o.id} failed", e)
            }
        }
        setContentView(root)

        scope.launch { app.navigator.requests.collect { (id, args) -> show(id, args) } }
        handleIntent(intent, initial = true)
    }

    // --- navigation ---

    private fun show(id: String, args: Bundle?) {
        val pagerIndex = pagerScreens.indexOfFirst { it.id == id }
        if (pagerIndex >= 0) {
            closeTopScreen()
            val changed = pagerIndex != pager.currentPage
            pager.scrollToPage(pagerIndex, animate = !changed || shownId != null)
            if (!changed) onShown(id, args) else (pager.page(pagerIndex) as? VesperScreen)?.onScreenShown(args)
            if (changed) { shownId = id; app.navigator.setCurrent(id) }
            return
        }
        val spec = app.screens.firstOrNull { it.id == id } ?: run { Log.w(VesperApp.TAG, "No screen '$id'"); return }
        closeTopScreen()
        val v = offPagerViews.getOrPut(id) { spec.factory(this) }
        topLayer.addView(v, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        topScreen = id to v
        currentScreenView()?.let { if (it !== v) (it as? VesperScreen)?.onScreenHidden() }
        (v as? VesperScreen)?.onScreenShown(args)
        shownId = id
        app.navigator.setCurrent(id)
    }

    private fun closeTopScreen() {
        val (_, v) = topScreen ?: return
        (v as? VesperScreen)?.onScreenHidden()
        topLayer.removeView(v)
        topScreen = null
        (pager.page(pager.currentPage) as? VesperScreen)?.onScreenShown(null)
        pagerScreens.getOrNull(pager.currentPage)?.let { shownId = it.id; app.navigator.setCurrent(it.id) }
    }

    private fun onShown(id: String, args: Bundle?) {
        if (shownId != null && shownId != id) screenView(shownId!!)?.let { (it as? VesperScreen)?.onScreenHidden() }
        shownId = id
        app.navigator.setCurrent(id)
        (screenView(id) as? VesperScreen)?.onScreenShown(args)
    }

    private fun screenView(id: String): View? {
        topScreen?.let { if (it.first == id) return it.second }
        val i = pagerScreens.indexOfFirst { it.id == id }
        return if (i >= 0) pager.page(i) else null
    }

    private fun currentScreenView(): View? = shownId?.let { screenView(it) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent, initial = false)
    }

    private fun handleIntent(intent: Intent, initial: Boolean) {
        val extras = intent.extras
        app.services.intents.tryEmit(intent)
        // Only `--ez demo true|false` toggles sample data; a String `demo` is a screen arg.
        @Suppress("DEPRECATION") val demo = extras?.get("demo")
        if (demo is Boolean) {
            if (demo != app.services.config.demoData) {
                app.services.settings.putBoolean(VesperApp.KEY_DEMO, demo)
                // Fakes read the flag at startup: restart the process (the system relaunches HOME).
                Handler(Looper.getMainLooper()).postDelayed({ finishAffinity(); Process.killProcess(Process.myPid()) }, 300)
                return
            }
        }
        val screen = extras?.getString("screen")
            ?: if (extras?.getString("face") != null) VesperApp.HOME_SCREEN else null
        when {
            screen != null -> show(screen, extras)
            initial -> show(VesperApp.HOME_SCREEN, null)
            // HOME pressed while we're in front: back to StandBy
            intent.hasCategory(Intent.CATEGORY_HOME) -> app.navigator.home()
        }
    }

    // --- system UI, keys, lifecycle ---

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        for (h in app.keyHandlers) if (h.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private val escapeHatch = Runnable {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private val handler = Handler(Looper.getMainLooper())

    /** Two fingers held still for 2 s opens Android Settings (dev escape hatch, any screen). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) handler.postDelayed(escapeHatch, 2000) else handler.removeCallbacks(escapeHatch)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(escapeHatch)
        }
        return super.dispatchTouchEvent(ev)
    }

    private val systemBars by lazy { SystemBars(this) }

    override fun onResume() {
        super.onResume()
        systemBars.hide()
        (currentScreenView() as? VesperScreen)?.onScreenShown(null)
        // The assistant's microphone (declared in :feature:assistant). Ask once per process; the
        // assistant re-checks it in onScreenShown, so no result callback is needed.
        if (!askedMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askedMic = true
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onPause() {
        (currentScreenView() as? VesperScreen)?.onScreenHidden()
        systemBars.restore()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** We're the launcher: Back closes a top screen, or goes to StandBy. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (topScreen != null) closeTopScreen() else app.navigator.home()
    }
}
