package app.vesper.feature.alarm

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.Profile
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Alarm filtering and wording by person (every alarm still rings whoever is in the room). */
class AlarmPeopleTest {
    private val maya = Profile("maya", "Maya", 0xFFFF9F0A.toInt(), alarmSound = "chime")
    private val sam = Profile("sam", "Sam", 0xFF30D158.toInt())
    private val james = Profile("james", "James", 3)
    private val people = listOf(Profile.SHARED, maya, sam, james)

    private val shared = Alarm("s", 7, 30, label = "Kids")
    private val mayas = Alarm("m", 6, 15, label = "Run", profileId = "maya")
    private val sams = Alarm("x", 5, 45, label = "Gym", profileId = "sam")
    private val orphan = Alarm("o", 8, 0, profileId = "gone")
    private val all = listOf(shared, mayas, sams, orphan)

    @Test fun aPersonSeesTheirOwnPlusShared() {
        assertEquals(listOf("m", "s"), AlarmPeople.visible(all, "maya").map { it.id })
        assertEquals(listOf("x", "s"), AlarmPeople.visible(all, "sam").map { it.id })
    }

    @Test fun nobodyRecognisedSeesEveryAlarmSortedByTime() {
        assertEquals(listOf("x", "m", "s", "o"), AlarmPeople.visible(all, Profile.SHARED_ID).map { it.id })
    }

    @Test fun newAlarmsBelongToTheRecognisedPerson() {
        assertEquals("maya", AlarmPeople.ownerForNew("maya"))
        assertNull(AlarmPeople.ownerForNew(Profile.SHARED_ID))
    }

    @Test fun ownerTitleAndSound() {
        assertSame(maya, AlarmPeople.owner(mayas, people))
        assertNull(AlarmPeople.owner(shared, people))
        assertNull(AlarmPeople.owner(orphan, people)) // a deleted person's alarm reads as shared
        assertNull(AlarmPeople.owner(shared.copy(profileId = Profile.SHARED_ID), people))
        assertEquals("Maya’s alarm", AlarmPeople.title(mayas, people))
        assertEquals("James’ alarm", AlarmPeople.title(mayas.copy(profileId = "james"), people))
        assertNull(AlarmPeople.title(shared, people))
        assertEquals("chime", AlarmPeople.sound(mayas, people))
        assertNull(AlarmPeople.sound(sams, people)) // Sam follows the alarm.sound setting
        assertNull(AlarmPeople.sound(shared, people))
        assertNull(AlarmPeople.sound(mayas, listOf(maya.copy(alarmSound = "kazoo"))))
    }

    @Test fun goodMorningUsesTheOwnersCalendar() {
        fun ev(id: String, who: String?) = CalendarEvent(id, id, "", 1_000L, 2_000L, color = 0, profileId = who)
        val events = listOf(ev("a", null), ev("b", "maya"), ev("c", "sam"))
        assertEquals(listOf("a", "b"), AlarmPeople.eventsFor(events, "maya").map { it.id })
        assertEquals(listOf("a", "b", "c"), AlarmPeople.eventsFor(events, null).map { it.id })
    }

    @Test fun headerNextAmongVisible() {
        val zone = ZoneId.of("America/Los_Angeles")
        val now = ZonedDateTime.of(2026, 11, 23, 22, 0, 0, 0, zone).toInstant().toEpochMilli() // Monday night
        val visible = AlarmPeople.visible(all, "maya")
        // The store's next is Sam's 5:45: not in Maya's list, so her own next one shows.
        val storeNext = NextAlarm(sams, now + 1, null)
        val n = AlarmPeople.nextFor(visible, storeNext, now, zone)!!
        assertEquals("m", n.alarm.id)
        assertEquals(ZonedDateTime.of(2026, 11, 24, 6, 15, 0, 0, zone).toInstant().toEpochMilli(), n.triggerAtMillis)
        // The store's next is in the list: it's used as is (keeps the reason).
        val withReason = NextAlarm(mayas, now + 5, "Earlier for Dentist")
        assertSame(withReason, AlarmPeople.nextFor(visible, withReason, now, zone))
        // Off alarms don't count.
        assertNull(AlarmPeople.nextFor(listOf(mayas.copy(enabled = false)), null, now, zone))
    }
}
