package app.vesper.core.model

/**
 * Values of a [SettingType.ORDER] setting: an ordering of choices, each on or off, stored as
 * "a,-b,c" ("-" = off). Pure functions, shared by the pager and the settings page.
 */
object OrderValue {
    data class Item(val id: String, val on: Boolean)

    /** Every entry of [stored] as written (trimmed, empty ones dropped); unknown ids and duplicates kept. */
    fun parse(stored: String): List<Item> = stored.split(',').mapNotNull { raw ->
        val t = raw.trim()
        val off = t.startsWith("-")
        val id = t.removePrefix("-").trim()
        if (id.isEmpty()) null else Item(id, !off)
    }

    fun format(items: List<Item>): String = items.joinToString(",") { if (it.on) it.id else "-${it.id}" }

    /**
     * [stored] laid over [options] (the known ids, in their default order). Ids that aren't
     * options are dropped, the first of duplicates wins, and options missing from [stored] (new
     * in a later version, or not available when it was saved) are added switched on, right after
     * the option that comes before them by default (or first, when none does).
     */
    fun resolve(options: List<String>, stored: String): List<Item> {
        val known = options.toSet()
        val out = ArrayList<Item>(options.size)
        val seen = HashSet<String>()
        for (it in parse(stored)) if (it.id in known && seen.add(it.id)) out += it
        for ((k, id) in options.withIndex()) {
            if (id in seen) continue
            var at = 0
            for (j in k - 1 downTo 0) {
                val i = out.indexOfFirst { it.id == options[j] }
                if (i >= 0) { at = i + 1; break }
            }
            out.add(at, Item(id, true))
            seen += id
        }
        return out
    }
}

/**
 * Which screens are pages of the launcher's horizontal pager, in which order: the user's choice
 * (setting [KEY], the web page's Screens section) over the registered [ScreenSpec]s. StandBy
 * ([FIRST]) is always the first page and can't be switched off. A screen that isn't registered
 * (its feature isn't configured, e.g. Home without Home Assistant) stays out; its stored place is
 * kept for when it comes back.
 */
object PagerScreens {
    const val KEY = "pager.screens"
    const val FIRST = "standby"

    /** Screens that can be pages, in their default order ([ScreenSpec.order]), StandBy first. */
    fun candidates(screens: List<ScreenSpec>): List<ScreenSpec> {
        val all = screens.filter { it.inPager }.sortedBy { it.order }.distinctBy { it.id }
        return all.filter { it.id == FIRST } + all.filter { it.id != FIRST }
    }

    /** The choosable ones (all but StandBy) as id to title, in default order: the ORDER setting's options. */
    fun options(screens: List<ScreenSpec>): List<Pair<String, String>> =
        candidates(screens).filter { it.id != FIRST }.map { it.id to (it.title ?: it.id) }

    /** The pager's pages for [stored], the value of [KEY] ("" = the default set and order). */
    fun pages(screens: List<ScreenSpec>, stored: String): List<ScreenSpec> {
        val all = candidates(screens)
        val byId = all.associateBy { it.id }
        val rest = OrderValue.resolve(all.filter { it.id != FIRST }.map { it.id }, stored)
        return all.filter { it.id == FIRST } + rest.filter { it.on }.mapNotNull { byId[it.id] }
    }
}
