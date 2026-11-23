package app.vesper.feature.lists.engine

import app.vesper.core.model.ListItem
import app.vesper.core.model.ListNames
import app.vesper.core.model.Reminder
import org.json.JSONArray
import org.json.JSONObject

/** filesDir/reminders.json and filesDir/lists.json (versioned; unknown fields ignored, bad entries skipped). */
object Codec {
    const val VERSION = 1

    fun remindersToJson(list: List<Reminder>): String = JSONObject()
        .put("version", VERSION)
        .put("reminders", JSONArray().apply {
            for (r in list) put(JSONObject()
                .put("id", r.id).put("text", r.text).put("due", r.dueAtMillis)
                .put("state", r.state.name).put("created", r.createdAtMillis)
                .put("snoozes", r.snoozes)
                .also { o -> r.profileId?.let { o.put("profile", it) }; r.doneAtMillis?.let { o.put("done", it) } })
        }).toString()

    fun remindersFromJson(s: String): List<Reminder> {
        val arr = JSONObject(s).optJSONArray("reminders") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            val due = o.optLong("due", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            Reminder(
                id = id, text = o.optString("text").ifBlank { "Reminder" }, dueAtMillis = due,
                profileId = if (o.has("profile") && !o.isNull("profile")) o.getString("profile") else null,
                state = runCatching { Reminder.State.valueOf(o.optString("state")) }.getOrDefault(Reminder.State.PENDING),
                createdAtMillis = o.optLong("created", 0L),
                doneAtMillis = if (o.has("done")) o.getLong("done") else null,
                snoozes = o.optInt("snoozes", 0),
            )
        }
    }

    fun listsToJson(lists: Map<String, List<ListItem>>): String = JSONObject()
        .put("version", VERSION)
        .put("lists", JSONObject().apply {
            for ((name, items) in lists) put(name, JSONArray().apply {
                for (it in items) put(JSONObject().put("id", it.id).put("text", it.text).put("checked", it.checked).put("added", it.addedAtMillis))
            })
        }).toString()

    fun listsFromJson(s: String): Map<String, List<ListItem>> {
        val o = JSONObject(s).optJSONObject("lists") ?: return emptyMap()
        val out = LinkedHashMap<String, List<ListItem>>()
        for (name in o.keys()) {
            val arr = o.optJSONArray(name) ?: continue
            val items = (0 until arr.length()).mapNotNull { i ->
                val x = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = x.optString("id").ifEmpty { return@mapNotNull null }
                val text = x.optString("text").ifBlank { return@mapNotNull null }
                ListItem(id, text, x.optBoolean("checked", false), x.optLong("added", 0L))
            }
            if (items.isNotEmpty()) out[ListNames.normalize(name)] = items
        }
        return out
    }
}
