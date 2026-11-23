package app.vesper.feature.camera.profiles

import app.vesper.core.model.Profile

/**
 * The Profiles screen's "Your stuff" row (pure, unit-tested): what's personal for one person, as
 * short chips, and how the editable ones cycle. A null preference means "follow Everyone's" (the
 * shared settings). The values match the settings they override: `clock.glass.wallpaper`,
 * `clock.night.mode`, `alarm.sound`.
 */
object PersonalStuff {
    enum class Kind { CLOCK, WALLPAPER, NIGHT, SOUND, ALARMS, CALENDAR }

    data class Item(val kind: Kind, val text: String, val editable: Boolean)

    const val EVERYONE = "Everyone’s"
    val WALLPAPERS = listOf("Blue", "Dusk", "Graphite")
    val NIGHT = listOf("auto" to "Auto", "schedule" to "Schedule", "on" to "Always", "off" to "Off")
    val SOUNDS = listOf("system" to "Default", "chime" to "Soft chime", "beeps" to "Beeps")

    fun items(p: Profile, faceName: String, alarmCount: Int, calendarSet: Boolean): List<Item> = listOf(
        Item(Kind.CLOCK, "Clock · $faceName", false),
        Item(Kind.WALLPAPER, "Wallpaper · ${p.wallpaper ?: EVERYONE}", true),
        Item(Kind.NIGHT, "Night · ${NIGHT.firstOrNull { it.first == p.nightMode }?.second ?: EVERYONE}", true),
        Item(Kind.SOUND, "Alarm sound · ${SOUNDS.firstOrNull { it.first == p.alarmSound }?.second ?: EVERYONE}", true),
        Item(Kind.ALARMS, when (alarmCount) { 0 -> "No alarms"; 1 -> "1 alarm"; else -> "$alarmCount alarms" }, false),
        Item(Kind.CALENDAR, if (calendarSet) "Calendar · set" else "Calendar · not set", false),
    )

    /** A tap on an editable chip: the next value, then back to Everyone's (null). */
    fun cycle(p: Profile, kind: Kind): Profile = when (kind) {
        Kind.WALLPAPER -> p.copy(wallpaper = next(WALLPAPERS, p.wallpaper))
        Kind.NIGHT -> p.copy(nightMode = next(NIGHT.map { it.first }, p.nightMode))
        Kind.SOUND -> p.copy(alarmSound = next(SOUNDS.map { it.first }, p.alarmSound))
        else -> p
    }

    /** null → first → … → last → null. */
    fun next(values: List<String>, cur: String?): String? {
        val i = values.indexOf(cur)
        return if (cur == null || i < 0) values.first() else values.getOrNull(i + 1)
    }
}
