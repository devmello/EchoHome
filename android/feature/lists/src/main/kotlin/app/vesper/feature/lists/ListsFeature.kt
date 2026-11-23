package app.vesper.feature.lists

import android.os.Handler
import android.os.Looper
import app.vesper.core.model.FeatureHost
import app.vesper.core.model.ScreenSpec
import app.vesper.core.model.VesperFeature
import app.vesper.feature.lists.ui.ListsScreen

/**
 * `:feature:lists`: reminders and the shopping list, by voice (the assistant's tools read
 * services.reminders / services.lists) and by touch.
 *
 * Binds services.reminders ([RealReminderStore]: persisted, exact AlarmManager wake-ups, survives
 * reboot; chime + island + banner when due, missed when unanswered) and services.lists
 * ([RealListStore]: named lists, "shopping" by default, case-insensitive de-dup).
 * Screen "lists" (pager order 35, after Timers). Settings card "Reminders & lists" (order 45),
 * whose TEXT_LIST rows edit both stores from the phone ([WebLists]).
 */
object ListsFeature : VesperFeature {
    override val id = "lists"
    const val SCREEN_ID = "lists"
    const val TAG = "VesperLists"

    /** For the receivers (created by the system). */
    @Volatile internal var reminderStore: RealReminderStore? = null

    override fun install(host: FeatureHost) {
        val s = host.services
        val reminders = RealReminderStore(host.context, s)
        reminderStore = reminders
        s.reminders = reminders
        s.lists = RealListStore(host.context)
        host.registerSettings(ListsSettings.section)
        host.registerScreen(ScreenSpec(SCREEN_ID, order = 35) { ListsScreen(it, s) })
        // Once every feature is installed (alerts use the island, banners, profiles, system state).
        Handler(Looper.getMainLooper()).post {
            reminders.evaluate("start")
            WebLists(host.context, s).start()
        }
    }
}
