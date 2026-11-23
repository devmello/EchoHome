package app.vesper.feature.clock

import app.vesper.core.model.Profile

/**
 * Which value a recognised person's StandBy uses (pure, unit-tested). A person's own preference
 * ([Profile.wallpaper], [Profile.nightMode]) wins when it's set and valid; null means "follow
 * Shared", i.e. the setting everyone uses.
 */
object PersonPrefs {
    /** The Liquid Glass wallpapers (GlassFace.PALETTES' names), in picker order. */
    val WALLPAPERS = listOf("Blue", "Dusk", "Graphite")
    val NIGHT_MODES = listOf("auto", "schedule", "on", "off")

    /** The active person's latest copy (edits land in `profiles` first); null for Shared. */
    fun person(active: Profile, profiles: List<Profile>): Profile? {
        if (active.id == Profile.SHARED_ID) return null
        return profiles.firstOrNull { it.id == active.id } ?: active
    }

    /** A wallpaper: the person's if valid, else the setting's if valid, else [default]. */
    fun wallpaper(personal: String?, setting: String, valid: Collection<String>, default: String): String =
        personal?.takeIf { it in valid } ?: setting.takeIf { it in valid } ?: default

    /** The Liquid Glass face's wallpaper for [person] (null = Shared) with the `clock.glass.wallpaper` setting. */
    fun glassWallpaper(person: Profile?, setting: String): String = wallpaper(person?.wallpaper, setting, WALLPAPERS, "Blue")

    /** The Widgets page's wallpaper: a person's Liquid Glass wallpaper, else `clock.widgets.wallpaper` (which may be Black). */
    fun widgetsWallpaper(person: Profile?, setting: String): String =
        wallpaper(person?.wallpaper, setting, WALLPAPERS + WidgetStacks.WALLPAPER_BLACK, WidgetStacks.DEFAULT_WALLPAPER)

    /** The next wallpaper in the edit-mode picker. */
    fun nextWallpaper(current: String): String = WALLPAPERS[(WALLPAPERS.indexOf(current) + 1).mod(WALLPAPERS.size)]

    /** Night mode: the person's if valid, else the `clock.night.mode` setting. */
    fun nightMode(person: Profile?, setting: String): String =
        person?.nightMode?.lowercase()?.takeIf { it in NIGHT_MODES } ?: setting.lowercase()
}
