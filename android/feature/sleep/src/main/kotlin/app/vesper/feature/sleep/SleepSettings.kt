package app.vesper.feature.sleep

import app.vesper.core.model.SettingSpec
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsSection
import app.vesper.core.model.SettingsStore
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds

/** The "Sleep sounds & bedtime" card on the web settings page. All read at use time (live). */
internal object SleepSettings {
    const val SOUND = "sleep.sound"
    const val TIMER = "sleep.timer"
    const val LEVEL = "sleep.level"
    const val BEDTIME_SOUND = "sleep.bedtime.sound"
    const val BEDTIME_DND = "sleep.bedtime.dnd"
    const val BEDTIME_NIGHT = "sleep.bedtime.night"
    const val BEDTIME_DIM = "sleep.bedtime.dim"
    const val BEDTIME_DIM_LEVEL = "sleep.bedtime.dimLevel"
    const val WAKE_FALLBACK = "sleep.bedtime.wake"

    /** Internal state, not a user setting: when the routine's Do Not Disturb ends (wall clock ms). */
    const val DND_UNTIL = "sleep.state.dndUntil"

    private val timerOptions = SleepSounds.TIMER_CHOICES.map { (if (it == 0) "0" else "$it") to (if (it == 0) "Off (until stopped)" else "$it min") }

    val section = SettingsSection(
        id = "sleep", title = "Sleep sounds & bedtime", order = 38,
        help = "Sounds are generated on the Echo (no downloads). By voice: “good night”, “play rain for 30 minutes”, “stop the sleep sound”.",
        settings = listOf(
            SettingSpec(SOUND, "Default sleep sound", SettingType.CHOICE, SleepSound.RAIN.id,
                options = SleepSound.values().map { it.id to it.title }),
            SettingSpec(TIMER, "Sleep timer", SettingType.CHOICE, "30", options = timerOptions,
                help = "The sound fades out over the last 30 seconds."),
            SettingSpec(LEVEL, "Sleep sound loudness", SettingType.FLOAT, "0.7", min = 0.2f, max = 1f, step = 0.05f,
                help = "On top of the Echo's volume, so the rain can sit quieter than music."),
            SettingSpec(BEDTIME_SOUND, "Good night starts the sleep sound", SettingType.BOOLEAN, "true",
                help = "“Good night” (voice, or the button on the Sleep screen) plays the default sound with the timer above."),
            SettingSpec(BEDTIME_DND, "Good night turns on Do Not Disturb", SettingType.BOOLEAN, "true",
                help = "Until the next alarm. Alarms still ring."),
            SettingSpec(BEDTIME_NIGHT, "Good night turns on Night mode", SettingType.BOOLEAN, "true",
                help = "Red, dimmed clock. It goes back to automatic when the alarm rings."),
            SettingSpec(BEDTIME_DIM, "Good night dims the screen", SettingType.BOOLEAN, "false"),
            SettingSpec(BEDTIME_DIM_LEVEL, "Dim to", SettingType.FLOAT, "0.1", min = 0.02f, max = 0.5f, step = 0.02f, advanced = true),
            SettingSpec(WAKE_FALLBACK, "With no alarm set, Do Not Disturb lasts until", SettingType.TIME, "07:00", advanced = true),
        ),
    )

    fun sound(s: SettingsStore): SleepSound = SleepSound.of(s.getString(SOUND, SleepSound.RAIN.id)) ?: SleepSound.RAIN
    fun timer(s: SettingsStore): Int = s.getString(TIMER, "30").toIntOrNull()?.takeIf { it in SleepSounds.TIMER_CHOICES } ?: 30
    fun level(s: SettingsStore): Float = s.getFloat(LEVEL, 0.7f).coerceIn(0.2f, 1f)
    fun bedtimeSound(s: SettingsStore) = s.getBoolean(BEDTIME_SOUND, true)
    fun bedtimeDnd(s: SettingsStore) = s.getBoolean(BEDTIME_DND, true)
    fun bedtimeNight(s: SettingsStore) = s.getBoolean(BEDTIME_NIGHT, true)
    fun bedtimeDim(s: SettingsStore) = s.getBoolean(BEDTIME_DIM, false)
    fun dimLevel(s: SettingsStore) = s.getFloat(BEDTIME_DIM_LEVEL, 0.1f).coerceIn(0.02f, 0.5f)

    /** "HH:mm" → (hour, minute); 07:00 if it doesn't parse. */
    fun wakeFallback(s: SettingsStore): Pair<Int, Int> {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.getString(WAKE_FALLBACK, "07:00").trim())
        val h = m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return 7 to 0
        val min = m.groupValues[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return 7 to 0
        return h to min
    }
}
