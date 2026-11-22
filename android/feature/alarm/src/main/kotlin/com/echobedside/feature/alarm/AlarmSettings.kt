package com.echobedside.feature.alarm

import com.echobedside.core.model.Alarm
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection
import com.echobedside.core.model.SettingsStore

/**
 * The "Alarms & timers" card on the settings page. Global: they apply to every alarm (there's no
 * per-alarm editor yet). Read at use time, and a change re-evaluates the alarms at once (a new
 * sunrise length moves the next sunrise start), so nothing needs a restart.
 */
internal object AlarmSettings {
    const val SUNRISE = "alarm.sunrise.enabled"
    const val SUNRISE_MIN = "alarm.sunrise.minutes"
    const val SUNRISE_BRIGHTNESS = "alarm.sunrise.brightness"
    const val SNOOZE_MIN = "alarm.snooze.minutes"
    const val SOUND = "alarm.sound"
    const val RAMP_SEC = "alarm.ramp.seconds"
    const val VOLUME_FLOOR = "alarm.volume.floor"
    const val TIMER_ALERT_MIN = "timer.alert.minutes"

    const val SOUND_SYSTEM = "system"
    const val SOUND_CHIME = "chime"
    const val SOUND_BEEPS = "beeps"

    val section = SettingsSection(
        id = "alarm", title = "Alarms & timers", order = 40,
        help = "Applies to every alarm. Timers are set by voice or on the Timers screen.",
        settings = listOf(
            SettingSpec(SUNRISE, "Sunrise light", SettingType.BOOLEAN, "true",
                help = "Before an alarm, the screen slowly brightens and glows warm, like a sunrise."),
            SettingSpec(SUNRISE_MIN, "Sunrise length (minutes)", SettingType.INT, "30", min = 5f, max = 60f, step = 5f),
            SettingSpec(SUNRISE_BRIGHTNESS, "Sunrise brightness", SettingType.FLOAT, "1.0", min = 0.3f, max = 1f, step = 0.05f,
                help = "How bright the screen gets by the time the alarm rings.", advanced = true),
            SettingSpec(SNOOZE_MIN, "Snooze (minutes)", SettingType.INT, "9", min = 1f, max = 30f, step = 1f),
            SettingSpec(SOUND, "Alarm sound", SettingType.CHOICE, SOUND_SYSTEM, options = listOf(
                SOUND_SYSTEM to "Default alarm sound",
                SOUND_CHIME to "Soft chime",
                SOUND_BEEPS to "Beeps",
            )),
            SettingSpec(RAMP_SEC, "Volume ramp (seconds)", SettingType.INT, "45", min = 0f, max = 120f, step = 5f,
                help = "The alarm starts quietly and reaches full volume after this long. 0 = full volume at once."),
            SettingSpec(VOLUME_FLOOR, "Make sure alarms are audible", SettingType.BOOLEAN, "false",
                help = "While an alarm rings, raise the alarm volume to at least 60 % if it's set lower, then put it back."),
            SettingSpec(TIMER_ALERT_MIN, "Timer chime length (minutes)", SettingType.INT, "3", min = 1f, max = 10f, step = 1f, advanced = true),
        ),
    )

    fun sunrise(s: SettingsStore) = s.getBoolean(SUNRISE, true)
    fun sunriseMinutes(s: SettingsStore) = s.getInt(SUNRISE_MIN, 30).coerceIn(1, 120)
    fun sunriseBrightness(s: SettingsStore) = s.getFloat(SUNRISE_BRIGHTNESS, 1f).coerceIn(0.1f, 1f)
    fun snoozeMinutes(s: SettingsStore) = s.getInt(SNOOZE_MIN, 9).coerceIn(1, 60)
    fun sound(s: SettingsStore) = s.getString(SOUND, SOUND_SYSTEM)
    fun rampMs(s: SettingsStore) = s.getInt(RAMP_SEC, 45).coerceIn(0, 600) * 1000L
    fun volumeFloor(s: SettingsStore) = s.getBoolean(VOLUME_FLOOR, false)
    fun timerAlertMs(s: SettingsStore) = s.getInt(TIMER_ALERT_MIN, 3).coerceIn(1, 30) * 60_000L

    /** The global settings applied to one alarm (the engine's adjust hook). */
    fun apply(s: SettingsStore, a: Alarm): Alarm =
        a.copy(sunrise = a.sunrise && sunrise(s), sunriseMinutes = sunriseMinutes(s), snoozeMinutes = snoozeMinutes(s))
}
