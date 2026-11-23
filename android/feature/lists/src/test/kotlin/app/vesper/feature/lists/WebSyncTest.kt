package app.vesper.feature.lists

import app.vesper.core.model.ListItem
import app.vesper.core.model.Reminder
import app.vesper.core.model.ReminderTime
import app.vesper.feature.lists.engine.WebSync
import app.vesper.feature.lists.ui.ListsModel
import app.vesper.feature.lists.ui.Row
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSyncTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val now = ms("2026-11-23T10:00")

    private val reminders = listOf(
        Reminder("a", "Take out the trash", ms("2026-11-23T19:00")),
        Reminder("b", "Call Mom, then Dad", ms("2026-11-23T09:00"), state = Reminder.State.MISSED),
        Reminder("c", "Done already", ms("2026-11-23T08:00"), state = Reminder.State.DONE, doneAtMillis = now),
    )

    @Test fun reminderLinesAreCommaFreeAndAbsolute() {
        val lines = WebSync.reminderLines(reminders, zone, h24 = false)
        assertEquals(listOf(
            "Mon Nov 23 · 7:00 PM · Take out the trash" to "a",
            "Missed · Mon Nov 23 · 9:00 AM · Call Mom; then Dad" to "b",
        ), lines)
        assertTrue(WebSync.join(lines).split(',').size == 2)
    }

    @Test fun diffFindsDeletesAndAdds() {
        val current = WebSync.reminderLines(reminders, zone, false)
        val sent = listOf(current[1].first, "tomorrow 8am call the dentist")
        val (gone, added) = WebSync.diff(current, sent)
        assertEquals(listOf("a"), gone)
        assertEquals(listOf("tomorrow 8am call the dentist"), added)
        // the same lines back: nothing to do
        assertEquals(emptyList<String>() to emptyList<String>(), WebSync.diff(current, current.map { it.first }))
    }

    @Test fun duplicatesMatchOneToOne() {
        val cur = listOf("Milk" to "1", "Milk" to "2")
        assertEquals(listOf("2") to emptyList<String>(), WebSync.diff(cur, listOf("Milk")))
    }

    @Test fun newLinesParse() {
        assertEquals("call the dentist" to ms("2026-11-24T08:00"), WebSync.parseNew("tomorrow 8am call the dentist", now, zone))
        assertEquals("check the oven" to ms("2026-11-23T10:20"), WebSync.parseNew("in 20 minutes check the oven", now, zone))
        // an edited canonical line
        assertEquals("Take out the trash" to ms("2026-11-24T20:00"), WebSync.parseNew("Tue Nov 24 · 8:00 PM · Take out the trash", now, zone))
        assertNull(WebSync.parseNew("buy milk", now, zone))
        assertNull(WebSync.parseNew("this morning water plants", now, zone))
    }

    @Test fun itemLinesSkipChecked() {
        val items = listOf(ListItem("1", "Milk"), ListItem("2", "Eggs", checked = true), ListItem("3", "Salt, sea"))
        assertEquals(listOf("Milk" to "1", "Salt; sea" to "3"), WebSync.itemLines(items))
    }

    @Test fun screenRowsOrderAndLabels() {
        val rows = ListsModel.reminderRows(reminders + Reminder("d", "Due now", now - 1000, state = Reminder.State.DUE), now, zone, false)
        assertEquals(listOf("d", "b", "a", "c"), rows.map { it.id })
        assertEquals(Row.Tone.DUE, rows[0].tone)
        assertEquals("Now", rows[0].label)
        assertEquals("Missed · 9:00 AM", rows[1].label)
        assertEquals("7:00 PM", rows[2].label)
        assertTrue(rows[3].checked)
        val items = ListsModel.itemRows(listOf(ListItem("1", "A", true), ListItem("2", "B")))
        assertEquals(listOf("B", "A"), items.map { it.text })
        assertEquals("3 reminders · 1 missed · 1 to buy", ListsModel.summary(reminders + Reminder("d", "x", now + 1), listOf(ListItem("1", "A", true), ListItem("2", "B")), "Shopping"))
        assertEquals("No reminders · Shopping list empty", ListsModel.summary(emptyList(), emptyList(), "Shopping"))
        assertEquals("7:00 PM", ReminderTime.describe(ms("2026-11-23T19:00"), now, zone))
    }
}
