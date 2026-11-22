package com.echobedside.feature.camera

import com.echobedside.feature.camera.capture.YPlane
import com.echobedside.feature.camera.face.ConsecutiveMatcher
import com.echobedside.feature.camera.face.FaceBox
import com.echobedside.feature.camera.face.FaceGeometry
import com.echobedside.feature.camera.face.FaceMatcher
import com.echobedside.feature.camera.face.FaceTemplates
import com.echobedside.feature.camera.face.GreyImage
import com.echobedside.feature.camera.face.LbpEmbedder
import com.echobedside.feature.camera.face.Match
import com.echobedside.feature.camera.face.MatchPolicy
import com.echobedside.feature.camera.face.QualityFilter
import com.echobedside.feature.camera.face.QualityIssue
import com.echobedside.feature.camera.face.cosine
import com.echobedside.feature.camera.face.cropFace
import com.echobedside.feature.camera.face.l2normalize
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosineTest {
    @Test fun identicalIsOne() = assertEquals(1f, cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), 1e-6f)
    @Test fun scaleInvariant() = assertEquals(1f, cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-6f)
    @Test fun orthogonalIsZero() = assertEquals(0f, cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    @Test fun oppositeIsMinusOne() = assertEquals(-1f, cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    @Test fun zeroVectorIsZero() = assertEquals(0f, cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-6f)
    @Test(expected = IllegalArgumentException::class) fun dimMismatchThrows() { cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)) }
    @Test fun l2() { val v = l2normalize(floatArrayOf(3f, 4f)); assertEquals(0.6f, v[0], 1e-6f); assertEquals(0.8f, v[1], 1e-6f) }
}

class MatcherTest {
    private val rnd = Random(7)
    private fun unit(n: Int = 64) = l2normalize(FloatArray(n) { rnd.nextFloat() - 0.5f })
    private fun near(v: FloatArray, noise: Float) = l2normalize(FloatArray(v.size) { v[it] + (rnd.nextFloat() - 0.5f) * noise })

    @Test fun matchesTheRightPerson() {
        val maya = unit(); val sam = unit()
        val people = listOf(
            FaceTemplates("maya", "e", List(5) { near(maya, 0.05f) }),
            FaceTemplates("sam", "e", List(5) { near(sam, 0.05f) }),
        )
        val m = FaceMatcher(MatchPolicy.LBP).match(near(maya, 0.05f), people)
        assertEquals("maya", m.profileId)
        assertTrue(m.score > m.secondScore)
    }

    @Test fun strangerIsRejected() {
        val people = listOf(FaceTemplates("maya", "e", List(5) { near(unit(), 0.02f) }))
        assertNull(FaceMatcher(MatchPolicy.LBP).match(unit(), people).profileId)
    }

    @Test fun excludedPersonIsSkipped() {
        val maya = unit()
        val people = listOf(FaceTemplates("maya", "e", List(5) { near(maya, 0.05f) }))
        assertNull(FaceMatcher().match(near(maya, 0.05f), people, excluded = setOf("maya")).profileId)
    }

    @Test fun tooCloseToRunnerUpIsNoMatch() {
        val a = unit()
        val people = listOf(FaceTemplates("a", "e", List(3) { a }), FaceTemplates("b", "e", List(3) { a }))
        val m = FaceMatcher(MatchPolicy.LBP).match(a, people)
        assertNull(m.profileId)
        assertEquals(m.score, m.secondScore, 1e-6f)
    }

    @Test fun wrongDimensionTemplatesAreIgnored() {
        val people = listOf(FaceTemplates("old", "e", List(3) { FloatArray(10) { 1f } }))
        assertNull(FaceMatcher().match(unit(64), people).profileId)
    }

    @Test fun personalThresholdIsWeakestEnrolmentPair() {
        val v = listOf(floatArrayOf(1f, 0f), floatArrayOf(0.8f, 0.6f), floatArrayOf(1f, 0.1f))
        val t = FaceTemplates("x", "e", v)
        // weakest pair: (0.8,0.6)·(1,0) = 0.8 → threshold 0.79; never below the floor
        assertEquals(0.79f, t.threshold(MatchPolicy.LBP), 1e-4f)
        assertEquals(0.9f, t.threshold(MatchPolicy.LBP.copy(floor = 0.9f)), 1e-6f)
    }
}

class ConsecutiveMatcherTest {
    private fun m(id: String?) = Match(id, if (id != null) 0.9f else 0.1f, 0f)

    @Test fun twoInARowDecides() {
        val c = ConsecutiveMatcher()
        assertNull(c.offer(m("maya")))
        assertEquals("maya", c.offer(m("maya")))
        assertTrue(c.done); assertFalse(c.failed)
    }

    @Test fun missThenTwoMatchesDecidesOnThirdTry() {
        val c = ConsecutiveMatcher()
        c.offer(m(null)); c.offer(m("maya"))
        assertFalse(c.done)
        assertEquals("maya", c.offer(m("maya")))
        assertEquals(3, c.tries)
    }

    @Test fun alternatingPeopleNeverDecide() {
        val c = ConsecutiveMatcher()
        c.offer(m("maya")); c.offer(m("sam")); c.offer(m("maya"))
        assertTrue(c.failed); assertNull(c.decided)
    }

