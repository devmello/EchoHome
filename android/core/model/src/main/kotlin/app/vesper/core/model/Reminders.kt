package app.vesper.core.model

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reminders and lists (`:feature:lists` binds the real stores; the assistant, the settings page and
 * other screens read them through [VesperServices.reminders] / [VesperServices.lists]).
 */
data class Reminder(
    val id: String,
    val text: String,
    /** Wall clock (System.currentTimeMillis) when it's due. Snoozing moves it. */
    val dueAtMillis: Long,
    /** Whose it is ([Profile.id]); null = shared. */
    val profileId: String? = null,
    val state: State = State.PENDING,
    val createdAtMillis: Long = 0L,
    /** When it was marked done (state DONE). */
    val doneAtMillis: Long? = null,
    val snoozes: Int = 0,
) {
    /**
     * PENDING: waiting for [dueAtMillis]. DUE: ringing now (chime, island, banner with Done / Snooze).
     * MISSED: nobody answered while it was due; it stays until Done or deleted. DONE: checked off.
     */
    enum class State { PENDING, DUE, MISSED, DONE }

    /** Still needs doing (not checked off). */
    val open: Boolean get() = state != State.DONE
}

interface ReminderStore {
    /** Every reminder (pending, due, missed and recently done), sorted by [Reminder.dueAtMillis]. */
    val reminders: StateFlow<List<Reminder>>

    fun add(text: String, dueAtMillis: Long, profileId: String? = null): Reminder
    /** Changes the text and/or time (a new time makes it pending again). Null if there's no such reminder. */
    fun update(id: String, text: String? = null, dueAtMillis: Long? = null): Reminder?
    /** Checks it off. */
    fun complete(id: String)
    /** Back to open (un-checks a done reminder; it rings again only if its time is still ahead). */
    fun reopen(id: String) {}
    /** Due or missed: ring again in [minutes]. */
    fun snooze(id: String, minutes: Int = SNOOZE_MINUTES)
    fun delete(id: String)

    companion object {
        const val SNOOZE_MINUTES = 10
    }
}

/** One entry on a list ("2 lemons"). */
data class ListItem(
    val id: String,
    val text: String,
    val checked: Boolean = false,
    val addedAtMillis: Long = 0L,
)

/** What [ListStore.add] did with each item. */
data class ListAddResult(
    val added: List<ListItem>,
    /** Already on the list (case-insensitive, ignoring quantity and plurals); unchecked if it was checked. */
    val duplicates: List<ListItem>,
    /** Already on the list but with a new quantity ("lemons" → "2 lemons"). */
    val updated: List<ListItem> = emptyList(),
)

/**
 * Named lists ("shopping" by default, see [ListNames]). Items de-duplicate case-insensitively
 * ([ListText.key]), so "Milk" and "milk" are one item and "2 lemons" updates "lemons".
 */
interface ListStore {
    /** List name → items (oldest first). Empty lists may be missing. */
    val lists: StateFlow<Map<String, List<ListItem>>>

    fun items(list: String): List<ListItem> = lists.value[ListNames.normalize(list)].orEmpty()
    fun add(list: String, texts: List<String>): ListAddResult
    /** Removes items matching [texts] ([ListText.key]); returns the removed ones. */
    fun remove(list: String, texts: List<String>): List<ListItem>
    fun setChecked(list: String, id: String, checked: Boolean)
    fun delete(list: String, id: String)
    /** Removes everything, or only the checked items. Returns how many were removed. */
    fun clear(list: String, onlyChecked: Boolean = false): Int
}

/** List names: lower case, without "list"; groceries are the shopping list. */
object ListNames {
    const val SHOPPING = "shopping"
    const val TODO = "todo"

    fun normalize(name: String?): String {
        var n = (name ?: "").trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 '\\-]"), " ").replace(Regex("\\s+"), " ").trim()
        n = n.removePrefix("my ").removePrefix("the ").removeSuffix(" list").removeSuffix(" lists").trim()
        return when (n) {
            "", "shopping", "shop", "grocery", "groceries", "grocery shopping", "supermarket" -> SHOPPING
            "todo", "to do", "to-do", "todos", "to dos", "to-dos", "tasks", "task" -> TODO
            else -> n.take(30)
        }
    }

