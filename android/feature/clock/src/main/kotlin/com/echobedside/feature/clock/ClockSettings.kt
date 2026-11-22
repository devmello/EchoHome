package com.echobedside.feature.clock

import com.echobedside.core.model.FaceSpec
import com.echobedside.core.model.SettingSpec
import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsSection

/**
 * Every user-facing setting `:feature:clock` reads, declared for the web settings page
 * (`host.registerSettings`). All of them apply live: StandBy, the faces and [NightController]
 * observe `services.settings.changes`. Debug launch extras are not settings.
 */
object ClockSettings {
    const val KEY_FACE = "clock.face"
    fun colorKey(faceId: String) = "clock.color.$faceId"
    fun complicationsKey(faceId: String) = "clock.complications.$faceId"

    const val KEY_NIGHT_MODE = "clock.night.mode"
    const val KEY_NIGHT_ENTER_LUX = "clock.night.enterLux"
    const val KEY_NIGHT_LEAVE_LUX = "clock.night.leaveLux"
    const val KEY_NIGHT_DELAY_SEC = "clock.night.delaySec"

    /** Keys whose change StandBy applies to the face on screen. */
    fun affectsStandby(key: String) =
        key == KEY_FACE || key.startsWith("clock.color.") || key.startsWith("clock.complications.")

    fun section(faces: List<FaceSpec>) = SettingsSection(
        id = "clock",
        title = "Clock & StandBy",
        order = 20,
        help = "The faces Vesper shows at rest. Swipe up or down on the Echo to switch; touch and hold to customize.",
        settings = buildList {
            add(SettingSpec(KEY_FACE, "Clock face", SettingType.CHOICE, default = "digital",
                options = faces.map { it.id to it.name },
                help = "When someone is recognised, their own face and colour show instead."))
            for (f in faces) {
                if (f.colorOptions.isNotEmpty()) add(SettingSpec(colorKey(f.id), "${f.name} colour", SettingType.COLOR,
                    default = hexColor(f.colorOptions.first())))
                if (f.supportsComplications) add(SettingSpec(complicationsKey(f.id), "${f.name}: show date, weather and alarm",
                    SettingType.BOOLEAN, default = "true"))
            }
            add(SettingSpec(GlassFace.KEY_GLASS, "Liquid Glass: frost", SettingType.FLOAT, default = "0.45",
                min = 0f, max = 1f, step = 0.05f, help = "0 = clear glass, 1 = frosted."))
            add(SettingSpec(GlassFace.KEY_WALLPAPER, "Liquid Glass: wallpaper", SettingType.CHOICE, default = "Blue",
                options = GlassFace.PALETTES.keys.map { it to it }))
            add(SettingSpec(WorldFace.KEY_CITIES, "World: cities", SettingType.TEXT_LIST,
                default = WorldFace.DEFAULT_CITIES.joinToString(",") { WorldFace.encodeCity(it) },
                help = "Up to three, each \"City|Time zone|latitude|longitude\", e.g. London|Europe/London|51.5|-0.13."))
            add(SettingSpec(KEY_NIGHT_MODE, "Night mode", SettingType.CHOICE, default = "auto",
                options = listOf("auto" to "Automatic (dark room)", "on" to "Always on", "off" to "Off"),
                help = "Night shows every face in dim red. Automatic turns it on when the room stays dark."))
            add(SettingSpec(KEY_NIGHT_ENTER_LUX, "Night: dark below (lux)", SettingType.FLOAT, default = "3",
                min = 0f, max = 50f, step = 0.5f, advanced = true))
            add(SettingSpec(KEY_NIGHT_LEAVE_LUX, "Night: bright above (lux)", SettingType.FLOAT, default = "10",
                min = 1f, max = 200f, step = 1f, advanced = true, help = "Keep it above the dark threshold so Night doesn't flicker."))
            add(SettingSpec(KEY_NIGHT_DELAY_SEC, "Night: dark for (seconds)", SettingType.INT, default = "30",
                min = 0f, max = 600f, step = 5f, advanced = true))
        },
    )
}
