package app.vesper.feature.alarm

import android.os.Handler
import android.os.Looper
import app.vesper.core.model.VesperFeature
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.OverlaySpec
import app.vesper.core.model.ScreenSpec
import app.vesper.feature.alarm.ui.AlarmScreen
import app.vesper.feature.alarm.ui.AlarmsScreen
import app.vesper.feature.alarm.ui.SunriseOverlay
import app.vesper.feature.alarm.ui.TimersScreen

/**
 * `:feature:alarm` (agent E — Alarms + timers). NOTES §6 "Alarms", §2.1 Alarm / Timers.
 *
 * Binds services.alarms ([RealAlarmStore]: exact alarms, sunrise ramp, ring / 9-min snooze /
 * Good morning, rescheduled after boot, clock and zone changes) and services.timers
 * ([RealTimerStore]: up to 3, persisted, AlarmManager completion). Screens: "alarm" (not in the
 * pager; opened when it rings), "alarms" (the list and editor, pager order 25) and "timers" (pager order 30). Overlay: the sunrise wash (z 250).
 * Island: ALARM_RINGING (Snooze / Stop), TIMER per timer, TIMER_DONE.
 */
object AlarmFeature : VesperFeature {
    override val id = "alarm"
    const val ALARMS_SCREEN_ID = "alarms"

    override fun install(host: FeatureHost) {
        val alarms = RealAlarmStore(host.context, host.services)
        val timers = RealTimerStore(host.context, host.services)
        host.services.alarms = alarms
        host.services.timers = timers
        AlarmRuntime.alarms = alarms
        AlarmRuntime.timers = timers
        AlarmRuntime.settings = host.services.settings
        host.registerSettings(AlarmSettings.section)
        AlarmService.createChannels(host.context)

        host.registerScreen(ScreenSpec(RealAlarmStore.SCREEN_ID, order = 1000, inPager = false) { AlarmScreen(it, host.services) })
        host.registerScreen(ScreenSpec(ALARMS_SCREEN_ID, order = 25) { AlarmsScreen(it, host.services) })
        host.registerScreen(ScreenSpec(RealTimerStore.SCREEN_ID, order = 30) { TimersScreen(it, host.services) })
        host.registerOverlay(OverlaySpec("alarm.sunrise", 250) { SunriseOverlay(it, host.services) })

        // Evaluate once every feature is installed (the phase side effects use other services).
        Handler(Looper.getMainLooper()).post {
            // The ringing / snoozed screen has its own big time and status: no island over it.
            host.services.island.hideOnScreen(RealAlarmStore.SCREEN_ID)
            alarms.evaluate("start")
            timers.start()
        }
    }
}
