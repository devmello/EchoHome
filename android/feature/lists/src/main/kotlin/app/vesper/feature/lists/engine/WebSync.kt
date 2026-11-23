package app.vesper.feature.lists.engine

import app.vesper.core.model.ListItem
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderTime
import java.time.ZoneId

/**
 * The settings page edits reminders and the shopping list as two TEXT_LIST settings (one text
 * field per entry, Add / − buttons; stored comma-joined, so entries never contain commas). Pure
 * diffing, unit-tested:
 *  - the store writes its canonical lines ([reminderLines], [itemLines]) after every change;
 *  - when the page saves, the lines it sent are compared with the canonical ones: a missing line
 *    deletes that entry, a new line is added (reminders: parsed with [ReminderTime.parse]).
 */
object WebSync {
    /** Comma-free text for one entry (the store format splits on commas). */
    fun safe(s: String) = s.replace(',', ';').replace(Regex("\\s+"), " ").trim()

    /** "Tue Nov 24 · 7:00 PM · Take out the trash" (prefixed "Missed · " when missed), for each open reminder → id. */
    fun reminderLines(list: List<Reminder>, zone: ZoneId, h24: Boolean): List<Pair<String, String>> =
        list.filter { it.open }.map { r ->
            val prefix = if (r.state == Reminder.State.MISSED) "Missed · " else ""
            safe(prefix + ReminderTime.absolute(r.dueAtMillis, zone, h24) + " · " + r.text) to r.id
        }

    /** The unchecked items (checked ones are bought; they stay on the Echo until cleared) → id. */
    fun itemLines(items: List<ListItem>): List<Pair<String, String>> = items.filter { !it.checked }.map { safe(it.text) to it.id }

    /** What the page's [sent] lines change: ids whose line is gone, and the new lines. Multiset matching. */
    fun diff(current: List<Pair<String, String>>, sent: List<String>): Pair<List<String>, List<String>> {
        val pool = sent.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val removed = ArrayList<String>()
        for ((line, id) in current) {
            val i = pool.indexOf(line)
            if (i >= 0) pool.removeAt(i) else removed += id
        }
        return removed to pool
    }

    /** Splits the stored value ("a,b,c"). */
    fun split(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun join(lines: List<Pair<String, String>>): String = lines.take(50).joinToString(",") { it.first }

    /**
     * A reminder typed on the page: "tomorrow 8am call the dentist", "call mom in 20 minutes".
     * Null when there's no time in it or the time has passed.
     */
    fun parseNew(line: String, now: Long, zone: ZoneId): Pair<String, Long>? {
        // An edited canonical line ("Tue Nov 24 · 8:00 PM · Take out the trash") reads like a typed one.
        val typed = line.removePrefix("Missed · ").replace("·", " ")
            .replace(Regex("^\\s*(?:mon|tue|wed|thu|fri|sat|sun)\\s+(?=[a-z]{3})", RegexOption.IGNORE_CASE), "")
        val p = ReminderTime.parse(typed, now, zone) ?: return null
        if (p.atMillis <= now) return null
        return p.text.ifBlank { "Reminder" } to p.atMillis
    }
}
