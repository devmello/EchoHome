package app.vesper.feature.alarm

import app.vesper.core.model.Alarm
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.Profile
import app.vesper.feature.alarm.engine.nextTriggerAfter
import java.time.ZoneId

/**
 * Whose alarm is whose (pure, unit-tested). [Alarm.profileId] null = shared. Every alarm still
 * rings whoever is in the room; this only decides what the screens show and say.
 */
internal object AlarmPeople {
    /**
     * The Alarms screen's list: a recognised person sees their own alarms plus the shared ones;
     * with nobody recognised (Shared) every alarm shows, so nobody's alarm is out of reach.
     * Sorted by time of day.
     */
    fun visible(alarms: List<Alarm>, activeId: String): List<Alarm> = alarms
        .filter { activeId == Profile.SHARED_ID || it.profileId == null || it.profileId == activeId }
        .sortedWith(compareBy({ it.hour }, { it.minute }, { it.label }))

    /** Who a new alarm belongs to: the recognised person, or nobody (shared). */
    fun ownerForNew(activeId: String): String? = activeId.takeIf { it != Profile.SHARED_ID }

    /** The alarm's owner, if it has one that still exists (a deleted person's alarm reads as shared). */
    fun owner(alarm: Alarm, profiles: List<Profile>): Profile? {
        val id = alarm.profileId ?: return null
        if (id == Profile.SHARED_ID) return null
        return profiles.firstOrNull { it.id == id }
    }

    /** "Maya’s alarm", or null for a shared one. */
    fun title(alarm: Alarm, profiles: List<Profile>): String? = owner(alarm, profiles)?.let { possessive(it.name) + " alarm" }

    fun possessive(name: String): String = name.trim().let { if (it.endsWith("s", ignoreCase = true)) "$it’" else "$it’s" }

    /** The owner's own alarm sound ([Profile.alarmSound]), or null to use the `alarm.sound` setting. */
    fun sound(alarm: Alarm, profiles: List<Profile>): String? =
        owner(alarm, profiles)?.alarmSound?.takeIf { it in SOUNDS }

    val SOUNDS = setOf(AlarmSettings.SOUND_SYSTEM, AlarmSettings.SOUND_CHIME, AlarmSettings.SOUND_BEEPS)

    /** Good morning after [owner]'s alarm: their events plus shared ones (everything for a shared alarm). */
    fun eventsFor(events: List<CalendarEvent>, ownerId: String?): List<CalendarEvent> =
        if (ownerId == null) events else events.filter { it.profileId == null || it.profileId == ownerId }

    /**
     * The header's next alarm among [visible]: the store's [next] when it is one of them (it has the
     * leave-by / holiday reason), else the earliest enabled visible alarm (plain next trigger).
     */
    fun nextFor(visible: List<Alarm>, next: NextAlarm?, now: Long, zone: ZoneId): NextAlarm? {
        if (next != null && visible.any { it.id == next.alarm.id }) return next
        return visible.filter { it.enabled }
            .map { NextAlarm(it, nextTriggerAfter(it, now, zone)) }
            .minByOrNull { it.triggerAtMillis }
    }
}
