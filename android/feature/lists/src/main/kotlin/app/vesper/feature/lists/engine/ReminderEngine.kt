package app.vesper.feature.lists.engine

import app.vesper.core.model.Reminder
import app.vesper.core.model.Reminder.State
import java.util.UUID

/** One evaluation: the advanced list, what just fell due (chime + banner), and when to wake next. */
data class Evaluation(
    val reminders: List<Reminder>,
    /** PENDING → DUE in this evaluation (chime, banner). Reminders that fell due long ago go straight to MISSED instead. */
    val newlyDue: List<Reminder>,
    /** Wall clock of the next state change (a reminder falls due, a due one becomes missed); null = nothing ahead. */
    val nextWakeAt: Long?,
)

/**
 * The reminder state machine (pure; unit-tested). Time only moves forward through [evaluate]:
 *  - PENDING at its time → DUE (rings: chime, island, banner) for [RING_MS]; if it fell due more than
 *    [RING_MS] ago (the Echo was off, the app restarted) it goes straight to MISSED, silently.
 *  - DUE with no answer for [RING_MS] → MISSED (stays until Done or deleted).
 *  - DONE ones are kept [KEEP_DONE_MS] (shown checked, can be un-checked), MISSED ones [KEEP_MISSED_MS].
 * User actions ([add], [complete], [snooze], …) return the new list; the store evaluates right after.
 */
object ReminderEngine {
    const val RING_MS = 5 * 60_000L
    const val KEEP_DONE_MS = 24 * 3_600_000L
    const val KEEP_MISSED_MS = 7 * 24 * 3_600_000L
    const val MAX_TEXT = 120
    /** More than this many open reminders is surely a mistake (a loop): refuse new ones. */
    const val MAX_OPEN = 100

    fun evaluate(list: List<Reminder>, now: Long): Evaluation {
        val out = ArrayList<Reminder>(list.size)
        val newly = ArrayList<Reminder>()
        var next: Long? = null
        fun wake(at: Long) { if (at > now && (next == null || at < next!!)) next = at }
        for (r in list) {
            when (r.state) {
                State.PENDING -> when {
                    r.dueAtMillis > now -> { out += r; wake(r.dueAtMillis) }
                    now - r.dueAtMillis < RING_MS -> {
                        val d = r.copy(state = State.DUE)
                        out += d; newly += d; wake(r.dueAtMillis + RING_MS)
                    }
                    else -> out += r.copy(state = State.MISSED)
                }
                State.DUE -> if (now - r.dueAtMillis >= RING_MS) out += r.copy(state = State.MISSED) else { out += r; wake(r.dueAtMillis + RING_MS) }
                State.MISSED -> if (now - r.dueAtMillis < KEEP_MISSED_MS) out += r
                State.DONE -> if (now - (r.doneAtMillis ?: r.dueAtMillis) < KEEP_DONE_MS) out += r
            }
        }
        return Evaluation(sorted(out), newly, next)
    }

    fun sorted(l: List<Reminder>): List<Reminder> = l.sortedWith(compareBy<Reminder>({ it.dueAtMillis }, { it.createdAtMillis }, { it.id }))

    fun newReminder(text: String, dueAt: Long, profileId: String?, now: Long, id: String = "r-" + UUID.randomUUID().toString().take(8)) =
        Reminder(id, cleanText(text), dueAt, profileId, State.PENDING, createdAtMillis = now)

    fun cleanText(s: String): String = s.replace(Regex("[\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_TEXT).ifEmpty { "Reminder" }

    fun update(list: List<Reminder>, id: String, text: String?, dueAt: Long?): List<Reminder> = list.map { r ->
        if (r.id != id) r else r.copy(
            text = text?.let(::cleanText) ?: r.text,
            dueAtMillis = dueAt ?: r.dueAtMillis,
            // a new time makes it pending again (it rings at the new time)
            state = if (dueAt != null) State.PENDING else r.state,
            doneAtMillis = if (dueAt != null) null else r.doneAtMillis,
        )
    }

    fun complete(list: List<Reminder>, id: String, now: Long) = list.map { if (it.id == id && it.state != State.DONE) it.copy(state = State.DONE, doneAtMillis = now) else it }

    /** Un-checks: rings again if its time is ahead, otherwise it's a missed one (no chime). */
    fun reopen(list: List<Reminder>, id: String, now: Long) = list.map {
        if (it.id != id || it.state != State.DONE) it
        else it.copy(state = if (it.dueAtMillis > now) State.PENDING else State.MISSED, doneAtMillis = null)
    }

    fun snooze(list: List<Reminder>, id: String, minutes: Int, now: Long) = list.map {
        if (it.id == id) it.copy(state = State.PENDING, dueAtMillis = now + minutes.coerceIn(1, 24 * 60) * 60_000L, doneAtMillis = null, snoozes = it.snoozes + 1) else it
    }

    fun delete(list: List<Reminder>, id: String) = list.filterNot { it.id == id }
}
