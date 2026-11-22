package com.echobedside.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreBlurTest {
    @Test fun boxesApproximateSigma() {
        val boxes = PreBlur.boxesForGauss(4f, 3)
        assertEquals(3, boxes.size)
        boxes.forEach { assertTrue(it % 2 == 1) }
        // variance of n boxes of width w is sum((w^2-1)/12) ≈ sigma^2
        val variance = boxes.sumOf { (it * it - 1) / 12.0 }
        assertEquals(16.0, variance, 4.0)
    }

    @Test fun blurKeepsFlatImageFlatAndSpreadsAPoint() {
        val w = 32; val h = 16
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        PreBlur.gaussian(flat, w, h, 3f)
        flat.forEach { assertEquals(0xFF336699.toInt(), it) }

        val dot = IntArray(w * h) { 0xFF000000.toInt() }
        dot[8 * w + 16] = 0xFFFFFFFF.toInt()
        PreBlur.gaussian(dot, w, h, 2f)
        val centre = dot[8 * w + 16] and 255
        val near = dot[8 * w + 18] and 255
        assertTrue(centre in 1..254)
        assertTrue(near in 1 until centre + 1)
    }

    @Test fun nightMapsRgbKeepsAlpha() {
        assertEquals(0x80BE180E.toInt(), Night.map(0x80FFFFFF.toInt(), true))
        assertEquals(0x80FFFFFF.toInt(), Night.map(0x80FFFFFF.toInt(), false))
    }
}