    @Test fun matchMissFailsEarly() {
        // After match, miss there's one try left but two consecutive are needed: give up now.
        val c = ConsecutiveMatcher()
        c.offer(m("maya")); c.offer(m(null))
        assertTrue(c.failed)
        assertEquals(2, c.tries)
    }

    @Test fun neverMoreThanThreeTries() {
        val c = ConsecutiveMatcher()
        c.offer(m(null)); c.offer(m("a")); c.offer(m(null))
        assertTrue(c.failed)
        c.offer(m("a")); c.offer(m("a"))
        assertEquals(3, c.tries)
        assertNull(c.decided)
    }

    @Test fun resetStartsOver() {
        val c = ConsecutiveMatcher()
        c.offer(m("a")); c.offer(m("a"))
        c.reset()
        assertFalse(c.done); assertEquals(0, c.tries)
    }
}

class FaceImageTest {
    /** A synthetic "face": smooth shading plus a few dark blobs for eyes and mouth. */
    private fun face(seed: Int, w: Int = 112, h: Int = 112): GreyImage {
        val r = Random(seed)
        val ex = 0.3f + r.nextFloat() * 0.08f; val ey = 0.38f + r.nextFloat() * 0.06f; val my = 0.72f + r.nextFloat() * 0.06f
        val g = GreyImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x / w.toFloat(); val v = y / h.toFloat()
            var lum = 150f + 40f * sin(u * 3.1f + seed) + 20f * sin(v * 5f)
            fun blob(cx: Float, cy: Float, rr: Float, d: Float) { val dd = (u - cx) * (u - cx) + (v - cy) * (v - cy); if (dd < rr * rr) lum -= d }
            blob(ex, ey, 0.07f, 90f); blob(1f - ex, ey, 0.07f, 90f); blob(0.5f, my, 0.1f, 60f)
            g.px[y * w + x] = (lum + r.nextInt(-3, 4)).toInt().coerceIn(0, 255)
        }
        return g
    }

    @Test fun lbpEmbeddingIsUnitLengthAndDeterministic() {
        val e = LbpEmbedder()
        val a = e.embed(face(1)); val b = e.embed(face(1))
        assertEquals(e.dim, a.size)
        assertEquals(1f, a.fold(0f) { s, x -> s + x * x }, 1e-3f)
        assertEquals(1f, cosine(a, b), 1e-6f)
    }

    @Test fun lbpIsInvariantToBrightness() {
        val e = LbpEmbedder()
        val f = face(2)
        val brighter = GreyImage(f.w, f.h, IntArray(f.px.size) { (f.px[it] + 30).coerceAtMost(255) })
        assertTrue(cosine(e.embed(f), e.embed(brighter)) > 0.97f)
    }

    @Test fun sameFaceIsCloserThanAnotherFace() {
        val e = LbpEmbedder()
        val a1 = e.embed(face(3)); val a2 = e.embed(face(3))
        val b = e.embed(face(40))
        assertTrue(cosine(a1, a2) > cosine(a1, b))
    }

    @Test fun uniformPatternTableHas58PlusOne() {
        val bins = LbpEmbedder.UNIFORM.toSet()
        assertEquals(59, bins.size)
        assertEquals(0, LbpEmbedder.UNIFORM[0])
        assertEquals(58, LbpEmbedder.UNIFORM[0b01010101])
    }

    @Test fun qualityFilterStages() {
        val q = QualityFilter()
        assertEquals(QualityIssue.TOO_SMALL, q.check(FaceBox(160f, 100f, 10f, 0.9f), 320, 240))
        assertEquals(QualityIssue.LOW_CONFIDENCE, q.check(FaceBox(160f, 100f, 40f, 0.2f), 320, 240))
        assertEquals(QualityIssue.CLIPPED, q.check(FaceBox(20f, 100f, 40f, 0.9f), 320, 240))
        assertEquals(QualityIssue.NONE, q.check(FaceBox(160f, 100f, 40f, 0.9f), 320, 240))
        assertEquals(QualityIssue.NONE, q.check(face(5)))
        assertEquals(QualityIssue.TOO_DARK, q.check(GreyImage(112, 112, IntArray(112 * 112) { 10 })))
        assertEquals(QualityIssue.BLURRY, q.check(GreyImage(112, 112, IntArray(112 * 112) { 128 })))
        val lopsided = GreyImage(112, 112, IntArray(112 * 112) { i -> val x = i % 112; if (x < 56) 60 + (i * 7919 % 50) else 200 - (i * 104729 % 40) })
        assertEquals(QualityIssue.NOT_FRONTAL, q.check(lopsided))
    }

    @Test fun cropSamplesTheFaceSquare() {
        // 320×240 frame: bright square exactly where the crop should land, dark elsewhere.
        val w = 320; val h = 240
        val box = FaceBox(160f, 100f, 40f, 0.9f)
        val side = FaceGeometry.side(box); val cy = FaceGeometry.centerY(box)
        val bytes = ByteArray(w * h) { i ->
            val x = i % w; val y = i / w
            if (abs(x - box.cx) < side / 2 - 1 && abs(y - cy) < side / 2 - 1) 200.toByte() else 20.toByte()
        }
        val crop = cropFace(YPlane(ByteBuffer.wrap(bytes), w, h, w, 1, 0), box)
        assertEquals(112, crop.w)
        assertTrue(crop[56, 56] > 190)
        assertTrue(crop[5, 5] > 150)
        assertTrue(crop[106, 106] > 150)
    }
}
