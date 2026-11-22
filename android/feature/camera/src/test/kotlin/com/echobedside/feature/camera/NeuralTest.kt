package com.echobedside.feature.camera

import com.echobedside.core.model.SettingType
import com.echobedside.feature.camera.face.ArcFaceTemplate
import com.echobedside.feature.camera.face.BlazeFace
import com.echobedside.feature.camera.face.FaceBox
import com.echobedside.feature.camera.face.FaceEmbedder
import com.echobedside.feature.camera.face.FaceImage
import com.echobedside.feature.camera.face.FaceTemplates
import com.echobedside.feature.camera.face.FallbackEmbedder
import com.echobedside.feature.camera.face.LbpEmbedder
import com.echobedside.feature.camera.face.Landmark
import com.echobedside.feature.camera.face.Letterbox
import com.echobedside.feature.camera.face.MatchPolicy
import com.echobedside.feature.camera.face.Similarity
import com.echobedside.feature.camera.face.SyntheticFace
import com.echobedside.feature.camera.face.alignFace
import com.echobedside.feature.camera.face.letterbox
import com.echobedside.feature.camera.profiles.FaceProfileManager
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignTest {
    @Test fun similarityFitRecoversAKnownTransform() {
        val ang = 0.2f; val s = 1.7f
        val truth = Similarity(s * cos(ang), s * sin(ang), 31f, -12f)
        val from = floatArrayOf(10f, 20f, 50f, 22f, 30f, 45f, 31f, 70f)
        val to = FloatArray(8) { i -> if (i % 2 == 0) truth.mapX(from[i], from[i + 1]) else truth.mapY(from[i - 1], from[i]) }
        val fit = Similarity.fit(from, to)
        assertEquals(truth.a, fit.a, 1e-4f); assertEquals(truth.b, fit.b, 1e-4f)
        assertEquals(truth.tx, fit.tx, 1e-3f); assertEquals(truth.ty, fit.ty, 1e-3f)
        assertEquals(s, fit.scale, 1e-4f)
    }

    @Test fun landmarksMapTheTemplateOntoTheFace() {
        // A face exactly the template, scaled ×2, rotated 15°, shifted; BlazeFace lists the
        // subject's right eye (image left) first, but the order mustn't matter.
        val ang = 0.26f; val s = 2f
        val m = Similarity(s * cos(ang), s * sin(ang), 100f, 40f)
        val t = ArcFaceTemplate.POINTS
        val lm = FloatArray(12)
        for (k in 0 until 4) { lm[2 * k] = m.mapX(t[2 * k], t[2 * k + 1]); lm[2 * k + 1] = m.mapY(t[2 * k], t[2 * k + 1]) }
        for (order in listOf(lm, lm.copyOf().also { swapEyes(it) })) {
            val box = BlazeFace.faceBox(order, 0.9f)
            val fit = ArcFaceTemplate.toFrame(box)
            assertEquals(m.a, fit.a, 1e-3f); assertEquals(m.b, fit.b, 1e-3f)
            assertEquals(m.tx, fit.tx, 0.05f); assertEquals(m.ty, fit.ty, 0.05f)
        }
    }

    private fun swapEyes(lm: FloatArray) {
        for (k in 0..1) { val v = lm[k]; lm[k] = lm[2 + k]; lm[2 + k] = v }
    }

    @Test fun withoutLandmarksTheEyesLandOnTheTemplateEyes() {
        val box = FaceBox(160f, 100f, 40f, 0.9f)
        val m = ArcFaceTemplate.toFrame(box)
        assertEquals(160f, m.mapX(ArcFaceTemplate.EYE_MID_X, ArcFaceTemplate.EYE_MID_Y), 1e-3f)
        assertEquals(100f, m.mapY(ArcFaceTemplate.EYE_MID_X, ArcFaceTemplate.EYE_MID_Y), 1e-3f)
        assertEquals(40f / ArcFaceTemplate.EYE_DIST, m.scale, 1e-5f)
    }

    @Test fun alignedCropPutsTheDrawnEyesOnTheTemplate() {
        val cx = 150f; val cy = 110f; val r = 40f
        val frame = SyntheticFace.frame(320, 240, cx, cy, r)
        val e = SyntheticFace.eyes(cx, cy, r)
        val box = FaceBox((e[0] + e[2]) / 2, (e[1] + e[3]) / 2, e[2] - e[0], 1f)
        val face = alignFace(frame, box, FaceImage())
        val t = ArcFaceTemplate.POINTS
        val leftEye = face.grey[t[0].toInt(), t[1].toInt()]
        val rightEye = face.grey[t[2].toInt(), t[3].toInt()]
        val between = face.grey[56, 52]
        assertTrue("eyes dark: $leftEye $rightEye", leftEye < 60 && rightEye < 60)
        assertTrue("skin between the eyes: $between", between > 150)
        // Colour survives: the cheek is skin-coloured (red > blue).
        val cheek = face.rgb[80 * 112 + 30]
        assertTrue(((cheek shr 16) and 0xFF) > (cheek and 0xFF) + 40)
    }

    @Test fun nv21ToRgb() {
        val r = 40f
        val frame = SyntheticFace.frame(320, 240, 160f, 120f, r)
        val skin = frame.rgb((160 - 0.6f * r).toInt(), (120 + 0.3f * r).toInt())
        assertEquals(0xE0, (skin shr 16) and 0xFF, 6f)
        assertEquals(0xAC, (skin shr 8) and 0xFF, 6f)
        assertEquals(0x8C, skin and 0xFF, 6f)
    }

    private fun assertEquals(expected: Int, actual: Int, tol: Float) = assertTrue("$expected vs $actual", abs(expected - actual) <= tol)

    @Test fun letterboxKeepsAspectAndPads() {
        val lb = Letterbox.fit(320, 240, 128)
        assertEquals(0.4f, lb.scale, 1e-6f); assertEquals(0f, lb.padX, 1e-6f); assertEquals(16f, lb.padY, 1e-6f)
        assertEquals(160f, lb.toFrameX(64f), 1e-4f); assertEquals(120f, lb.toFrameY(64f), 1e-4f)
        val buf = FloatBuffer.allocate(128 * 128 * 3)
        letterbox(SyntheticFace.frame(320, 240, 160f, 120f, 40f), 128, buf)
        assertEquals(0f, buf.get(0), 0f) // pad row
        val bg = buf.get((20 * 128 + 2) * 3 + 2) // background blue 140 → 140/127.5 − 1
        assertEquals(140 / 127.5f - 1f, bg, 0.06f)
    }
}

