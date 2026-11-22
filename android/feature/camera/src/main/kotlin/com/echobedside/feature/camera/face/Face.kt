package com.echobedside.feature.camera.face

import com.echobedside.feature.camera.capture.YPlane
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A detected face in frame pixels: eye midpoint, eye distance, detector confidence (0..1). */
data class FaceBox(val cx: Float, val cy: Float, val eyeDist: Float, val confidence: Float)

/** Stage 3 (NOTES §6.6): a tiny detector. Runs on the camera thread. */
interface FaceFinder {
    val name: String
    /** Faces in [frame], largest first. */
    fun find(frame: YPlane): List<FaceBox>
    fun close() {}
}

/** Grey image the size of a face crop, row-major, 0..255. */
class GreyImage(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    operator fun get(x: Int, y: Int) = px[y * w + x]
}

/** Square crop geometry: the face is about 2.4 eye distances wide, centred a bit below the eyes. */
object FaceGeometry {
    const val SIDE_PER_EYE = 2.4f
    const val CENTER_DROP = 0.3f
    fun side(b: FaceBox) = b.eyeDist * SIDE_PER_EYE
    fun centerY(b: FaceBox) = b.cy + b.eyeDist * CENTER_DROP

    /** True when the whole crop square lies inside a [w]×[h] frame. */
    fun inside(b: FaceBox, w: Int, h: Int): Boolean {
        val s = side(b) / 2f
        val cy = centerY(b)
        return b.cx - s >= 0 && b.cx + s <= w && cy - s >= 0 && cy + s <= h
    }
}

/** Crops the face square from the Y plane into an [n]×[n] grey image (bilinear). */
fun cropFace(frame: YPlane, b: FaceBox, n: Int = 112, out: GreyImage = GreyImage(n, n)): GreyImage {
    val side = FaceGeometry.side(b)
    val x0 = b.cx - side / 2f
    val y0 = FaceGeometry.centerY(b) - side / 2f
    val step = side / n
    val buf = frame.buffer
    val rs = frame.rowStride
    val ps = frame.pixelStride
    val maxX = frame.width - 1
    val maxY = frame.height - 1
    fun px(x: Int, y: Int) = buf.get(y.coerceIn(0, maxY) * rs + x.coerceIn(0, maxX) * ps).toInt() and 0xFF
    for (j in 0 until n) {
        val sy = y0 + (j + 0.5f) * step - 0.5f
        val iy = floor(sy).toInt()
        val fy = sy - iy
        for (i in 0 until n) {
            val sx = x0 + (i + 0.5f) * step - 0.5f
            val ix = floor(sx).toInt()
            val fx = sx - ix
            val a = px(ix, iy); val bb = px(ix + 1, iy); val c = px(ix, iy + 1); val d = px(ix + 1, iy + 1)
            val top = a + (bb - a) * fx
            val bot = c + (d - c) * fx
            out.px[j * n + i] = (top + (bot - top) * fy).roundToInt().coerceIn(0, 255)
        }
    }
    return out
}

/** Why a face was rejected by the quality filter (stage 4). */
enum class QualityIssue { NONE, TOO_SMALL, LOW_CONFIDENCE, CLIPPED, TOO_DARK, TOO_BRIGHT, BLURRY, NOT_FRONTAL }

/**
 * Stage 4: only good faces reach recognition. "Most frames stop here."
 * Thresholds are for a 320×240 detection frame; see docs/status/camera.md for how they were set.
 */
class QualityFilter(
    var minEyeDist: Float = 22f,
    var minConfidence: Float = 0.45f,
    var minMean: Int = 40,
    var maxMean: Int = 225,
    /** Variance of the Laplacian on the 112×112 crop; lower = blurrier. */
    var minSharpness: Float = 40f,
    /** |left − right| mean luma over their sum; a turned head lights / fills the halves unevenly. */
    var maxAsymmetry: Float = 0.22f,
) {
    fun check(box: FaceBox, frameW: Int, frameH: Int): QualityIssue = when {
        box.eyeDist < minEyeDist -> QualityIssue.TOO_SMALL
        box.confidence < minConfidence -> QualityIssue.LOW_CONFIDENCE
        !FaceGeometry.inside(box, frameW, frameH) -> QualityIssue.CLIPPED
        else -> QualityIssue.NONE
    }

    fun check(crop: GreyImage): QualityIssue {
        val m = mean(crop)
        if (m < minMean) return QualityIssue.TOO_DARK
        if (m > maxMean) return QualityIssue.TOO_BRIGHT
        if (sharpness(crop) < minSharpness) return QualityIssue.BLURRY
        if (asymmetry(crop) > maxAsymmetry) return QualityIssue.NOT_FRONTAL
        return QualityIssue.NONE
    }

    companion object {
        fun mean(g: GreyImage): Int { var s = 0L; for (v in g.px) s += v; return (s / g.px.size).toInt() }

        /** Variance of the 4-neighbour Laplacian. */
        fun sharpness(g: GreyImage): Float {
            var sum = 0.0; var sq = 0.0; var n = 0
            for (y in 1 until g.h - 1) for (x in 1 until g.w - 1) {
                val l = (g[x - 1, y] + g[x + 1, y] + g[x, y - 1] + g[x, y + 1] - 4 * g[x, y]).toDouble()
                sum += l; sq += l * l; n++
            }
            if (n == 0) return 0f
            val mu = sum / n
            return (sq / n - mu * mu).toFloat()
        }

        /** Left/right half brightness imbalance of the central face region, 0..1. */
        fun asymmetry(g: GreyImage): Float {
            var l = 0L; var r = 0L
            val y0 = g.h / 4; val y1 = g.h * 3 / 4
            val half = g.w / 2
            for (y in y0 until y1) {
                for (x in g.w / 8 until half) l += g[x, y]
                for (x in half until g.w - g.w / 8) r += g[x, y]
            }
            val s = l + r
            return if (s == 0L) 0f else abs(l - r).toFloat() / s
        }
    }
}

/** Keeps a FaceBox's numbers sane (the legacy detector sometimes reports NaN confidence). */
internal fun FaceBox.sane(w: Int, h: Int): FaceBox? {
    if (!cx.isFinite() || !cy.isFinite() || !eyeDist.isFinite() || eyeDist <= 0f) return null
    return copy(cx = cx.coerceIn(0f, w.toFloat()), cy = cy.coerceIn(0f, h.toFloat()),
        eyeDist = min(eyeDist, max(w, h).toFloat()), confidence = if (confidence.isFinite()) confidence else 0f)
}
