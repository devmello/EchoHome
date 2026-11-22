package com.echobedside.feature.system.banner

import com.echobedside.core.model.Banner

/**
 * Which banner is on screen (pure; unit-tested). Fed with `services.notifications.banners`:
 *  - each banner is presented once (again only if it's re-posted with a newer postedAt),
 *  - Do Not Disturb hides non-critical ones (doorbell, alarm and calls are critical),
 *  - a new banner replaces the current one, which tucks behind it (Banners.dc.html),
 *  - compact banners leave after [SHOW_MS]; an expanded one stays until dismissed or collapsed,
 *  - a banner removed from the feed leaves the screen.
 */
class BannerQueue {
    private val seen = HashMap<String, Long>()
    private val queue = ArrayDeque<Banner>()
    var current: Banner? = null; private set
    /** The previous banner, tucked behind the current one while both are fresh. */
    var behind: Banner? = null; private set
    var expanded = false; private set
    private var shownAt = 0L
    /** `system.banners.seconds`. */
    var showMs = SHOW_MS
    /** `system.dnd.critical`: doorbell / alarm / calls still show in Do Not Disturb. */
    var dndAllowsCritical = true

    /** Returns true if what's shown changed. */
    fun update(feed: List<Banner>, dnd: Boolean, now: Long): Boolean {
        val before = Triple(current?.id, behind?.id, expanded)
        val ids = feed.map { it.id }.toSet()
        seen.keys.retainAll(ids)
        queue.removeAll { it.id !in ids }
        if (current != null && current!!.id !in ids) { current = null; expanded = false }
        if (behind != null && behind!!.id !in ids) behind = null
        // refresh content of what's shown (same id re-posted with new text)
        current = current?.let { c -> feed.firstOrNull { it.id == c.id } ?: c }
        for (b in feed) {
            val last = seen[b.id]
            if (last != null && last >= b.postedAtMillis) continue
            seen[b.id] = b.postedAtMillis
            if (dnd && !(b.critical && dndAllowsCritical)) continue
            if (current?.id == b.id) { shownAt = now; continue }
            queue.removeAll { it.id == b.id }
            queue.addLast(b)
        }
        if (!expanded) queue.removeFirstOrNull()?.let { show(it, now) }
        return before != Triple(current?.id, behind?.id, expanded)
    }

    fun tick(now: Long): Boolean {
        val c = current ?: return false
        if (expanded || now < shownAt + showMs) {
            if (behind != null && now >= shownAt + BEHIND_MS) { behind = null; return true }
            return false
        }
        behind = null
        current = null
        queue.removeFirstOrNull()?.let { show(it, now) }
        return c !== current
    }

    fun deadline(): Long? = when {
        current == null -> null
        expanded -> if (behind != null) shownAt + BEHIND_MS else null
        behind != null -> minOf(shownAt + BEHIND_MS, shownAt + showMs)
        else -> shownAt + showMs
    }

    fun expand(): Boolean { if (current == null) return false; expanded = true; behind = null; return true }

    /** Collapse = the banner leaves (the design returns to the screen, not to the compact banner). */
    fun close(now: Long) {
        expanded = false
        current = null
        behind = null
        queue.removeFirstOrNull()?.let { show(it, now) }
    }

    private fun show(b: Banner, now: Long) {
        behind = current?.takeIf { now - shownAt < showMs }
        current = b
        shownAt = now
    }

    companion object {
        /** On screen this long (the design's 2.7 s, longer to read across a room). */
        const val SHOW_MS = 5000L
        /** The tucked-behind banner disappears after this. */
        const val BEHIND_MS = 2500L
    }
}
