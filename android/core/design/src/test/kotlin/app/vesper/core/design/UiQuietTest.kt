package app.vesper.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiQuietTest {
    @Test fun quietOnlyAfterNoFrameForQuietMs() {
        assertFalse(UiQuiet.quiet(now = 1_000, lastFrame = 1_000 - 16, touching = false)) // mid-animation
        assertFalse(UiQuiet.quiet(now = 1_000, lastFrame = 1_000 - UiQuiet.QUIET_MS + 1, touching = false))
        assertTrue(UiQuiet.quiet(now = 1_000, lastFrame = 1_000 - UiQuiet.QUIET_MS, touching = false))
        assertTrue(UiQuiet.quiet(now = 1_000, lastFrame = 0, touching = false)) // never installed
    }

    @Test fun aFingerDownIsNeverQuiet() {
        assertFalse(UiQuiet.quiet(now = 10_000, lastFrame = 0, touching = true))
    }

    @Test fun waitCoversTheRestOfTheQuietWindow() {
        assertEquals(UiQuiet.QUIET_MS - 16, UiQuiet.waitMs(now = 1_000, lastFrame = 1_000 - 16, touching = false))
        assertEquals(1L, UiQuiet.waitMs(now = 1_000, lastFrame = 0, touching = false))
        assertEquals(UiQuiet.QUIET_MS, UiQuiet.waitMs(now = 1_000, lastFrame = 1_000, touching = false))
        assertEquals(UiQuiet.QUIET_MS * 2, UiQuiet.waitMs(now = 1_000, lastFrame = 0, touching = true))
    }
}
