package com.echobedside.feature.media

import com.echobedside.core.model.SettingType
import com.echobedside.core.model.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSettingsTest {
    private class Mem : SettingsStore {
        val map = HashMap<String, String>()
        override val changes: SharedFlow<String> = MutableSharedFlow()
        override fun getString(key: String, default: String) = map[key] ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key]?.toInt() ?: default
        override fun putInt(key: String, value: Int) { map[key] = "$value" }
        override fun getFloat(key: String, default: Float) = map[key]?.toFloat() ?: default
        override fun putFloat(key: String, value: Float) { map[key] = "$value" }
        override fun getBoolean(key: String, default: Boolean) = map[key]?.toBoolean() ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = "$value" }
        override fun remove(key: String) { map.remove(key) }
    }

    @Test fun pageDefaultsMatchWhatTheCodeReads() {
        val s = Mem()
        val specs = MediaSettings.section.settings.associateBy { it.key }
        assertEquals(setOf(MediaSettings.OTHER_APPS, MediaSettings.ISLAND, MediaSettings.PAUSED_DRIFT), specs.keys)
        assertTrue(specs.values.all { it.type == SettingType.BOOLEAN && it.key.startsWith("media.") })
        assertEquals(specs.getValue(MediaSettings.OTHER_APPS).default.toBoolean(), MediaSettings.otherApps(s))
        assertEquals(specs.getValue(MediaSettings.ISLAND).default.toBoolean(), MediaSettings.island(s))
        assertEquals(specs.getValue(MediaSettings.PAUSED_DRIFT).default.toBoolean(), MediaSettings.pausedDrift(s))
        // Defaults: mirror other apps, island on, drift settles after 30 s.
        assertTrue(MediaSettings.otherApps(s)); assertTrue(MediaSettings.island(s)); assertFalse(MediaSettings.pausedDrift(s))
        s.putBoolean(MediaSettings.PAUSED_DRIFT, true)
        assertTrue(MediaSettings.pausedDrift(s))
    }
}
