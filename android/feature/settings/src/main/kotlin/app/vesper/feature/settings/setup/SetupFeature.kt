package app.vesper.feature.settings.setup

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.VesperServices
import app.vesper.feature.settings.SettingsFeature
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The first-run setup ("onboarding" screen, not in the pager), part of `:feature:settings`.
 *
 * **Install it first** (before every other feature, see `FeatureList` in `:app`): the gate decides
 * "fresh install or already set up" from whether the settings store has any keys, and other
 * features write keys of their own while they start (AirPlay's identity, the detected location…).
 *
 * - Fresh install: the setup opens over StandBy on the first launch (unless the launch intent
 *   asks for a screen), and again on every start until it's finished or skipped.
 * - `--es screen onboarding [--es step <1-8 | welcome|wifi|name|location|phone|people|clock|done>]`
 *   opens it for review at any time; it doesn't change `setup.done` until Done is tapped.
 * - "Run setup again" (General, on the settings page) resets `setup.done` and opens it.
 */
object SetupFeature : VesperFeature {
    override val id = "setup"
    const val SCREEN = "onboarding"
    private const val TAG = "VesperSetup"
    /** The settings store's file (`:core:data` PrefsSettingsStore). */
    private const val PREFS = "echo_settings"

    /** True while this install still needs the setup (read on the main thread). */
    var needed = false
        private set

    override fun install(host: FeatureHost) {
        val services = host.services
        needed = FirstRun.resolve(services.settings, hadSettings(host.context))
        Log.i(TAG, if (needed) "first run: setup will show" else "set up already")

        host.registerScreen(ScreenSpec(SCREEN, order = 97, inPager = false) { c ->
            OnboardingView(c, services, SettingsFeature.webSettings)
        })
        services.island.hideOnScreen(SCREEN)
        // Back steps back through the setup instead of leaving it.
        host.registerKeyHandler { e ->
            if (e.keyCode != KeyEvent.KEYCODE_BACK || services.navigator.current.value != SCREEN) return@registerKeyHandler false
            if (e.action == KeyEvent.ACTION_UP) OnboardingView.active?.get()?.onBackKey()
            true
        }

        // Open on the first launch of the HOME activity. Posted, so it lands after the activity
        // has shown StandBy for the same intent (and stays on top of it).
        if (needed) services.scope.launch {
            val intent = services.intents.first()
            if (intent.extras?.getString("screen") == null && needed) {
                Handler(Looper.getMainLooper()).post { services.navigator.show(SCREEN) }
            }
        }
    }

    /** The setup's Done: never show it on its own again. */
    fun finish(services: VesperServices) {
        needed = false
        FirstRun.markDone(services.settings)
        services.navigator.home()
    }

    /** "Run setup again" (settings page). */
    fun runAgain(services: VesperServices): String {
        FirstRun.reset(services.settings)
        needed = true
        services.navigator.show(SCREEN, Bundle().apply { putString("step", SetupStep.WELCOME.id) })
        return "Setup is open on the Echo."
    }

    /**
     * Any keys in the settings store before this process wrote one? Read straight from the store's
     * SharedPreferences (the `SettingsStore` contract can't list keys). Same instance as the store,
     * so it sees writes from this process too: that's why this runs first.
     */
    private fun hadSettings(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.any(FirstRun::countsAsSettings)
    } catch (e: Exception) {
        // Unreadable: treat as set up, so an existing Echo is never interrupted.
        Log.w(TAG, "settings unreadable: ${e.javaClass.simpleName}")
        true
    }
}
