package app.vesper.feature.assistant.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceChunkerTest {
    private fun chunks(vararg deltas: String): List<String> {
        val c = SentenceChunker()
        val out = ArrayList<String>()
        for (d in deltas) out += c.add(d)
        c.flush()?.let(out::add)
        return out
    }

    @Test fun `sentences come out as soon as they end`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.add("Rain starts"))
        assertEquals(emptyList<String>(), c.add(" around eight tomorrow."))
        assertEquals(listOf("Rain starts around eight tomorrow."), c.add(" Your"))
        assertEquals(emptyList<String>(), c.add(" alarm is set"))
        assertEquals("Your alarm is set", c.flush())
        assertNull(c.flush())
    }

    @Test fun `the first piece may be a clause, later ones are whole sentences`() {
        assertEquals(
            listOf("Your nine thirty is twenty five minutes away,", "so I moved your alarm to six forty, as you asked.", "Okay?"),
            chunks("Your nine thirty is twenty five minutes away, so I moved your alarm to six forty, as you asked. Okay?"))
        // A short opener isn't cut at its comma.
        assertEquals(listOf("Sure, it's done."), chunks("Sure, it's done."))
    }

    @Test fun `times, decimals and abbreviations are not sentence ends`() {
        assertEquals(listOf("It's 9.5 degrees at 6:40 a.m. on St. Mark's Place.", "Done!"),
            chunks("It's 9.5 degrees at 6:40 a.m. on St. Mark's Place. Done!"))
    }

    @Test fun `text before a tool call can be dropped`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.add("Let me check"))
        c.reset()
        assertEquals(listOf("It's sunny."), c.add("It's sunny. "))
    }
}
