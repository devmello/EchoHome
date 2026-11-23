package app.vesper.feature.lists

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Our AlarmManager wake-up (not exported): a reminder falls due or a due one becomes missed.
 * If the process wasn't running, VesperApp installs the features (creating the store) first.
 * Kept alive ~3 s so the chime finishes.
 */
class ReminderTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DUE) return
        val store = ListsFeature.reminderStore ?: run { Log.e(ListsFeature.TAG, "reminder store missing"); return }
        val pending = goAsync()
        store.evaluate("alarm manager")
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 3000)
    }

    companion object {
        const val ACTION_DUE = "app.vesper.reminders.DUE"
    }
}

/** Boot (AlarmManager forgets), clock / time-zone changes (wall-clock times move), app updates. */
class ReminderSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                ListsFeature.reminderStore?.evaluate(intent.action ?: "system")
        }
    }
}
