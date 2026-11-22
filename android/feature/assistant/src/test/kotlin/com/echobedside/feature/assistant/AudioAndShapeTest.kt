package com.echobedside.feature.assistant

import com.echobedside.feature.assistant.audio.AudioChunker
import com.echobedside.feature.assistant.audio.Pcm
import com.echobedside.feature.assistant.audio.VoiceGate
import com.echobedside.feature.assistant.ui.Blob
import com.echobedside.feature.assistant.ui.PathSink
import com.echobedside.feature.assistant.ui.SvgPath
import com.echobedside.feature.assistant.voice.pcm16ToFloat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAndShapeTest {

    @Test fun `chunker emits fixed 40 ms chunks`() {
        val out = ArrayList<ByteArray>()
        val c = AudioChunker { out += it }
        c.write(ByteArray(1000)); c.write(ByteArray(2000))
        assertEquals(2, out.size)
        assertTrue(out.all { it.size == Pcm.CHUNK_BYTES })
    }

    @Test fun `voice gate passes speech with its pre-roll and closes after the hold`() {
        val g = VoiceGate(prerollMs = 120, holdMs = 200)
        val quiet = ByteArray(1280)
        repeat(10) { assertTrue(g.process(quiet, -70f).isEmpty()) }
        val loud = ByteArray(1280)
        assertEquals(4, g.process(loud, -20f).size) // 3 pre-roll + this one
        assertEquals(1, g.process(quiet, -70f).size) // held open
        repeat(5) { g.process(quiet, -70f) }
        assertTrue(g.process(quiet, -70f).isEmpty())
    }

    @Test fun `pcm16 to float`() {
        val f = pcm16ToFloat(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F))
        assertEquals(0.5f, f[0], 1e-4f)
        assertEquals(-0.5f, f[1], 1e-4f)
        assertEquals(1f, f[2], 1e-3f)
    }

    private class Rec : PathSink {
        val ops = ArrayList<String>()
        override fun moveTo(x: Float, y: Float) { ops += "M" }
        override fun lineTo(x: Float, y: Float) { ops += "L" }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { ops += "C" }
        override fun close() { ops += "Z" }
    }

    @Test fun `svg icons parse (relative, shorthand, arcs)`() {
        val r = Rec()
        SvgPath.parse("M12 2a7 7 0 0 0-4 12.74V17a2 2 0 0 0 2 2h4z", r)
        assertEquals("M", r.ops.first())
        assertEquals("Z", r.ops.last())
        assertTrue(r.ops.count { it == "C" } >= 2)
        val check = Rec()
        SvgPath.parse("m5 12.5 4.5 4.5L19 7.5", check)
        assertEquals(listOf("M", "L", "L"), check.ops)
    }

    @Test fun `blob radii wobble around 50 percent and the path closes`() {
        val r = Blob.radii(1.234, 9f)
        assertTrue(r.all { it in 41f..59f })
        val calm = Blob.radii(1.234, 0f)
        assertTrue(calm.all { it == 50f })
        val rec = Rec()
        Blob.path(rec, 0f, 0f, 116f, 116f, r)
        assertEquals(listOf("M", "L", "C", "L", "C", "L", "C", "L", "C", "Z"), rec.ops)
        assertEquals(116f, Blob.size("listen"))
        assertEquals(84f, Blob.size("idle"))
    }
}
