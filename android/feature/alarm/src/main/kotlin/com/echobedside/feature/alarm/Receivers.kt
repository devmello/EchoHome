package com.echobedside.feature.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Our own AlarmManager wake-ups (not exported). The process is started if needed: EchoApp installs
 * the features (creating the stores) before this runs.
 */
class AlarmTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ALARM -> AlarmRuntime.alarms?.evaluate("alarm manager") ?: Log.e(AlarmRuntime.TAG, "alarm store missing")
            ACTION_TIMER -> AlarmRuntime.timers?.tick() ?: Log.e(AlarmRuntime.TAG, "timer store missing")
            ACTION_SNOOZE -> AlarmRuntime.alarms?.snooze()
            ACTION_STOP -> AlarmRuntime.alarms?.stop()
        }
    }

    companion object {
        const val ACTION_ALARM = "com.echobedside.alarm.WAKE"
        const val ACTION_TIMER = "com.echobedside.alarm.TIMER"
        const val ACTION_SNOOZE = "com.echobedside.alarm.SNOOZE"
        const val ACTION_STOP = "com.echobedside.alarm.STOP"
    }
}

/**
 * System events after which exact alarms must be rescheduled: boot (AlarmManager forgets them),
 * the clock or time zone changing (wall-clock triggers move), and our app being updated.
 */
class AlarmSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(AlarmRuntime.TAG, "reschedule: ${intent.action}")
                AlarmRuntime.alarms?.evaluate(intent.action ?: "system")
                AlarmRuntime.timers?.tick()
            }
        }
    }
}
