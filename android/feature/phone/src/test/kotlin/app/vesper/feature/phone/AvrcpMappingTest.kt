package app.vesper.feature.phone

import app.vesper.core.model.MediaSourceKind
import app.vesper.feature.phone.audio.AvrcpMapping
import app.vesper.feature.phone.audio.AvrcpMapping.STATE_ERROR
import app.vesper.feature.phone.audio.AvrcpMapping.STATE_NONE
import app.vesper.feature.phone.audio.AvrcpMapping.STATE_PAUSED
import app.vesper.feature.phone.audio.AvrcpMapping.STATE_PLAYING
import app.vesper.feature.phone.audio.AvrcpSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvrcpMappingTest {
    private fun snap(state: Int = STATE_PLAYING, pos: Long = 61_000, at: Long = 5_000, title: String? = "Slow Tide", dur: Long = 214_000) =
        AvrcpSnapshot(title, "Harbor Lights", "Night Swim", dur, state, pos, at)

    @Test fun playingTrackMapsToABluetoothSource() {
        val s = AvrcpMapping.toState(snap(), "Maya’s iPhone", 0.4f, now = 9_000)!!
        assertEquals("Slow Tide", s.title)
        assertEquals("Harbor Lights", s.artist)
        assertEquals("Night Swim", s.album)
        assertEquals(214_000, s.durationMs)
        assertEquals(61_000, s.positionMs)
        assertEquals(5_000, s.positionSampledAt)
        assertTrue(s.playing)
        assertEquals(MediaSourceKind.BLUETOOTH, s.source)
        assertEquals("Maya’s iPhone", s.sourceLabel)
        assertEquals(0.4f, s.volume)
        assertEquals(65_000, s.positionNow(9_000)) // extrapolated by readers
        assertEquals("bluetooth", AvrcpMapping.SOURCE_ID)
    }

    @Test fun pausedDoesNotAdvance() {
        val s = AvrcpMapping.toState(snap(STATE_PAUSED), "x", null, 9_000)!!
        assertFalse(s.playing)
        assertEquals(61_000, s.positionNow(60_000))
    }

    @Test fun nothingToShow() {
        assertNull(AvrcpMapping.toState(null, "x", null, 0))
        assertNull(AvrcpMapping.toState(snap(STATE_NONE), "x", null, 0))
        assertNull(AvrcpMapping.toState(snap(STATE_ERROR), "x", null, 0)) // the stack's "not connected"
        assertNull(AvrcpMapping.toState(snap(title = " ").copy(artist = ""), "x", null, 0))
    }

    @Test fun unknownPositionAndDurationAreSafe() {
        val s = AvrcpMapping.toState(snap(pos = -1, at = 0, dur = 0), "x", null, now = 42)!!
        assertEquals(0, s.positionMs)
        assertEquals(42, s.positionSampledAt)
        assertEquals(0, s.durationMs)
        val over = AvrcpMapping.toState(snap(pos = 500_000), "x", null, 0)!!
        assertEquals(214_000, over.positionMs)
    }

    @Test fun steadyPlaybackIsNotAChange() {
        val a = AvrcpMapping.toState(snap(pos = 61_000, at = 5_000), "x", null, 0)
        val b = AvrcpMapping.toState(snap(pos = 62_000, at = 6_000), "x", null, 0)
        assertTrue(AvrcpMapping.sameForUi(a, b))
        val seek = AvrcpMapping.toState(snap(pos = 120_000, at = 6_000), "x", null, 0)
        assertFalse(AvrcpMapping.sameForUi(a, seek))
        assertFalse(AvrcpMapping.sameForUi(a, AvrcpMapping.toState(snap(STATE_PAUSED), "x", null, 0)))
        assertFalse(AvrcpMapping.sameForUi(a, AvrcpMapping.toState(snap(title = "Other"), "x", null, 0)))
    }

    @Test fun routeChips() {
        assertEquals(listOf("Bluetooth", "Maya’s iPhone"), AvrcpMapping.route("Maya’s iPhone"))
        assertEquals(listOf("Bluetooth"), AvrcpMapping.route(""))
    }
}
