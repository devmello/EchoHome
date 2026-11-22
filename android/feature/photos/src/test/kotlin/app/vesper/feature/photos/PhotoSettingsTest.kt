package app.vesper.feature.photos

import app.vesper.core.model.SettingType
import app.vesper.core.model.SettingsStore
import app.vesper.feature.photos.data.Album
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSettingsTest {
    /** Like SharedPreferences: reading a key with the wrong type throws ClassCastException. */
    private class TypedStore : SettingsStore {
        val map = HashMap<String, Any>()
        private inline fun <reified T> read(key: String, default: T): T = map[key]?.let { it as T } ?: default
        override fun getString(key: String, default: String) = read(key, default)
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getInt(key: String, default: Int) = read(key, default)
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getFloat(key: String, default: Float) = read(key, default)
        override fun putFloat(key: String, value: Float) { map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = read(key, default)
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override val changes: SharedFlow<String> = MutableSharedFlow()
    }

    @Test fun albumIsAChoiceOnceAlbumsAreKnown() {
        val s = PhotoSettings.section(listOf(Album("t", "Trips", 5), Album("f", "Family", 40), Album("b", "Big", 90)))
        val album = s.settings.first()
        assertEquals(PhotoSettings.ALBUM_ID, album.key)
        assertEquals(SettingType.CHOICE, album.type)
        assertEquals(listOf("t" to "Trips (5)", "f" to "Family (40)", "b" to "Big (90)"), album.options)
        assertEquals("f", album.default) // "Family" first, as the frame does
        assertEquals("b", PhotoSettings.section(listOf(Album("t", "Trips", 5), Album("b", "Big", 90))).settings.first().default)
        assertEquals(listOf(PhotoSettings.ALBUM_ID, PhotoSettings.SECONDS, PhotoSettings.SHUFFLE, PhotoSettings.IDLE_MINUTES, PhotoSettings.NOT_AT_NIGHT),
            s.settings.map { it.key })
        assertEquals("photos", s.id)
    }

    @Test fun albumIsTextBeforeAlbumsCanBeListed() {
        for (albums in listOf(null, emptyList<Album>())) {
            val album = PhotoSettings.section(albums).settings.first()
            assertEquals(PhotoSettings.ALBUM_NAME, album.key)
            assertEquals(SettingType.TEXT, album.type)
        }
    }

    @Test fun readsValuesStoredAsStringsOrTyped() {
        val st = TypedStore()
        assertEquals(8, PhotoSettings.seconds(st))
        assertTrue(PhotoSettings.shuffle(st))
        assertTrue(PhotoSettings.notAtNight(st))
        assertEquals(0, PhotoSettings.idleMinutes(st))
        st.putString(PhotoSettings.SECONDS, "30"); st.putString(PhotoSettings.SHUFFLE, "false"); st.putString(PhotoSettings.IDLE_MINUTES, "15")
        assertEquals(30, PhotoSettings.seconds(st)); assertFalse(PhotoSettings.shuffle(st)); assertEquals(15, PhotoSettings.idleMinutes(st))
        st.putInt(PhotoSettings.SECONDS, 2); st.putBoolean(PhotoSettings.SHUFFLE, true); st.putFloat(PhotoSettings.IDLE_MINUTES, 999f)
        assertEquals(3, PhotoSettings.seconds(st)) // clamped
        assertTrue(PhotoSettings.shuffle(st))
        assertEquals(PhotoSettings.MAX_IDLE_MINUTES, PhotoSettings.idleMinutes(st))
        st.putString(PhotoSettings.NOT_AT_NIGHT, "garbage")
        assertTrue(PhotoSettings.notAtNight(st)) // unreadable → default
    }

    @Test fun autoShowRules() {
        val min = 60_000L
        assertTrue(PhotoSettings.shouldAutoShow(10 * min, 10, "standby", night = false, notAtNight = true))
        assertFalse(PhotoSettings.shouldAutoShow(9 * min, 10, "standby", night = false, notAtNight = true))
        assertFalse(PhotoSettings.shouldAutoShow(99 * min, 0, "standby", night = false, notAtNight = true)) // off
        assertFalse(PhotoSettings.shouldAutoShow(99 * min, 10, "nowplaying", night = false, notAtNight = true))
        assertFalse(PhotoSettings.shouldAutoShow(99 * min, 10, "standby", night = true, notAtNight = true))
        assertTrue(PhotoSettings.shouldAutoShow(99 * min, 10, "standby", night = true, notAtNight = false))
    }
}
