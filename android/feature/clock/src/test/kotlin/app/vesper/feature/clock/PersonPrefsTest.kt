package app.vesper.feature.clock

import app.vesper.core.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** StandBy / Widgets wallpaper and Night mode resolution for the recognised person. */
class PersonPrefsTest {
    private val maya = Profile("maya", "Maya", 1, faceId = "glass", wallpaper = "Dusk")
    private val sam = Profile("sam", "Sam", 2) // no wallpaper: follows Shared

    @Test fun personIsTheLatestCopyAndNullForShared() {
        assertNull(PersonPrefs.person(Profile.SHARED, listOf(Profile.SHARED, maya)))
        val edited = maya.copy(wallpaper = "Graphite")
        assertSame(edited, PersonPrefs.person(maya, listOf(Profile.SHARED, edited)))
        // Deleted meanwhile: the active copy still counts.
        assertSame(maya, PersonPrefs.person(maya, listOf(Profile.SHARED)))
    }

    @Test fun glassWallpaperFollowsThePersonElseTheSetting() {
        assertEquals("Blue", PersonPrefs.glassWallpaper(null, "Blue"))
        assertEquals("Graphite", PersonPrefs.glassWallpaper(null, "Graphite"))
        assertEquals("Dusk", PersonPrefs.glassWallpaper(maya, "Blue"))
        assertEquals("Graphite", PersonPrefs.glassWallpaper(sam, "Graphite"))
        // Unknown values fall back: the person's to the setting, the setting to Blue.
        assertEquals("Graphite", PersonPrefs.glassWallpaper(maya.copy(wallpaper = "Neon"), "Graphite"))
        assertEquals("Blue", PersonPrefs.glassWallpaper(null, "Black"))
        assertEquals("Blue", PersonPrefs.glassWallpaper(sam, ""))
    }

    @Test fun widgetsWallpaperAllowsBlackOnlyFromTheSetting() {
        assertEquals("Black", PersonPrefs.widgetsWallpaper(null, "Black"))
        assertEquals("Dusk", PersonPrefs.widgetsWallpaper(maya, "Black"))
        assertEquals("Black", PersonPrefs.widgetsWallpaper(sam, "Black"))
        assertEquals(WidgetStacks.DEFAULT_WALLPAPER, PersonPrefs.widgetsWallpaper(null, "nope"))
    }

    @Test fun wallpaperPickerCycles() {
        assertEquals("Dusk", PersonPrefs.nextWallpaper("Blue"))
        assertEquals("Graphite", PersonPrefs.nextWallpaper("Dusk"))
        assertEquals("Blue", PersonPrefs.nextWallpaper("Graphite"))
        assertEquals("Blue", PersonPrefs.nextWallpaper("Black"))
    }

    @Test fun wallpapersMatchTheGlassPalettes() {
        assertEquals(GlassFace.PALETTES.keys.toList(), PersonPrefs.WALLPAPERS)
    }

    @Test fun nightModeFollowsThePersonElseTheSetting() {
        assertEquals("auto", PersonPrefs.nightMode(null, "auto"))
        assertEquals("schedule", PersonPrefs.nightMode(sam, "Schedule"))
        assertEquals("off", PersonPrefs.nightMode(sam.copy(nightMode = "off"), "auto"))
        assertEquals("on", PersonPrefs.nightMode(sam.copy(nightMode = "ON"), "auto"))
        assertEquals("auto", PersonPrefs.nightMode(sam.copy(nightMode = "sometimes"), "auto"))
    }
}