class BlazeFaceDecodeTest {
    private val lb = Letterbox.fit(320, 240, 128)

    @Test fun anchorsMatchTheShortRangeLayout() {
        val a = BlazeFace.ANCHORS
        assertEquals(896 * 2, a.size)
        assertEquals(0.5f / 16, a[0], 1e-6f); assertEquals(0.5f / 16, a[1], 1e-6f)
        assertEquals(a[0], a[2], 0f) // two anchors per 16×16 cell
        assertEquals(0.5f / 8, a[2 * 512], 1e-6f) // then the 8×8 grid, six per cell
        assertEquals(1.5f / 8, a[2 * (512 + 6)], 1e-6f)
        assertEquals(7.5f / 8, a[2 * 895], 1e-6f); assertEquals(7.5f / 8, a[2 * 895 + 1], 1e-6f)
    }

    private fun tensors(): Pair<FloatArray, FloatArray> = FloatArray(896 * 16) to FloatArray(896) { -10f }

    /** Puts a face at [anchor]: box [w] input px, eyes ±[eye] px from the anchor centre. */
    private fun put(reg: FloatArray, logits: FloatArray, anchor: Int, logit: Float, w: Float, eye: Float, dx: Float = 0f) {
        logits[anchor] = logit
        val o = anchor * 16
        reg[o] = dx; reg[o + 1] = 0f; reg[o + 2] = w; reg[o + 3] = w
        reg[o + 4 + 2 * Landmark.RIGHT_EYE] = -eye + dx; reg[o + 5 + 2 * Landmark.RIGHT_EYE] = -5f
        reg[o + 4 + 2 * Landmark.LEFT_EYE] = eye + dx; reg[o + 5 + 2 * Landmark.LEFT_EYE] = -5f
        reg[o + 4 + 2 * Landmark.NOSE] = dx; reg[o + 5 + 2 * Landmark.NOSE] = 3f
        reg[o + 4 + 2 * Landmark.MOUTH] = dx; reg[o + 5 + 2 * Landmark.MOUTH] = 10f
    }

    @Test fun nothingBelowTheScoreThreshold() {
        val (reg, logits) = tensors()
        put(reg, logits, 600, -0.1f, 30f, 6f) // sigmoid < 0.5
        assertTrue(BlazeFace.decode(reg, logits, lb).isEmpty())
    }

    @Test fun overlappingDetectionsMergeWeighted() {
        val (reg, logits) = tensors()
        // Two anchors of the same 8×8 cell (index 512 + 6·k), boxes 2 px apart, scores 0.95 / 0.73.
        val a = 512 + 6 * 27
        put(reg, logits, a, 3f, 30f, 6f, dx = 0f)
        put(reg, logits, a + 1, 1f, 30f, 6f, dx = 2f)
        val faces = BlazeFace.decode(reg, logits, lb)
        assertEquals(1, faces.size)
        val f = faces[0]
        val s1 = 1f / (1f + kotlin.math.exp(-3f)); val s2 = 1f / (1f + kotlin.math.exp(-1f))
        val ax = BlazeFace.ANCHORS[2 * a] * 128; val ay = BlazeFace.ANCHORS[2 * a + 1] * 128
        val mx = ax + 2f * s2 / (s1 + s2)
        assertEquals(s1, f.confidence, 1e-5f)
        assertEquals(lb.toFrameX(mx), f.cx, 1e-3f)
        assertEquals(lb.toFrameY(ay - 5f), f.cy, 1e-3f)
        assertEquals(12f / lb.scale, f.eyeDist, 1e-3f)
        assertNotNull(f.landmarks)
        assertTrue(f.lx(Landmark.RIGHT_EYE) < f.lx(Landmark.LEFT_EYE))
        assertEquals(lb.toFrameY(ay + 10f), f.ly(Landmark.MOUTH), 1e-3f)
    }

