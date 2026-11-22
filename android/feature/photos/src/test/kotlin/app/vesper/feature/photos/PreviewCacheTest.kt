package app.vesper.feature.photos

import app.vesper.feature.photos.data.MetaStore
import app.vesper.feature.photos.data.PhotoAsset
import app.vesper.feature.photos.data.Album
import app.vesper.feature.photos.data.PreviewCache
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewCacheTest {
    private val dir: File = Files.createTempDirectory("cache").toFile()
    private var t = 1_000_000L
    private val clock = { t }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun put(c: PreviewCache, key: String, size: Int) {
        c.fileFor(key).writeBytes(ByteArray(size))
        t += 1000
        c.commit(key)
    }

    @Test fun evictsLeastRecentlyUsedPastTheCap() {
        val c = PreviewCache(dir, maxBytes = 300, clock = clock)
        put(c, "a", 100); put(c, "b", 100); put(c, "c", 100)
        assertEquals(300, c.sizeBytes)
        t += 1000
        assertNotNull(c.get("a")) // a is now the most recent
        put(c, "d", 100)          // over the cap: b (least recent) goes
        assertTrue(c.contains("a")); assertFalse(c.contains("b")); assertTrue(c.contains("c")); assertTrue(c.contains("d"))
        assertFalse(c.fileFor("b").exists())
        assertEquals(300, c.sizeBytes)
    }

    @Test fun anEntryBiggerThanTheCapIsKeptAlone() {
        val c = PreviewCache(dir, maxBytes = 150, clock = clock)
        put(c, "a", 100)
        put(c, "big", 400)
        assertFalse(c.contains("a"))
        assertTrue(c.contains("big"))
        assertEquals(1, c.count)
    }

    @Test fun recencySurvivesARestart() {
        val c = PreviewCache(dir, maxBytes = 1000, clock = clock)
        put(c, "a", 100); put(c, "b", 100); put(c, "c", 100)
        t += 1000
        c.get("a")
        // restart with a smaller cap: the oldest by mtime (b, then c) go first
        val c2 = PreviewCache(dir, maxBytes = 200, clock = clock)
        assertTrue(c2.contains("a"))
        assertFalse(c2.contains("b"))
        assertTrue(c2.contains("c"))
        assertEquals(200, c2.sizeBytes)
    }

    @Test fun cleansUpTempFilesAndHandlesMissingFiles() {
        File(dir, "x.img.tmp").writeBytes(ByteArray(10))
        val c = PreviewCache(dir, maxBytes = 1000, clock = clock)
        assertFalse(File(dir, "x.img.tmp").exists())
        put(c, "a", 50)
        c.fileFor("a").delete()
        assertNull(c.get("a"))
        assertEquals(0, c.sizeBytes)
        assertNull(c.commit("never-written"))
    }

    @Test fun removeDeletes() {
        val c = PreviewCache(dir, maxBytes = 1000, clock = clock)
        put(c, "a", 50)
        c.remove("a")
        assertFalse(c.contains("a"))
        assertFalse(c.fileFor("a").exists())
        assertEquals(0, c.sizeBytes)
    }

    @Test fun keysAreFileSafe() {
        assertEquals("abc-123_def.img", PreviewCache.fileName("abc-123/def"))
    }

    @Test fun metaStoreRoundTrips() {
        val m = MetaStore(File(dir, "meta"))
        m.saveAlbums(listOf(Album("a1", "Family", 3)))
        assertEquals(listOf(Album("a1", "Family", 3)), m.loadAlbums())
        val assets = listOf(PhotoAsset("p1", LocalDateTime.of(2025, 10, 12, 18, 30), "Big Sur", 4032, 3024), PhotoAsset("p2", null))
        m.saveAssets("a1", assets)
        assertEquals(assets, m.loadAssets("a1"))
        assertNull(m.loadAssets("other"))
    }
}
