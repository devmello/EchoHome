package app.vesper.feature.system.banner

import app.vesper.core.model.Banner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerQueueTest {
    private fun b(id: String, at: Long = 1, critical: Boolean = false) = Banner(id, "App", 0, title = "T$id", body = "B", postedAtMillis = at, critical = critical)

    @Test fun showsEachBannerOnceThenLeaves() {
        val q = BannerQueue()
        val feed = listOf(b("1"))
        q.update(feed, dnd = false, now = 0)
        assertEquals("1", q.current?.id)
        q.update(feed, false, 100)
        q.tick(BannerQueue.SHOW_MS)
        assertNull(q.current)
        q.update(feed, false, BannerQueue.SHOW_MS + 10)
        assertNull("not re-shown", q.current)
        q.update(listOf(b("1", at = 2)), false, BannerQueue.SHOW_MS + 20)
        assertEquals("re-posted with a newer time → shown again", "1", q.current?.id)
    }

    @Test fun newBannerTucksThePreviousBehind() {
        val q = BannerQueue()
        q.update(listOf(b("1")), false, 0)
        q.update(listOf(b("1"), b("2")), false, 1000)
        assertEquals("2", q.current?.id)
        assertEquals("1", q.behind?.id)
        q.tick(1000 + BannerQueue.BEHIND_MS)
        assertNull(q.behind)
        assertEquals("2", q.current?.id)
    }

    @Test fun doNotDisturbHidesAllButCritical() {
        val q = BannerQueue()
        q.update(listOf(b("msg"), b("door", critical = true)), dnd = true, now = 0)
        assertEquals("door", q.current?.id)
        q.tick(BannerQueue.SHOW_MS)
        assertNull(q.current)
    }

    @Test fun expandedStaysUntilClosed() {
        val q = BannerQueue()
        q.update(listOf(b("1")), false, 0)
        assertTrue(q.expand())
        q.tick(60_000)
        assertEquals("1", q.current?.id)
        q.close(60_000)
        assertNull(q.current)
    }

    @Test fun removedFromFeedLeavesTheScreen() {
        val q = BannerQueue()
        q.update(listOf(b("1")), false, 0)
        q.update(emptyList(), false, 10)
        assertNull(q.current)
    }

    @Test fun settingsChangeTimeAndDndCritical() {
        val q = BannerQueue().apply { showMs = 2000; dndAllowsCritical = false }
        q.update(listOf(b("door", critical = true)), dnd = true, now = 0)
        assertNull("critical hidden too when the setting is off", q.current)
        q.update(listOf(b("msg", at = 5)), dnd = false, now = 10)
        assertEquals("msg", q.current?.id)
        q.tick(10 + 2000)
        assertNull(q.current)
    }

    @Test fun queuedWhileExpanded() {
        val q = BannerQueue()
        q.update(listOf(b("1")), false, 0)
        q.expand()
        q.update(listOf(b("1"), b("2")), false, 100)
        assertEquals("1", q.current?.id)
        q.close(200)
        assertEquals("2", q.current?.id)
    }
}
