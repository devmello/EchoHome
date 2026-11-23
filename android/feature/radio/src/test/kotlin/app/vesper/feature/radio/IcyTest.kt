package app.vesper.feature.radio

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IcyTest {
    /** A stream with [metaInt] audio bytes between metadata blocks; returns (stream, the audio alone). */
    private fun stream(metaInt: Int, blocks: List<String?>): Pair<ByteArray, ByteArray> {
        val s = ByteArrayOutputStream()
        val audio = ByteArrayOutputStream()
        var n = 0
        for (b in blocks) {
            repeat(metaInt) { val v = (n++ % 251).toByte(); s.write(v.toInt()); audio.write(v.toInt()) }
            if (b == null) s.write(0) else {
                val bytes = b.toByteArray(Charsets.UTF_8)
                val len = (bytes.size + 15) / 16
                s.write(len)
                s.write(bytes)
                repeat(len * 16 - bytes.size) { s.write(0) }
            }
        }
        repeat(metaInt / 2) { val v = (n++ % 251).toByte(); s.write(v.toInt()); audio.write(v.toInt()) }
        return s.toByteArray() to audio.toByteArray()
    }

    private fun strip(metaInt: Int, data: ByteArray, chunk: Int): Pair<ByteArray, List<String>> {
        val out = ByteArrayOutputStream()
        val metas = ArrayList<String>()
        val st = IcyStripper(metaInt) { metas += it }
        var i = 0
        while (i < data.size) { val n = minOf(chunk, data.size - i); st.feed(data, i, n, out); i += n }
        return out.toByteArray() to metas
    }

    @Test fun stripsMetadataAtAnyChunkSize() {
        val (data, audio) = stream(100, listOf("StreamTitle='Marisol Vey - Low Tide';", null, "StreamTitle='Ines Calder - Slow Bloom';StreamUrl='';"))
        for (chunk in listOf(1, 7, 100, 101, 4096)) {
            val (out, metas) = strip(100, data, chunk)
            assertArrayEquals("chunk $chunk", audio, out)
            assertEquals(2, metas.size)
            assertEquals("Marisol Vey - Low Tide", IcyMeta.streamTitle(metas[0]))
            assertEquals("Ines Calder - Slow Bloom", IcyMeta.streamTitle(metas[1]))
        }
    }

    @Test fun streamTitle() {
        assertEquals("Café del Mar - Sunset", IcyMeta.streamTitle("StreamTitle='Café del Mar - Sunset';"))
        assertEquals("Guns N' Roses - Patience", IcyMeta.streamTitle("StreamTitle='Guns N' Roses - Patience';StreamUrl='http://x';"))
        assertNull(IcyMeta.streamTitle("StreamTitle='';"))
        assertNull(IcyMeta.streamTitle("StreamTitle=' - ';"))
        assertNull(IcyMeta.streamTitle("StreamTitle='Advertisement';"))
        assertNull(IcyMeta.streamTitle("StreamUrl='x';"))
    }

    @Test fun latin1Fallback() {
        val b = "StreamTitle='Beyoncé - Halo';".toByteArray(Charsets.ISO_8859_1)
        assertEquals("Beyoncé - Halo", IcyMeta.streamTitle(IcyStripper.decode(b, b.size)))
        val padded = "StreamTitle='A - B';".toByteArray() + ByteArray(12)
        assertEquals("StreamTitle='A - B';", IcyStripper.decode(padded, padded.size))
    }

    @Test fun split() {
        assertEquals("Marisol Vey" to "Low Tide", IcyMeta.split("Marisol Vey - Low Tide"))
        assertEquals("A" to "B - C", IcyMeta.split("A - B - C"))
        assertEquals("Sigur Rós" to "Hoppípolla", IcyMeta.split("Sigur Rós – Hoppípolla"))
        assertEquals(null to "Morning news", IcyMeta.split("Morning news"))
        assertEquals(null to "-ish", IcyMeta.split("-ish"))
    }
}
