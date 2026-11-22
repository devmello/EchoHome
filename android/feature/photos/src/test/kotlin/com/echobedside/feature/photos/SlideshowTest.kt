package com.echobedside.feature.photos

import com.echobedside.feature.photos.data.PhotoAsset
import com.echobedside.feature.photos.data.Slideshow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {
    private fun assets(vararg ids: String) = ids.map { PhotoAsset(it, null) }

    /** Plays the prefetched next photo, as the screen does. */
    private fun Slideshow.step(now: Long): String? = next()?.also { advanceTo(it, now) }?.id

    @Test fun playsInOrderAndWrapsWithARefresh() {
        val s = Slideshow()
        s.setAssets(assets("a", "b", "c"), shuffle = false)
        assertEquals("a", s.next()?.id)
        assertEquals("a", s.step(0))
        assertEquals("b", s.step(1))
        assertEquals("c", s.step(2))
        assertFalse(s.needsRefresh)
        assertEquals("a", s.step(3))
        assertTrue(s.needsRefresh) // a full pass: reload the album
    }

    @Test fun timingAndPause() {
        val s = Slideshow().apply { seconds = 8 }
        s.setAssets(assets("a", "b"), shuffle = false)
        assertFalse(s.due(0)) // nothing shown yet
        s.step(1_000)
        assertFalse(s.due(8_999))
        assertTrue(s.due(9_000))
        assertEquals(3_000L, s.msUntilDue(6_000))
        assertEquals(0L, s.msUntilDue(20_000))
        s.paused = true
        assertFalse(s.due(20_000))
        assertNull(s.msUntilDue(20_000))
        s.paused = false
        s.restartTimer(20_000)
        assertFalse(s.due(27_999))
        assertTrue(s.due(28_000))
    }

    @Test fun prefetchTargetSkipsFailedAndUncached() {
        val s = Slideshow()
        s.setAssets(assets("a", "b", "c", "d"), shuffle = false)
        s.step(0)
        s.markFailed(PhotoAsset("b", null))
        assertEquals("c", s.next()?.id)
        s.playable = { it.id != "c" } // offline: c isn't cached
        assertEquals("d", s.next()?.id)
        s.step(1)
        assertEquals("a", s.next()?.id)
    }

    @Test fun nothingToPrefetchWhenOnlyTheCurrentPhotoIsPlayable() {
        val s = Slideshow()
        s.setAssets(assets("a", "b"), shuffle = false)
        s.step(0)
        s.playable = { it.id == "a" }
        assertNull(s.next())
        val one = Slideshow()
        one.setAssets(assets("solo"), shuffle = false)
        assertEquals("solo", one.step(0))
        assertNull(one.next())
    }

    @Test fun refreshKeepsTheCurrentPhotoFirst() {
        val s = Slideshow(Random(1))
        s.setAssets(assets("a", "b", "c", "d", "e"))
        val first = s.step(0)!!
        s.setAssets(assets("a", "b", "c", "d", "e", "f"))
        assertEquals(first, s.current?.id)
        assertEquals(6, s.size)
        assertTrue(s.next()?.id != first)
        assertFalse(s.needsRefresh)
    }

    @Test fun shuffleVisitsEveryPhotoOncePerPass() {
        val s = Slideshow(Random(7))
        val ids = (1..20).map { "p$it" }
        s.setAssets(ids.map { PhotoAsset(it, null) })
        val seen = (0 until 20).map { s.step(it.toLong())!! }
        assertEquals(ids.toSet(), seen.toSet())
        assertFalse(s.needsRefresh)
        s.step(21)
        assertTrue(s.needsRefresh)
    }

    @Test fun emptyAlbum() {
        val s = Slideshow()
        s.setAssets(emptyList())
        assertTrue(s.isEmpty)
        assertNull(s.next())
        assertNull(s.current)
        assertFalse(s.due(100_000))
    }
}