    @Test fun separateFacesStaySeparateLargestFirst() {
        val (reg, logits) = tensors()
        put(reg, logits, 2 * (16 * 4 + 3), 2f, 20f, 4f)      // small face top-left
        put(reg, logits, 512 + 6 * (8 * 5 + 6), 2.5f, 40f, 9f) // big face bottom-right
        val faces = BlazeFace.decode(reg, logits, lb)
        assertEquals(2, faces.size)
        assertTrue(faces[0].eyeDist > faces[1].eyeDist)
        assertTrue(hypot(faces[0].cx - faces[1].cx, faces[0].cy - faces[1].cy) > 100f)
    }
}

class EmbedderPolicyTest {
    private class Broken : FaceEmbedder {
        override val id = "broken"; override val dim = 512; override val policy = MatchPolicy.NEURAL
        var released = false
        override fun embed(face: FaceImage): FloatArray = throw UnsatisfiedLinkError("no libtensorflowlite_jni.so")
        override fun release() { released = true }
    }

    @Test fun fallsBackToLbpWhenTheModelFails() {
        val broken = Broken()
        var why: Throwable? = null
        val e = FallbackEmbedder(broken, LbpEmbedder()) { why = it }
        assertEquals("broken", e.id)
        val v = e.embed(FaceImage().also { f -> for (i in f.grey.px.indices) f.grey.px[i] = (i * 31) % 256 })
        assertTrue(e.failed); assertTrue(broken.released); assertNotNull(why)
        assertEquals(LbpEmbedder().id, e.id)
        assertEquals(LbpEmbedder().dim, v.size)
        assertEquals(MatchPolicy.LBP, e.policy)
    }

    @Test fun noModelMeansLbp() {
        val e = FallbackEmbedder(null, LbpEmbedder())
        assertTrue(e.failed); assertEquals(LbpEmbedder().id, e.id)
    }

    @Test fun neuralThresholdStaysWithinFloorAndCeiling() {
        val p = MatchPolicy.NEURAL
        fun pair(c: Float) = FaceTemplates("x", "n", listOf(floatArrayOf(1f, 0f), floatArrayOf(c, kotlin.math.sqrt(1 - c * c))))
        assertEquals(p.ceiling, pair(0.9f).threshold(p), 1e-5f)   // very alike shots: capped
        assertEquals(0.5f - p.slack, pair(0.5f).threshold(p), 1e-5f)
        assertEquals(p.floor, pair(0.3f).threshold(p), 1e-5f)     // poor shots: never below the floor
        assertEquals(p.floor, FaceTemplates("x", "n", listOf(floatArrayOf(1f))).threshold(p), 0f)
    }

    @Test fun lbpRuleUnchanged() {
        val t = FaceTemplates("x", "l", listOf(floatArrayOf(1f, 0f), floatArrayOf(0.8f, 0.6f)))
        assertEquals(0.79f, t.threshold(MatchPolicy.LBP), 1e-4f)
    }
}

class SettingsSectionTest {
    @Test fun cameraSectionDeclaresTheKeysTheCodeReads() {
        val s = CameraFeature.settingsSection()
        val keys = s.settings.associateBy { it.key }
        assertEquals(setOf("camera.stage.presence", CameraPipeline.KEY_DUTY, FaceProfileManager.KEY_AUTO, CameraPipeline.KEY_THRESHOLD, FaceProfileManager.KEY_TIMEOUT_MIN), keys.keys)
        assertTrue(keys.getValue(CameraPipeline.KEY_DUTY).advanced)
        assertTrue(keys.getValue(CameraPipeline.KEY_THRESHOLD).advanced)
        assertFalse(keys.getValue(FaceProfileManager.KEY_AUTO).advanced)
        assertEquals(MatchPolicy.NEURAL.floor, keys.getValue(CameraPipeline.KEY_THRESHOLD).default.toFloat(), 0f)
        assertEquals(SettingType.INT, keys.getValue(FaceProfileManager.KEY_TIMEOUT_MIN).type)
        assertEquals("10", keys.getValue(FaceProfileManager.KEY_TIMEOUT_MIN).default)
    }
}