    /** "Shopping", "To-do", "Packing". */
    fun title(name: String): String = when (name) {
        SHOPPING -> "Shopping"
        TODO -> "To-do"
        else -> name.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

/** Item text rules shared by every list store (pure; unit-tested). */
object ListText {
    const val MAX_LEN = 80
    private val QTY_WORDS = setOf(
        "a", "an", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "some", "few", "couple", "dozen", "half", "pack", "packs", "bag", "bags", "box", "boxes", "bottle", "bottles",
        "can", "cans", "jar", "jars", "carton", "cartons", "loaf", "loaves", "bunch", "bunches", "of", "more", "extra",
        "kg", "g", "lb", "lbs", "oz", "l", "ml", "gallon", "gallons", "pint", "pints", "liter", "liters", "litre", "litres", "x",
    )

    /** Tidies one item ("  milk. " → "milk", the first letter kept as typed). Empty = not an item. */
    fun clean(s: String): String = s.replace(Regex("[\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ")
        .trim().trim('.', ',', ';', '!', '?', '-', '•', '*').trim().take(MAX_LEN)

    /** Splits a typed or spoken entry on commas, semicolons and new lines ("milk, eggs" → two items). */
    fun split(texts: List<String>): List<String> = texts.flatMap { it.split(',', ';', '\n') }.map(::clean).filter { it.isNotEmpty() }

    /** Words, lower case, without a leading quantity ("2 lemons", "a dozen eggs") or plural s. Same key = same item. */
    fun key(s: String): String {
        val words = clean(s).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < words.size - 1 && (words[i] in QTY_WORDS || words[i].any(Char::isDigit))) i++
        return words.drop(i).joinToString(" ") { singular(it) }
    }

    /** True if [s] starts with a quantity ("2 lemons", "a dozen eggs"). */
    fun hasQuantity(s: String): Boolean {
        val first = clean(s).lowercase(Locale.ROOT).substringBefore(' ')
        return clean(s).contains(' ') && (first.any(Char::isDigit) || first in QTY_WORDS)
    }

    private fun singular(w: String): String = when {
        w.length <= 3 -> w
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("ie") -> w.dropLast(2) + "y"
        w.endsWith("oe") -> w.dropLast(1)
        w.endsWith("oes") || w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") -> w.dropLast(2)
        w.endsWith("ss") || w.endsWith("us") -> w
        w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    /**
     * Adds [texts] to [items] with de-duplication: a known key is a duplicate (un-checked if it was
     * checked, and its text replaced if the new one has a quantity). Returns the new list and what happened.
     */
    fun add(items: List<ListItem>, texts: List<String>, now: Long, newId: () -> String = { UUID.randomUUID().toString().take(8) }): Pair<List<ListItem>, ListAddResult> {
        val out = items.toMutableList()
        val added = ArrayList<ListItem>(); val dup = ArrayList<ListItem>(); val upd = ArrayList<ListItem>()
        for (t in split(texts)) {
            val k = key(t)
            if (k.isEmpty()) continue
            val i = out.indexOfFirst { key(it.text) == k }
            if (i < 0) {
                val item = ListItem(newId(), t, false, now)
                out += item; added += item
                continue
            }
            val old = out[i]
            val newText = if (hasQuantity(t) && !t.equals(old.text, ignoreCase = true)) t else old.text
            val item = old.copy(text = newText, checked = false)
            out[i] = item
            if (newText != old.text) upd += item else dup += item
        }
        return out to ListAddResult(added, dup, upd)
    }

    /** Removes the items whose key matches one of [texts]. */
    fun remove(items: List<ListItem>, texts: List<String>): Pair<List<ListItem>, List<ListItem>> {
        val keys = split(texts).map(::key).filter { it.isNotEmpty() }.toSet()
        val (gone, kept) = items.partition { key(it.text) in keys }
        return kept to gone
    }
}

/** For tests and until `:feature:lists` binds the persisted store. */
class InMemoryReminderStore(private val now: () -> Long = System::currentTimeMillis) : ReminderStore {
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    override val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private fun set(l: List<Reminder>) { _reminders.value = l.sortedBy { it.dueAtMillis } }

    override fun add(text: String, dueAtMillis: Long, profileId: String?): Reminder {
        val r = Reminder("r-" + UUID.randomUUID().toString().take(8), text, dueAtMillis, profileId, createdAtMillis = now())
        set(_reminders.value + r)
        return r
    }

    override fun update(id: String, text: String?, dueAtMillis: Long?): Reminder? {
        val old = _reminders.value.firstOrNull { it.id == id } ?: return null
        val r = old.copy(text = text ?: old.text, dueAtMillis = dueAtMillis ?: old.dueAtMillis,
            state = if (dueAtMillis != null) Reminder.State.PENDING else old.state)
        set(_reminders.value.map { if (it.id == id) r else it })
        return r
    }

    override fun complete(id: String) = set(_reminders.value.map { if (it.id == id) it.copy(state = Reminder.State.DONE, doneAtMillis = now()) else it })
    override fun reopen(id: String) = set(_reminders.value.map { if (it.id == id) it.copy(state = Reminder.State.PENDING, doneAtMillis = null) else it })
    override fun snooze(id: String, minutes: Int) = set(_reminders.value.map {
        if (it.id == id) it.copy(state = Reminder.State.PENDING, dueAtMillis = now() + minutes * 60_000L, snoozes = it.snoozes + 1) else it
    })
    override fun delete(id: String) = set(_reminders.value.filterNot { it.id == id })
}

class InMemoryListStore(private val now: () -> Long = System::currentTimeMillis) : ListStore {
    private val _lists = MutableStateFlow<Map<String, List<ListItem>>>(emptyMap())
    override val lists: StateFlow<Map<String, List<ListItem>>> = _lists.asStateFlow()

    private fun edit(list: String, f: (List<ListItem>) -> List<ListItem>) {
        val n = ListNames.normalize(list)
        val m = _lists.value.toMutableMap()
        val l = f(m[n].orEmpty())
        if (l.isEmpty()) m.remove(n) else m[n] = l
        _lists.value = m
    }

    override fun add(list: String, texts: List<String>): ListAddResult {
        var res = ListAddResult(emptyList(), emptyList())
        edit(list) { val (l, r) = ListText.add(it, texts, now()); res = r; l }
        return res
    }

    override fun remove(list: String, texts: List<String>): List<ListItem> {
        var gone = emptyList<ListItem>()
        edit(list) { val (l, g) = ListText.remove(it, texts); gone = g; l }
        return gone
    }

    override fun setChecked(list: String, id: String, checked: Boolean) = edit(list) { l -> l.map { if (it.id == id) it.copy(checked = checked) else it } }
    override fun delete(list: String, id: String) = edit(list) { l -> l.filterNot { it.id == id } }
    override fun clear(list: String, onlyChecked: Boolean): Int {
        var n = 0
        edit(list) { l -> val kept = if (onlyChecked) l.filterNot { it.checked } else emptyList(); n = l.size - kept.size; kept }
        return n
    }
}
