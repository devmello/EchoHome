package app.vesper.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsWordsTest {
    @Test fun parsesEnhancedWordStamps() {
        val l = Lrc.parse("[00:12.34] <00:12.34>Hello <00:12.80>world")
        assertEquals(1, l.size)
        assertEquals("Hello world", l[0].text)
        assertEquals(12_340L, l[0].timeMs)
        val w = l[0].words!!
        assertEquals(listOf(12_340L, 12_800L), w.map { it.startMs })
        assertEquals(listOf("Hello", "world"), w.map { l[0].text.substring(it.from, it.to) })
    }

    @Test fun syllablesJoinIntoOneToken() {
        val l = Lrc.parse("[00:01.00]<00:01.00>Hel<00:01.30>lo <00:01.60>you")[0]
        assertEquals("Hello you", l.text)
        assertEquals(listOf("Hel", "lo", "you"), l.words!!.map { l.text.substring(it.from, it.to) })
        assertEquals(listOf(1000L, 1300L, 1600L), l.words!!.map { it.startMs })
    }

    @Test fun enhancedTimesNeverGoBackwardsAndOffsetApplies() {
        val l = Lrc.parse("[offset:+200]\n[00:01.00]<00:01.00>a <00:00.50>b")[0]
        assertEquals(listOf(800L, 800L), l.words!!.map { it.startMs })
        assertEquals(800L, l.timeMs)
    }

    @Test fun plainLinesHaveNoWords() {
        val l = Lrc.parse("[00:01.00]just  a   line")[0]
        assertEquals("just a line", l.text)
        assertNull(l.words)
    }

    @Test fun syllableCounts() {
        assertEquals(1, WordTiming.syllables("a"))
        assertEquals(1, WordTiming.syllables("love"))
        assertEquals(3, WordTiming.syllables("beautiful"))
        assertEquals(2, WordTiming.syllables("little"))
        assertEquals(1, WordTiming.syllables("I'm"))
        assertEquals(1, WordTiming.syllables("..."))
    }

    @Test fun estimatesStayMonotonicAndInsideTheLine() {
        val text = "I said ooh I'm blinded by the lights"
        val start = 10_000L; val next = 15_000L
        val w = WordTiming.estimate(text, start, next)
        assertEquals(text.split(' '), w.map { text.substring(it.from, it.to) })
        assertEquals(start, w.first().startMs)
        for (i in 1 until w.size) assertTrue(w[i].startMs >= w[i - 1].startMs)
        val end = WordTiming.sungEnd(start, next, text)
        assertTrue(end <= start + (0.9 * (next - start)).toLong())
        assertTrue(w.all { it.startMs in start until end })
        // More syllables take longer: "blinded" (2) vs "by" (1).
        val byLen = w[6].startMs - w[5].startMs
        val blindedLen = w[5].startMs - w[4].startMs
        assertTrue(blindedLen > byLen)
    }

    @Test fun longGapsDoNotStretchTheLine() {
        val text = "Oh yeah"
        val end = WordTiming.sungEnd(0, 60_000, text)
        assertTrue(end <= 2_500L)
        // A tiny gap still stays within 0.9 of it.
        assertTrue(WordTiming.sungEnd(1_000, 1_500, text) <= 1_450L)
        // Zero or negative gaps collapse every word onto the start without going backwards.
        val w = WordTiming.estimate(text, 5_000, 5_000)
        assertTrue(w.all { it.startMs == 5_000L })
        assertTrue(WordTiming.estimate("", 0, 1000).isEmpty())
        assertNotNull(w)
    }
}
