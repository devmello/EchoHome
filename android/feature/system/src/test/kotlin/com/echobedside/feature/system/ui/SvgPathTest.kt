package com.echobedside.feature.system.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SvgPathTest {
    private class Rec : PathSink {
        val ops = mutableListOf<String>()
        var x = 0f; var y = 0f
        override fun moveTo(x: Float, y: Float) { ops += "M"; this.x = x; this.y = y }
        override fun lineTo(x: Float, y: Float) { ops += "L"; this.x = x; this.y = y }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) { ops += "C"; this.x = x; this.y = y }
        override fun close() { ops += "Z" }
    }

    @Test fun implicitLinetoAfterMoveAndRelativeCommands() {
        val r = Rec()
        SvgPath.parse("M11 5 6 9H3v6h3l5 4z", r)
        assertEquals(listOf("M", "L", "L", "L", "L", "L", "Z"), r.ops)
    }

    @Test fun compactNumbersAndNegatives() {
        val r = Rec()
        SvgPath.parse("M12 19.5h.01M4.9 19.1l1.4-1.4", r)
        assertEquals(listOf("M", "L", "M", "L"), r.ops)
        assertEquals(6.3f, r.x, 1e-4f)
        assertEquals(17.7f, r.y, 1e-4f)
    }

    @Test fun arcEndsExactlyAtItsEndpoint() {
        val r = Rec()
        SvgPath.parse("M15.5 8.5a5 5 0 0 1 0 7", r)
        assertEquals("M", r.ops.first())
        assertEquals(15.5f, r.x, 1e-4f)
        assertEquals(15.5f, r.y, 1e-4f)
        // chord 7 on radius 5 is an ~89° arc: one cubic
        assertEquals(2, r.ops.size)
        val semi = Rec()
        SvgPath.parse("M0 0a5 5 0 0 1 10 0", semi)
        assertEquals("a semicircle splits into two ≤90° cubics", 3, semi.ops.size)
    }

    @Test fun fullCircleFromTwoArcs() {
        val r = Rec()
        SvgPath.parse("M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", r)
        assertEquals(12f, r.x, 1e-4f)
        assertEquals(8f, r.y, 1e-4f)
        assertEquals("Z", r.ops.last())
    }

    @Test fun everyDesignIconParses() {
        // Spot-check the more unusual ones (smooth curves, many arcs).
        listOf(
            "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM7.5 9.5c3-1 6.5-.7 9 .8M8 12.8c2.5-.7 5.2-.4 7.4.8M8.6 15.8c2-.5 4-.3 5.8.6",
            "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0",
            "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z",
        ).forEach { SvgPath.parse(it, Rec()) }
    }
}
