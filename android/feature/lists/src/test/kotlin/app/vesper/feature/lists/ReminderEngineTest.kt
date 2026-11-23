package app.vesper.feature.lists

import app.vesper.core.model.ListItem
import app.vesper.core.model.Reminder
import app.vesper.core.model.Reminder.State
import app.vesper.feature.lists.engine.Codec
import app.vesper.feature.lists.engine.ReminderEngine
import app.vesper.feature.lists.engine.ReminderEngine.RING_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderEngineTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    private fun r(id: String, due: Long, state: State = State.PENDING, done: Long? = null) =
        Reminder(id, "R $id", due, null, state, createdAtMillis = t0 - 1000, doneAtMillis = done)

    @Test fun pendingWakesAtTheEarliestDueTime() {
        val ev = ReminderEngine.evaluate(listOf(r("b", t0 + 30 * min), r("a", t0 + 10 * min)), t0)
        assertEquals(listOf("a", "b"), ev.reminders.map { it.id })
        assertTrue(ev.newlyDue.isEmpty())
        assertEquals(t0 + 10 * min, ev.nextWakeAt)
    }

    @Test fun fallsDueRingsThenBecomesMissed() {
        val due = t0 + 10 * min
        val ev1 = ReminderEngine.evaluate(listOf(r("a", due)), due + 200)
        assertEquals(State.DUE, ev1.reminders[0].state)
        assertEquals(listOf("a"), ev1.newlyDue.map { it.id })
        assertEquals(due + RING_MS, ev1.nextWakeAt)
        // evaluating again doesn't ring again
        val ev2 = ReminderEngine.evaluate(ev1.reminders, due + 60_000)
        assertTrue(ev2.newlyDue.isEmpty())
        assertEquals(State.DUE, ev2.reminders[0].state)
        // nobody answered
        val ev3 = ReminderEngine.evaluate(ev2.reminders, due + RING_MS)
        assertEquals(State.MISSED, ev3.reminders[0].state)
        assertNull(ev3.nextWakeAt)
    }

    @Test fun fellDueWhileOffIsMissedSilently() {
        // e.g. after a reboot: due an hour ago
        val ev = ReminderEngine.evaluate(listOf(r("a", t0 - 60 * min)), t0)
        assertEquals(State.MISSED, ev.reminders[0].state)
        assertTrue(ev.newlyDue.isEmpty())
    }

    @Test fun snoozeAndDoneAndReopen() {
        val due = listOf(r("a", t0 - min, State.DUE))
        val snoozed = ReminderEngine.snooze(due, "a", 10, t0)
        assertEquals(State.PENDING, snoozed[0].state)
        assertEquals(t0 + 10 * min, snoozed[0].dueAtMillis)
        assertEquals(1, snoozed[0].snoozes)
        assertEquals(t0 + 10 * min, ReminderEngine.evaluate(snoozed, t0).nextWakeAt)

        val done = ReminderEngine.complete(due, "a", t0)
        assertEquals(State.DONE, done[0].state)
        assertNull(ReminderEngine.evaluate(done, t0).nextWakeAt)
        // un-checking a past one makes it missed (no second chime)
        assertEquals(State.MISSED, ReminderEngine.reopen(done, "a", t0)[0].state)
        // and a future one pending again
        val futureDone = ReminderEngine.complete(listOf(r("b", t0 + 5 * min)), "b", t0)
        assertEquals(State.PENDING, ReminderEngine.reopen(futureDone, "b", t0)[0].state)
    }

    @Test fun updateWithATimeRingsAgain() {
        val l = ReminderEngine.update(listOf(r("a", t0 - 30 * min, State.MISSED)), "a", "New text", t0 + 5 * min)
        assertEquals(State.PENDING, l[0].state)
        assertEquals("New text", l[0].text)
        assertEquals(t0 + 5 * min, ReminderEngine.evaluate(l, t0).nextWakeAt)
        // text only keeps the state
        assertEquals(State.MISSED, ReminderEngine.update(listOf(r("a", t0 - 30 * min, State.MISSED)), "a", "x", null)[0].state)
    }

    @Test fun oldDoneAndMissedArePruned() {
        val l = listOf(
            r("done-old", t0 - 2 * ReminderEngine.KEEP_DONE_MS, State.DONE, done = t0 - ReminderEngine.KEEP_DONE_MS - 1),
            r("done-new", t0 - min, State.DONE, done = t0 - min),
            r("missed-old", t0 - ReminderEngine.KEEP_MISSED_MS - 1, State.MISSED),
            r("missed-new", t0 - 3 * 3_600_000L, State.MISSED),
        )
        assertEquals(setOf("done-new", "missed-new"), ReminderEngine.evaluate(l, t0).reminders.map { it.id }.toSet())
    }

    @Test fun textIsCleaned() {
        val n = ReminderEngine.newReminder("  take\nout   the trash ", t0, "maya", t0)
        assertEquals("take out the trash", n.text)
        assertEquals("maya", n.profileId)
        assertEquals("Reminder", ReminderEngine.newReminder("   ", t0, null, t0).text)
    }

    @Test fun persistenceRoundTrips() {
        val list = listOf(
            r("a", t0 + min),
            Reminder("b", "Call, the dentist", t0 + 2 * min, "maya", State.MISSED, t0, null, 2),
            r("c", t0 - min, State.DONE, done = t0),
        )
        assertEquals(list, Codec.remindersFromJson(Codec.remindersToJson(list)))
        val lists = mapOf("shopping" to listOf(ListItem("1", "Milk", false, 5), ListItem("2", "2 lemons", true, 6)), "todo" to listOf(ListItem("3", "Taxes")))
        assertEquals(lists, Codec.listsFromJson(Codec.listsToJson(lists)))
        // unknown fields and bad entries are skipped
        assertEquals(listOf("ok"), Codec.remindersFromJson("""{"version":9,"x":1,"reminders":[{"id":"ok","text":"t","due":5,"state":"WAT"},{"text":"no id","due":5},{"id":"nodue"}]}""").map { it.id })
        assertEquals(State.PENDING, Codec.remindersFromJson("""{"reminders":[{"id":"ok","text":"t","due":5,"state":"WAT"}]}""")[0].state)
        assertTrue(Codec.listsFromJson("""{"lists":{"Groceries":[{"id":"1","text":"x"}]}}""").containsKey("shopping"))
    }
}
