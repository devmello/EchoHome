package app.vesper.core.model

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTimeTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    /** Monday 2026-11-23, 10:00 local. */
    private val now = ms("2026-11-23T10:00")

    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun iso(at: Long) = ReminderTime.iso(at, zone)
    private fun p(s: String, at: Long = now) = ReminderTime.parse(s, at, zone)

    private fun check(phrase: String, expected: String, text: String? = null, at: Long = now) {
        val r = p(phrase, at) ?: throw AssertionError("no time in \"$phrase\"")
        assertEquals(phrase, expected, iso(r.atMillis))
        if (text != null) assertEquals(phrase, text, r.text)
    }

    @Test fun clockTimes() {
        check("remind me to take out the trash at 7pm", "2026-11-23T19:00", "take out the trash")
        check("remind me to call Mom at 6:30 p.m.", "2026-11-23T18:30", "call Mom")
        check("19:00 call the vet", "2026-11-23T19:00", "call the vet")
        check("noon lunch with Sam", "2026-11-23T12:00", "lunch with Sam")
        // a time that has passed today is tomorrow
        check("at 9am stretch", "2026-11-24T09:00", "stretch")
        check("07:30 run", "2026-11-24T07:30", "run")
    }

    @Test fun bareHourIsTheNextOne() {
        check("at 7 feed the cat", "2026-11-23T19:00", "feed the cat")
        check("at 7 feed the cat", "2026-11-23T07:00", at = ms("2026-11-23T06:00"))
        check("at 7 feed the cat", "2026-11-24T07:00", at = ms("2026-11-23T20:00"))
    }

    @Test fun relative() {
        check("remind me in 20 minutes to check the oven", "2026-11-23T10:20", "check the oven")
        check("in an hour and a half stretch", "2026-11-23T11:30", "stretch")
        check("in half an hour water the plants", "2026-11-23T10:30", "water the plants")
        check("in 2 hours move the car", "2026-11-23T12:00", "move the car")
        check("pizza 45 minutes from now", "2026-11-23T10:45", "pizza")
        check("in 1 hour 15 minutes leave", "2026-11-23T11:15", "leave")
        check("in 3 days renew the passport", "2026-11-26T10:00", "renew the passport")
    }

    @Test fun days() {
        check("remind me tomorrow morning to call the dentist", "2026-11-24T08:00", "call the dentist")
        check("tomorrow at 8 gym", "2026-11-24T08:00", "gym")
        check("tomorrow at 3 pick up the kids", "2026-11-24T15:00", "pick up the kids")
        check("tomorrow buy stamps", "2026-11-24T09:00", "buy stamps")
        check("tonight lock the back door", "2026-11-23T20:00", "lock the back door")
        check("tonight at 10 take the pills", "2026-11-23T22:00", "take the pills")
        check("this evening call Grandma", "2026-11-23T18:00", "call Grandma")
        check("on friday at 6pm pay the rent", "2026-11-27T18:00", "pay the rent")
        // today is Monday and 9 AM has passed: next Monday
        check("monday 9am standup notes", "2026-11-30T09:00", "standup notes")
        check("the day after tomorrow at 5pm return the books", "2026-11-25T17:00", "return the books")
    }

    @Test fun dates() {
        check("nov 30 9am dentist", "2026-11-30T09:00", "dentist")
        check("on the 3rd of december send the card", "2026-12-03T09:00", "send the card")
        check("jan 5 renew the car tax", "2027-01-05T09:00", "renew the car tax")
        check("2026-12-24 18:00 call home", "2026-12-24T18:00", "call home")
    }

    @Test fun noTime() {
        assertNull(p("buy milk"))
        assertNull(p(""))
        assertNull(p("in a bit"))
    }

    @Test fun explicitPastIsReturnedAsIs() {
        val r = p("this morning water the plants")!!
        assertTrue(r.atMillis < now)
    }

    @Test fun assistantAt() {
        assertEquals("2026-11-24T19:00", iso(ReminderTime.at("2026-11-24T19:00", now, zone)!!))
        assertEquals("2026-11-24T19:00", iso(ReminderTime.at("2026-11-24 19:00:00", now, zone)!!))
        assertEquals("2026-11-23T19:00", iso(ReminderTime.at("19:00", now, zone)!!))
        assertEquals("2026-11-24T08:00", iso(ReminderTime.at("08:00", now, zone)!!))
        assertEquals("2026-11-24T08:00", iso(ReminderTime.at("tomorrow morning", now, zone)!!))
        assertNull(ReminderTime.at("25:00", now, zone))
        assertNull(ReminderTime.at("soonish", now, zone))
    }

    @Test fun describe() {
        assertEquals("7:00 PM", ReminderTime.describe(ms("2026-11-23T19:00"), now, zone))
        assertEquals("19:00", ReminderTime.describe(ms("2026-11-23T19:00"), now, zone, h24 = true))
        assertEquals("Tomorrow 8:00 AM", ReminderTime.describe(ms("2026-11-24T08:00"), now, zone))
        assertEquals("Wed 9:00 AM", ReminderTime.describe(ms("2026-11-25T09:00"), now, zone))
        assertEquals("Dec 3 9:00 AM", ReminderTime.describe(ms("2026-12-03T09:00"), now, zone))
        assertEquals("Yesterday 12:05 AM", ReminderTime.describe(ms("2026-11-22T00:05"), now, zone))
        assertEquals("Tue Nov 24 · 7:00 PM", ReminderTime.absolute(ms("2026-11-24T19:00"), zone))
    }

    @Test fun cleanText() {
        assertEquals("take out the trash", ReminderTime.cleanText("Remind me to take out the trash."))
        assertEquals("call Mom", ReminderTime.cleanText("hey can you remind me to call Mom"))
        assertEquals("the meeting", ReminderTime.cleanText("set a reminder about the meeting"))
        assertEquals("Water plants", ReminderTime.cleanText("Water plants"))
    }
}
