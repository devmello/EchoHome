package app.vesper.feature.media

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LyricsTest {
    @Test fun parsesStampsAndText() {
        val l = Lrc.parse("[ar:The Weeknd]\n[00:12.34]Hello\n[01:02.5] World \n[00:03]early\nnot a lyric")
        assertEquals(listOf(3000L, 12340L, 62500L), l.map { it.timeMs })
        assertEquals(listOf("early", "Hello", "World"), l.map { it.text })
    }

    @Test fun severalStampsPerLine() {
        val l = Lrc.parse("[00:10.00][00:30.00][01:00.123]chorus")
        assertEquals(listOf(10_000L, 30_000L, 60_123L), l.map { it.timeMs })
        assertTrue(l.all { it.text == "chorus" })
    }

    @Test fun offsetShiftsLines() {
        // Positive offset = lyrics appear earlier.
        assertEquals(9_500L, Lrc.parse("[offset:+500]\n[00:10.00]a")[0].timeMs)
        assertEquals(10_500L, Lrc.parse("[offset:-500]\n[00:10.00]a")[0].timeMs)
        assertEquals(0L, Lrc.parse("[offset:2000]\n[00:01.00]a")[0].timeMs)
    }

    @Test fun keepsBlankLinesAndDropsWordTags() {
        val l = Lrc.parse("[00:01.00]<00:01.00>one <00:01.50>two\n[00:05.00]\n[00:07.00]three")
        assertEquals("one two", l[0].text)
        assertEquals("", l[1].text)
        assertEquals(3, l.size)
    }

    @Test fun lineAtPosition() {
        val l = Lrc.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, Lrc.lineAt(l, 0))
        assertEquals(-1, Lrc.lineAt(l, 9_999))
        assertEquals(0, Lrc.lineAt(l, 10_000))
        assertEquals(0, Lrc.lineAt(l, 19_999))
        assertEquals(1, Lrc.lineAt(l, 20_000))
        assertEquals(2, Lrc.lineAt(l, 999_999))
        assertEquals(-1, Lrc.lineAt(emptyList(), 5))
        assertEquals(20_000L, Lrc.nextStart(l, 0))
        assertEquals(-1L, Lrc.nextStart(l, 2))
        assertEquals(10_000L, Lrc.nextStart(l, -1))
    }

    @Test fun wrapsToWidth() {
        val w = { s: String -> s.length * 10f }
        assertEquals(listOf("aaa bbb", "ccc"), Lrc.wrap("aaa bbb ccc", 75f, w))
        assertEquals(listOf("averyveryverylongword"), Lrc.wrap("averyveryverylongword", 50f, w))
        assertEquals(listOf(""), Lrc.wrap("", 50f, w))
    }

    @Test fun lineBrightnessFallsOffAroundTheCurrentLine() {
        assertEquals(1f, LyricsView.alphaAt(0f), 0f)
        assertTrue(LyricsView.alphaAt(1f) < LyricsView.alphaAt(0f))
        assertTrue(LyricsView.alphaAt(-1f) < LyricsView.alphaAt(1f))   // past lines are the faintest
        assertTrue(LyricsView.alphaAt(-0.5f) > LyricsView.alphaAt(-1f))
        assertTrue(LyricsView.alphaAt(9f) >= 0.3f)
    }

    @Test fun lrclibRecords() {
        val synced = JSONObject("""{"instrumental":false,"plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a\n[00:02.00]b"}""")
        assertTrue(LrcLib.fromRecord(synced) is Lyrics.Synced)
        val plain = JSONObject("""{"instrumental":false,"plainLyrics":"a\nb","syncedLyrics":null}""")
        assertEquals(listOf("a", "b"), (LrcLib.fromRecord(plain) as Lyrics.Plain).lines)
        assertTrue(LrcLib.fromRecord(JSONObject("""{"instrumental":true}""")) is Lyrics.None)
        assertNull(LrcLib.fromRecord(JSONObject("""{"instrumental":false}""")))
    }

    @Test fun searchPrefersSyncedWithinDuration() {
        val arr = JSONArray("""[
            {"duration":300,"syncedLyrics":"[00:01.00]wrong length"},
            {"duration":201,"plainLyrics":"plain only"},
            {"duration":199,"syncedLyrics":"[00:01.00]right"}]""")
        val r = LrcLib.fromSearch(arr, 200) as Lyrics.Synced
        assertEquals("right", r.lines[0].text)
        assertNull(LrcLib.fromSearch(JSONArray("""[{"duration":400,"plainLyrics":"x"}]"""), 200))
    }

    @Test fun keyIsCaseAndSpaceInsensitive() {
        assertEquals(LrcLib.key("Blinding Lights", "The Weeknd", "After Hours", 200_400),
            LrcLib.key(" blinding lights", "the weeknd ", "After hours", 200_900))
    }

    @Test fun repositoryFetchesCachesAndNegativeCaches() {
        val dir = Files.createTempDirectory("lyr").toFile()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"instrumental":false,"syncedLyrics":"[00:01.00]hi"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))   // /get for the second track
        server.enqueue(MockResponse().setBody("[]"))                         // /search: nothing
        server.start()
        try {
            val repo = LyricsRepository(dir).also { it.base = server.url("/api").toString().trimEnd('/') }
            assertNull(repo.cached("k1"))
            val r = repo.fetch("k1", "Song", "Artist", "Album", 200_000) as Lyrics.Synced
            assertEquals("hi", r.lines[0].text)
            val first = server.takeRequest()
            assertEquals("Vesper/0.1 (smart display)", first.getHeader("User-Agent"))
            assertTrue(first.path!!.contains("track_name=Song") && first.path!!.contains("duration=200"))

            // A fresh repository (empty memory) reads the disk cache: no more requests.
            val again = LyricsRepository(dir).also { it.base = repo.base }
            assertTrue(again.cached("k1") is Lyrics.Synced)

            assertTrue(repo.fetch("k2", "Nope", "Nobody", "", 0) is Lyrics.None)
            assertEquals(3, server.requestCount)
            assertTrue(LyricsRepository(dir).also { it.base = repo.base }.cached("k2") is Lyrics.None)
            assertNotNull(server.takeRequest())
        } finally {
            server.shutdown(); dir.deleteRecursively()
        }
    }

    @Test fun networkErrorIsNotCached() {
        val dir = Files.createTempDirectory("lyr").toFile()
        val server = MockWebServer().also { it.start() }
        val base = server.url("/api").toString().trimEnd('/')
        server.shutdown() // nothing listening
        try {
            val repo = LyricsRepository(dir).also { it.base = base }
            assertNull(repo.fetch("k", "a", "b", "c", 1000))
            assertNull(repo.cached("k"))
        } finally { dir.deleteRecursively() }
    }
}
