package app.vesper.feature.sleep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.VesperServices
import app.vesper.feature.sleep.ui.SleepScreen
import kotlinx.coroutines.launch

/**
 * `:feature:sleep`: sleep sounds synthesised on the device and the bedtime routine.
 *
 *  - Binds `services.sleep` ([SleepController] over an [AudioTrackOutput]); the sound shows in
 *    `services.media` (Now Playing, island, Control Center) as "Sleep sounds".
 *  - Screen "sleep" (pager order 15, after Now Playing; also opened from Control Center's media
 *    tile while a sleep sound is loaded or nothing is): `--es screen sleep [--es sound rain]`.
 *  - Settings card "Sleep sounds & bedtime" (order 38).
 *  - The routine's Do Not Disturb / Night mode are handed back at the wake time ([WakeReceiver]).
 *
 * Nothing plays at install, after a restart, or in demo mode: only a tap or a voice request starts a sound.
 */
object SleepFeature : VesperFeature {
    override val id = "sleep"
    const val TAG = "VesperSleep"

    @Volatile internal var controller: SleepController? = null
    @Volatile private var services: VesperServices? = null

    override fun install(host: FeatureHost) {
        val s = host.services
        services = s
        val ctx = host.context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val c = SleepController(
            services = { s },
            output = AudioTrackOutput(),
            focus = AndroidFocusGate(ctx),
            scheduler = HandlerScheduler(main),
            elapsed = SystemClock::elapsedRealtime,
            artwork = { SleepArt.get(it) },
            scheduleWake = { at -> scheduleWake(ctx, at) },
        )
        controller = c
        s.sleep = c
        host.registerSettings(SleepSettings.section)
        host.registerScreen(ScreenSpec(SleepSounds.SCREEN_ID, order = 15) { SleepScreen(it, s, c) })
        s.scope.launch { s.settings.changes.collect { if (it == SleepSettings.LEVEL) c.levelChanged() } }
        // After every feature is installed (system controls, media hub producers).
        main.post {
            c.watchHub()
            WakeRestore.run(s, System.currentTimeMillis())?.let { scheduleWake(ctx, it) }
        }
    }

    internal fun onWake() {
        val s = services ?: return
        WakeRestore.run(s, System.currentTimeMillis())?.let { at -> Log.i(TAG, "wake early, again at $at") }
    }

    private fun scheduleWake(ctx: Context, atMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, WakeReceiver::class.java).setAction(WakeReceiver.ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }
}

/** Wakes at the bedtime routine's wake time to hand back Do Not Disturb / Night mode. Not exported. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) SleepFeature.onWake()
    }

    companion object { const val ACTION = "app.vesper.sleep.WAKE" }
}

internal class HandlerScheduler(private val h: Handler) : SleepScheduler {
    private var pending: Runnable? = null
    override fun at(atElapsed: Long, r: () -> Unit) {
        cancel()
        val run = Runnable { pending = null; r() }
        pending = run
        h.postDelayed(run, (atElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    override fun cancel() { pending?.let { h.removeCallbacks(it) }; pending = null }
}
