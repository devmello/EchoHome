package app.vesper.feature.lists.ui

import app.vesper.core.model.ListItem
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderTime
import java.time.ZoneId

/** One row on the Lists screen (pure; unit-tested). */
internal data class Row(
    val id: String,
    val text: String,
    val checked: Boolean,
    /** Right-hand label ("7:00 PM", "Tomorrow 9:00 AM", "Missed · 7:00 PM"); empty for list items. */
    val label: String = "",
    val tone: Tone = Tone.NORMAL,
    /** The person's initial and colour (reminders of a recognised person). */
    val person: Pair<String, Int>? = null,
) {
    enum class Tone { NORMAL, DUE, MISSED }
}

internal object ListsModel {
    /** Open reminders by time (due and missed ones first), then the checked-off ones. */
    fun reminderRows(list: List<Reminder>, now: Long, zone: ZoneId, h24: Boolean, person: (String?) -> Pair<String, Int>? = { null }): List<Row> {
        val open = list.filter { it.open }.sortedWith(compareBy<Reminder>({ rank(it.state) }, { it.dueAtMillis }))
        val done = list.filter { !it.open }.sortedByDescending { it.doneAtMillis ?: it.dueAtMillis }
        return (open + done).map { r ->
            val time = ReminderTime.describe(r.dueAtMillis, now, zone, h24)
            when (r.state) {
                Reminder.State.DUE -> Row(r.id, r.text, false, "Now", Row.Tone.DUE, person(r.profileId))
                Reminder.State.MISSED -> Row(r.id, r.text, false, "Missed · $time", Row.Tone.MISSED, person(r.profileId))
                Reminder.State.DONE -> Row(r.id, r.text, true, time, Row.Tone.NORMAL, person(r.profileId))
                Reminder.State.PENDING -> Row(r.id, r.text, false, time, Row.Tone.NORMAL, person(r.profileId))
            }
        }
    }

    private fun rank(s: Reminder.State) = when (s) { Reminder.State.DUE -> 0; Reminder.State.MISSED -> 1; else -> 2 }

    /** Items to get first (in the order added), checked ones at the bottom. */
    fun itemRows(items: List<ListItem>): List<Row> =
        (items.filter { !it.checked } + items.filter { it.checked }).map { Row(it.id, it.text, it.checked) }

    /** "2 reminders · 5 to buy" under the title. */
    fun summary(reminders: List<Reminder>, items: List<ListItem>, listTitle: String): String {
        val open = reminders.count { it.open }
        val missed = reminders.count { it.state == Reminder.State.MISSED }
        val left = items.count { !it.checked }
        val r = when {
            open == 0 -> "No reminders"
            missed > 0 -> "$open reminder${if (open == 1) "" else "s"} · $missed missed"
            else -> "$open reminder${if (open == 1) "" else "s"}"
        }
        val l = when {
            items.isEmpty() -> "$listTitle list empty"
            left == 0 -> "All checked off"
            listTitle == "Shopping" -> "$left to buy"
            else -> "$left to do"
        }
        return "$r · $l"
    }
}
