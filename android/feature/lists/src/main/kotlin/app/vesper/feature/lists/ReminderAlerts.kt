package app.vesper.feature.lists

import app.vesper.core.design.VesperColors
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.Banner
import app.vesper.core.model.IslandAction
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Profile
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderStore
import app.vesper.core.model.ReminderTime
import app.vesper.core.model.VesperServices
import app.vesper.feature.lists.engine.Evaluation
import java.time.ZoneId

/**
 * What a reminder does on screen and out loud, derived from each evaluation (idempotent):
 *  - newly due: the chime (unless Do Not Disturb; quieter in Night mode; setting `reminders.chime`)
 *    and a banner with Done (tinted) and Snooze 10 min. The banner is the alert; Do Not Disturb hides it.
 *  - due: a REMINDER island activity per reminder (compact bell + text; tap to expand Done / Snooze).
 *  - missed: one REMINDER_MISSED activity ("Missed: …" or "3 missed"), below music, until Done.
 * Island activities open the Lists screen and hide while it's showing (it shows them itself).
 */
internal class ReminderAlerts(private val services: VesperServices, private val store: ReminderStore) {
    private val published = HashMap<String, LiveContent.Reminder>()
    private val banners = HashSet<String>()

    fun apply(ev: Evaluation) {
        val list = ev.reminders
        val st = services.system.state.value
        if (ev.newlyDue.isNotEmpty() && !st.doNotDisturb && ListsSettings.chime(services.settings)) Chime.play(if (st.nightActive) NIGHT_VOLUME else VOLUME)
        for (r in ev.newlyDue) postBanner(r)

        val island = services.island
        val want = HashMap<String, Pair<ActivityKind, LiveContent.Reminder>>()
        for (r in list.filter { it.state == Reminder.State.DUE }) want[ISLAND_PREFIX + r.id] = ActivityKind.REMINDER to LiveContent.Reminder(r.text, r.dueAtMillis, 0, person(r))
        val missed = list.filter { it.state == Reminder.State.MISSED }
        if (missed.isNotEmpty()) {
            val newest = missed.maxBy { it.dueAtMillis }
            want[ISLAND_MISSED] = ActivityKind.REMINDER_MISSED to LiveContent.Reminder(newest.text, newest.dueAtMillis, missed.size, person(newest))
        }
        for (id in published.keys - want.keys) island.end(id)
        published.keys.retainAll(want.keys)
        for ((id, v) in want) {
            if (published[id] == v.second) continue
            published[id] = v.second
            island.publish(LiveActivity(id, v.first, v.second, actions(id, missed), ownerScreen = ListsFeature.SCREEN_ID))
        }

        // banners only while due
        val due = list.filter { it.state == Reminder.State.DUE }.map { BANNER_PREFIX + it.id }.toSet()
        for (b in banners - due) services.notifications.dismiss(b)
        banners.retainAll(due)
    }

    private fun actions(islandId: String, missed: List<Reminder>): List<IslandAction> {
        if (islandId == ISLAND_MISSED) {
            val only = missed.singleOrNull() ?: return listOf(IslandAction("open", "Show") { services.navigator.show(ListsFeature.SCREEN_ID) })
            return listOf(IslandAction("done", "Done", IslandAction.Style.PRIMARY) { store.complete(only.id) })
        }
        val id = islandId.removePrefix(ISLAND_PREFIX)
        return listOf(
            IslandAction("snooze", "Snooze 10 min") { store.snooze(id, ReminderStore.SNOOZE_MINUTES) },
            IslandAction("done", "Done", IslandAction.Style.PRIMARY) { store.complete(id) },
        )
    }

    private fun postBanner(r: Reminder) {
        val id = BANNER_PREFIX + r.id
        banners += id
        val time = ReminderTime.clock(java.time.Instant.ofEpochMilli(r.dueAtMillis).atZone(ZoneId.systemDefault()).toLocalTime())
        val who = person(r)?.let { "For $it · " } ?: ""
        services.notifications.post(Banner(
            id = id, appName = "Reminders", appColor = VesperColors.BLUE, glyph = "bell",
            title = r.text, body = "${who}Due $time",
            primaryAction = IslandAction("done", "Done", IslandAction.Style.PRIMARY) { store.complete(r.id) },
            actions = listOf(IslandAction("snooze", "Snooze 10 min") { store.snooze(r.id, ReminderStore.SNOOZE_MINUTES) }),
        ))
    }

    private fun person(r: Reminder): String? = r.profileId?.takeIf { it != Profile.SHARED_ID }
        ?.let { pid -> services.profiles.profiles.value.firstOrNull { it.id == pid }?.name }

    companion object {
        const val ISLAND_PREFIX = "reminder:"
        const val ISLAND_MISSED = "reminder:missed"
        const val BANNER_PREFIX = "reminder:"
        const val VOLUME = 0.55f
        const val NIGHT_VOLUME = 0.18f
    }
}
