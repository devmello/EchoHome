package app.vesper.feature.spotify

import app.vesper.core.model.DeviceName
import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifySettingsTest {
    private class MapSettings : SettingsStore {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getFloat(key: String, default: Float) = map[key] as? Float ?: default
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override val changes: SharedFlow<String> = MutableSharedFlow()
    }

    @Test fun onByDefaultAndEitherSwitchTurnsItOff() {
        val s = MapSettings()
        assertTrue(SpotifyFeature.enabled(s))
        s.putBoolean(SpotifyFeature.KEY_CC_SPOTIFY, false)
        assertFalse(SpotifyFeature.enabled(s))
        s.putBoolean(SpotifyFeature.KEY_CC_SPOTIFY, true)
        s.putBoolean(SpotifyFeature.KEY_ENABLED, false)
        assertFalse(SpotifyFeature.enabled(s))
    }

    @Test fun nameFallsBackToTheDeviceName() {
        val s = MapSettings()
        assertEquals("Echo Show", SpotifyFeature.name(s))
        s.putString(DeviceName.KEY, "Bedroom")
        assertEquals("Bedroom", SpotifyFeature.name(s))
        s.putString(SpotifyFeature.KEY_NAME, "Kitchen Speaker")
        assertEquals("Kitchen Speaker", SpotifyFeature.name(s))
        s.putString(SpotifyFeature.KEY_NAME, "  ")
        assertEquals("Bedroom", SpotifyFeature.name(s))
    }

    @Test fun bitrateIs160UnlessLowIsPicked() {
        val s = MapSettings()
        assertEquals(160, SpotifyFeature.bitrate(s))
        s.putString(SpotifyFeature.KEY_BITRATE, "96")
        assertEquals(96, SpotifyFeature.bitrate(s))
        s.putString(SpotifyFeature.KEY_BITRATE, "garbage")
        assertEquals(160, SpotifyFeature.bitrate(s))
    }

    @Test fun sectionDeclaresEveryUserSettingWithDefaults() {
        val section = SpotifyFeature.settingsSection("Ready.")
        assertEquals("spotify", section.id)
        assertTrue(section.help!!.contains("Spotify Premium"))
        assertTrue(section.help!!.endsWith("Status: Ready."))
        val byKey = section.settings.associateBy { it.key }
        assertEquals("true", byKey.getValue(SpotifyFeature.KEY_ENABLED).default)
        assertEquals("", byKey.getValue(SpotifyFeature.KEY_NAME).default)
        val bitrate = byKey.getValue(SpotifyFeature.KEY_BITRATE)
        assertEquals(SettingType.CHOICE, bitrate.type)
        assertEquals("160", bitrate.default)
        assertEquals(listOf("160", "96"), bitrate.options.map { it.first })
        assertTrue(byKey.getValue(SpotifyFeature.KEY_MDNS).advanced)
        val signOut = byKey.getValue(SpotifyFeature.KEY_SIGN_OUT)
        assertEquals(SettingType.ACTION, signOut.type)
        assertNotNull(signOut.onAction)
        // No service running: the action says so instead of failing.
        assertEquals("The receiver is off.", signOut.onAction!!.invoke())
        // Settings that restart the receiver are the ones it reads at start.
        assertEquals(setOf(SpotifyFeature.KEY_NAME, SpotifyFeature.KEY_BITRATE, SpotifyFeature.KEY_MDNS, DeviceName.KEY), SpotifyFeature.RESTART_KEYS)
    }

    @Test fun signOutActionCallsTheRunningService() {
        var called = 0
        SpotifyFeature.signOut = { called++ }
        try {
            val action = SpotifyFeature.settingsSection("").settings.first { it.key == SpotifyFeature.KEY_SIGN_OUT }.onAction!!
            assertEquals("Signed out.", action())
            assertEquals(1, called)
        } finally {
            SpotifyFeature.signOut = null
        }
    }
}
