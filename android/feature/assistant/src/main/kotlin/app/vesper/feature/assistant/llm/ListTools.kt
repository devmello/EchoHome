package app.vesper.feature.assistant.llm

import app.vesper.core.model.ListItem
import app.vesper.core.model.ListNames
import app.vesper.core.model.Profile
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderTime
import app.vesper.core.model.VesperServices
import app.vesper.feature.assistant.llm.Schema.bool
import app.vesper.feature.assistant.llm.Schema.int
import app.vesper.feature.assistant.llm.Schema.props
import app.vesper.feature.assistant.llm.Schema.str
import app.vesper.feature.assistant.pipeline.ResultCard
import app.vesper.feature.assistant.pipeline.ResultCard.Icon
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reminders and lists (services.reminders / services.lists, `:feature:lists`): add, list, change,
 * complete and cancel reminders; add to, read, remove from and clear a list ("shopping" by default).
 * Same rules as [VesperTools]: runs on the main thread, slots read at call time.
 *
 * Times: `at` is a local date-time ("2026-11-24T19:00") or a time of day ("19:00", the next one);
 * `in_minutes` is relative; `when` takes the user's words ("tomorrow morning") and is parsed by
 * [ReminderTime]. Exactly one is needed.
 */
internal class ListTools(
    private val zone: () -> ZoneId,
    private val wall: () -> Long,
) {
    /** null = not one of these tools. */
    fun run(s: VesperServices, name: String, input: JSONObject): ToolOutcome? = when (name) {
        "add_reminder" -> addReminder(s, input)
        "list_reminders" -> listReminders(s, input)
        "update_reminder" -> updateReminder(s, input)
        "complete_reminder" -> completeReminder(s, input)
        "cancel_reminder" -> cancelReminder(s, input)
        "list_add" -> listAdd(s, input)
        "list_remove" -> listRemove(s, input)
        "list_read" -> listRead(s, input)
        "list_clear" -> listClear(s, input)
        else -> null
    }

    // ---- reminders ----

    /** The time from `at` / `in_minutes` / `when`, or an error to give back. */
    private fun time(input: JSONObject): Pair<Long?, ToolOutcome?> {
        val now = wall()
        val at = when {
            input.has("in_minutes") -> now + input.getLong("in_minutes") * 60_000L
            input.optString("at").isNotBlank() -> ReminderTime.at(input.getString("at"), now, zone())
                ?: return null to ToolOutcome.error("BAD_TIME", "Couldn't read at=\"${input.getString("at").take(40)}\"; use a local date-time like 2026-11-24T19:00.")
            input.optString("when").isNotBlank() -> ReminderTime.parse(input.getString("when"), now, zone())?.atMillis
                ?: return null to ToolOutcome.error("BAD_TIME", "Couldn't find a time in \"${input.getString("when").take(60)}\"; give at or in_minutes.")
            else -> return null to ToolOutcome.error("NEEDS_TIME", "Say when: give at, in_minutes or when. If the user didn't say, ask them.")
        }
        if (at <= now) return null to ToolOutcome.error("PAST_TIME", "${ReminderTime.iso(at, zone())} has already passed; pick a time in the future.")
        if (at - now > MAX_AHEAD_MS) return null to ToolOutcome.error("TOO_FAR", "Reminders can be at most a year ahead.")
        return at to null
    }

    private fun addReminder(s: VesperServices, input: JSONObject): ToolOutcome {
        val text = ReminderTime.cleanText(input.getString("text")).ifEmpty { return ToolOutcome.error("INVALID_INPUT", "What should the reminder say?") }
        if (s.reminders.reminders.value.count { it.open } >= MAX_OPEN) return ToolOutcome.error("TOO_MANY", "There are already $MAX_OPEN open reminders.")
        val (at, err) = time(input)
        if (err != null) return err
        val active = s.profiles.active.value
        val pid = if (input.optBoolean("for_everyone", false)) null else active.id.takeIf { it != Profile.SHARED_ID }
        val r = s.reminders.add(text, at!!, pid)
        return ToolOutcome(JSONObject().put("reminder", reminderJson(s, r)),
            card = ResultCard.Action(r.text, ReminderTime.describe(r.dueAtMillis, wall(), zone()), Icon.REMINDER))
    }

    private fun listReminders(s: VesperServices, input: JSONObject): ToolOutcome {
        val all = input.optBoolean("include_done", false)
        val list = s.reminders.reminders.value.filter { all || it.open }
        val open = list.filter { it.open }
        val next = open.filter { it.state == Reminder.State.PENDING }.minByOrNull { it.dueAtMillis }
        val missed = open.count { it.state == Reminder.State.MISSED }
        val state = when {
            open.isEmpty() -> "None"
            missed > 0 -> "${open.size} open · $missed missed"
            next != null -> "${open.size} open · next ${ReminderTime.describe(next.dueAtMillis, wall(), zone())}"
            else -> "${open.size} open"
        }
        return ToolOutcome(JSONObject().put("reminders", JSONArray(list.map { reminderJson(s, it) })).put("now", ReminderTime.iso(wall(), zone())),
            card = ResultCard.Action("Reminders", state, Icon.REMINDER))
    }

    /** By reminder_id, else by text (case-insensitive, then contains), else the only open one. */
    private fun find(s: VesperServices, input: JSONObject, openOnly: Boolean = true): Reminder? {
        val list = s.reminders.reminders.value.filter { !openOnly || it.open }
        val id = input.optString("reminder_id", "")
        val text = input.optString("text", "").trim()
        return when {
            id.isNotEmpty() -> list.firstOrNull { it.id == id }
            text.isNotEmpty() -> list.firstOrNull { it.text.equals(text, true) }
                ?: list.filter { it.text.contains(text, true) || text.contains(it.text, true) }.singleOrNull()
            else -> list.singleOrNull()
        }
    }

    private fun notFound(s: VesperServices): ToolOutcome {
        val open = s.reminders.reminders.value.filter { it.open }
        return ToolOutcome.error("NOT_FOUND", if (open.isEmpty()) "There are no open reminders." else "Say which one (reminder_id from list_reminders): ${open.take(8).joinToString { "\"${it.text}\"" }}")
    }

    private fun updateReminder(s: VesperServices, input: JSONObject): ToolOutcome {
        val old = s.reminders.reminders.value.firstOrNull { it.id == input.getString("reminder_id") } ?: return notFound(s)
        val newText = input.optString("new_text", "").let(ReminderTime::cleanText).ifEmpty { null }
        val timeGiven = input.has("in_minutes") || input.optString("at").isNotBlank() || input.optString("when").isNotBlank()
        var at: Long? = null
        if (timeGiven) { val (t, err) = time(input); if (err != null) return err; at = t }
        if (newText == null && at == null) return ToolOutcome.error("NOTHING_TO_CHANGE", "Give new_text or a new time.")
        val r = s.reminders.update(old.id, newText, at) ?: return notFound(s)
        return ToolOutcome(JSONObject().put("reminder", reminderJson(s, r)),
            card = ResultCard.Action(r.text, ReminderTime.describe(r.dueAtMillis, wall(), zone()), Icon.REMINDER))
    }

    private fun completeReminder(s: VesperServices, input: JSONObject): ToolOutcome {
        val r = find(s, input) ?: return notFound(s)
        s.reminders.complete(r.id)
        return ToolOutcome(JSONObject().put("completed", r.id).put("text", r.text), card = ResultCard.Action(r.text, "Done", Icon.REMINDER))
    }

    private fun cancelReminder(s: VesperServices, input: JSONObject): ToolOutcome {
        val r = find(s, input, openOnly = false) ?: return notFound(s)
        s.reminders.delete(r.id)
        return ToolOutcome(JSONObject().put("cancelled", r.id).put("text", r.text), card = ResultCard.Action(r.text, "Cancelled", Icon.REMINDER))
    }

    private fun reminderJson(s: VesperServices, r: Reminder): JSONObject = JSONObject()
        .put("reminder_id", r.id).put("text", r.text)
        .put("at", ReminderTime.iso(r.dueAtMillis, zone()))
        .put("state", r.state.name.lowercase())
        .also { o ->
            r.profileId?.let { pid -> o.put("for", s.profiles.profiles.value.firstOrNull { it.id == pid }?.name ?: pid) }
            if (r.snoozes > 0) o.put("snoozed", r.snoozes)
        }

    // ---- lists ----

    private fun listName(input: JSONObject) = ListNames.normalize(input.optString("list", ListNames.SHOPPING))

    private fun texts(input: JSONObject): List<String> {
        val a = input.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optString(it, null)?.takeIf { s -> s.isNotBlank() } }
    }

    private fun listAdd(s: VesperServices, input: JSONObject): ToolOutcome {
        val name = listName(input)
        val items = texts(input).ifEmpty { return ToolOutcome.error("INVALID_INPUT", "Give the items to add.") }
        val r = s.lists.add(name, items)
        val title = ListNames.title(name)
        val n = r.added.size + r.updated.size
        val state = when {
            n == 0 && r.duplicates.isNotEmpty() -> "Already on it"
            r.added.size == 1 && r.updated.isEmpty() -> "Added ${r.added[0].text}".let { if (it.length > 28) "Added 1 item" else it }
            else -> "Added $n item${if (n == 1) "" else "s"}"
        }
        return ToolOutcome(JSONObject().put("list", name)
            .put("added", JSONArray(r.added.map { it.text }))
            .put("already_on_list", JSONArray(r.duplicates.map { it.text }))
            .put("quantity_updated", JSONArray(r.updated.map { it.text }))
            .put("count", s.lists.items(name).count { !it.checked }),
            card = ResultCard.Action("$title list", state, Icon.LIST))
    }

    private fun listRemove(s: VesperServices, input: JSONObject): ToolOutcome {
        val name = listName(input)
        val items = texts(input).ifEmpty { return ToolOutcome.error("INVALID_INPUT", "Give the items to remove.") }
        val gone = s.lists.remove(name, items)
        if (gone.isEmpty()) return ToolOutcome.error("NOT_FOUND", "Not on the ${ListNames.title(name).lowercase()} list. It has: " +
            s.lists.items(name).take(20).joinToString { it.text }.ifEmpty { "nothing" })
        return ToolOutcome(JSONObject().put("list", name).put("removed", JSONArray(gone.map { it.text })),
            card = ResultCard.Action("${ListNames.title(name)} list", "Removed ${if (gone.size == 1) gone[0].text.take(18) else "${gone.size} items"}", Icon.LIST))
    }

    private fun listRead(s: VesperServices, input: JSONObject): ToolOutcome {
        val name = listName(input)
        val items = s.lists.items(name)
        val open = items.filter { !it.checked }
        val o = JSONObject().put("list", name)
            .put("items", JSONArray(open.map(ListItem::text)))
            .put("checked_off", JSONArray(items.filter { it.checked }.map(ListItem::text)))
        val others = s.lists.lists.value.keys.filter { it != name }
        if (others.isNotEmpty()) o.put("other_lists", JSONArray(others))
        return ToolOutcome(o, card = ResultCard.Action("${ListNames.title(name)} list",
            if (open.isEmpty()) "Empty" else "${open.size} item${if (open.size == 1) "" else "s"}", Icon.LIST))
    }

    private fun listClear(s: VesperServices, input: JSONObject): ToolOutcome {
        val name = listName(input)
        val onlyChecked = input.optBoolean("only_checked", false)
        val items = s.lists.items(name)
        if (items.isEmpty()) return ToolOutcome(JSONObject().put("list", name).put("cleared", 0), card = ResultCard.Action("${ListNames.title(name)} list", "Already empty", Icon.LIST))
        if (!onlyChecked && !input.getBoolean("confirmed")) return ToolOutcome.error("NEEDS_CONFIRMATION",
            "Not cleared. Ask the user to confirm clearing all ${items.size} items from the ${ListNames.title(name).lowercase()} list, then call again with confirmed=true.")
        val n = s.lists.clear(name, onlyChecked)
        return ToolOutcome(JSONObject().put("list", name).put("cleared", n),
            card = ResultCard.Action("${ListNames.title(name)} list", if (onlyChecked) "Cleared $n checked" else "Cleared", Icon.LIST))
    }

    companion object {
        const val MAX_OPEN = 100
        const val MAX_AHEAD_MS = 366L * 24 * 3_600_000L

        private fun timeProps() = arrayOf(
            "at" to str("Local date-time from the context's local time, e.g. 2026-11-24T19:00, or just a time of day like 19:00 (the next one)."),
            "in_minutes" to int("Minutes from now, for \"in 20 minutes\" / \"in an hour\".", 1, 525_600),
            "when" to str("Or the user's own words for the time (\"tomorrow morning\", \"tonight\"), when converting is unclear."),
        )

        private val LIST = "list" to str("Which list; default shopping (groceries are the shopping list). Others: todo, or any name like packing.")
        private val ITEMS = "items" to JSONObject().put("type", "array").put("description", "One entry per item, as the user said it (\"2 lemons\"); split \"milk and eggs\" into two.")
            .put("items", JSONObject().put("type", "string").put("maxLength", 80))

        val SPECS = listOf(
            ToolSpec("add_reminder", "Set a reminder on this display: at its time it chimes and shows with Done / Snooze. Give the time as at, in_minutes or when. It belongs to the recognised person unless for_everyone.",
                props("text" to str("What to remind about, short, without \"remind me to\" (e.g. \"Take out the trash\").", max = 120), *timeProps(),
                    "for_everyone" to bool("A household reminder rather than the active person's.")), listOf("text")),
            ToolSpec("list_reminders", "List the open reminders (pending, due now, missed) with ids and times.",
                props("include_done" to bool("Also the ones checked off today."))),
            ToolSpec("update_reminder", "Change a reminder's time or text (a new time makes it ring then).",
                props("reminder_id" to str("From list_reminders."), "new_text" to str("New text.", max = 120), *timeProps()), listOf("reminder_id")),
            ToolSpec("complete_reminder", "Mark a reminder done. Give reminder_id or its text; with neither, the only open one.",
                props("reminder_id" to str("From list_reminders."), "text" to str("The reminder's text."))),
            ToolSpec("cancel_reminder", "Delete a reminder the user no longer wants. Give reminder_id or its text; with neither, the only one.",
                props("reminder_id" to str("From list_reminders."), "text" to str("The reminder's text."))),
            ToolSpec("list_add", "Add items to a list (the shopping list by default). Items already on it aren't added twice.",
                props(ITEMS, LIST), listOf("items")),
            ToolSpec("list_remove", "Remove items from a list (shopping by default), e.g. when bought or not needed.",
                props(ITEMS, LIST), listOf("items")),
            ToolSpec("list_read", "What's on a list (shopping by default): items still to get and ones checked off.",
                props(LIST)),
            ToolSpec("list_clear", "Clear a list (shopping by default). only_checked removes just the checked-off items. Clearing everything needs confirmed=true: only if the user plainly asked to clear the list or just confirmed.",
                props(LIST, "only_checked" to bool("Remove only checked-off items."),
                    "confirmed" to bool("True only if the user plainly asked to clear the whole list or just confirmed it.")), listOf("confirmed")),
        )
    }
}
