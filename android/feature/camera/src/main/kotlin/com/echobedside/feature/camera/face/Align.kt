package com.echobedside.feature.camera.face

import com.echobedside.feature.camera.capture.YPlane
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.sqrt

/** An aligned face crop: 0xRRGGBB pixels for the network, and its luma for quality checks and LBP. */
class FaceImage(val size: Int = 112) {
    val rgb = IntArray(size * size)
    val grey = GreyImage(size, size)
}

/** x' = a·x − b·y + tx, y' = b·x + a·y + ty (rotation + uniform scale + shift). */
data class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {
    fun mapX(x: Float, y: Float) = a * x - b * y + tx
    fun mapY(x: Float, y: Float) = b * x + a * y + ty
    val scale: Float get() = sqrt(a * a + b * b)

    companion object {
        /** Least-squares similarity taking [from] to [to] (x,y pairs; ≥2 points). Closed form (Umeyama, 2D). */
        fun fit(from: FloatArray, to: FloatArray): Similarity {
            require(from.size == to.size && from.size >= 4 && from.size % 2 == 0)
            val n = from.size / 2
            var mx = 0f; var my = 0f; var mu = 0f; var mv = 0f
            for (i in 0 until n) { mx += from[2 * i]; my += from[2 * i + 1]; mu += to[2 * i]; mv += to[2 * i + 1] }
            mx /= n; my /= n; mu /= n; mv /= n
            var num1 = 0f; var num2 = 0f; var den = 0f
            for (i in 0 until n) {
                val x = from[2 * i] - mx; val y = from[2 * i + 1] - my
                val u = to[2 * i] - mu; val v = to[2 * i + 1] - mv
                num1 += x * u + y * v
                num2 += x * v - y * u
                den += x * x + y * y
            }
            require(den > 1e-6f) { "degenerate points" }
            val a = num1 / den; val b = num2 / den
            return Similarity(a, b, mu - a * mx + b * my, mv - b * mx - a * my)
        }
    }
}

/**
 * ArcFace / InsightFace 112×112 alignment template (arcface_torch `norm_crop`): image-left eye,
 * image-right eye, nose tip, mouth centre (the mean of the two mouth corners).
 */
object ArcFaceTemplate {
    val POINTS = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 56.1396f, 92.2848f)
    val EYE_MID_X = (38.2946f + 73.5318f) / 2f
    val EYE_MID_Y = (51.6963f + 51.5014f) / 2f
    const val EYE_DIST = 73.5318f - 38.2946f

    /**
     * Template → frame transform for [b]: from BlazeFace's eyes + nose + mouth when it has them,
     * else from the eye midpoint and distance alone (no roll; the legacy detector).
     */
    fun toFrame(b: FaceBox, size: Int = 112): Similarity {
        val k = size / 112f
        val lm = b.landmarks
        if (lm != null) {
            // Eyes sorted by x so the template's image-left eye meets the image-left eye.
            val e0 = if (b.lx(Landmark.RIGHT_EYE) <= b.lx(Landmark.LEFT_EYE)) Landmark.RIGHT_EYE else Landmark.LEFT_EYE
            val e1 = if (e0 == Landmark.RIGHT_EYE) Landmark.LEFT_EYE else Landmark.RIGHT_EYE
            val to = floatArrayOf(b.lx(e0), b.ly(e0), b.lx(e1), b.ly(e1), b.lx(Landmark.NOSE), b.ly(Landmark.NOSE), b.lx(Landmark.MOUTH), b.ly(Landmark.MOUTH))
            return Similarity.fit(FloatArray(8) { POINTS[it] * k }, to)
        }
        val s = b.eyeDist / (EYE_DIST * k)
        return Similarity(s, 0f, b.cx - s * EYE_MID_X * k, b.cy - s * EYE_MID_Y * k)
    }
}

/**
 * Samples a frame at (sx, sy) in pixel coordinates (centres at +0.5 already removed): bilinear
 * luma, nearest NV21 chroma, edge-clamped. [get] returns luma shl 24 or 0xRRGGBB (grey when the
 * frame has no chroma). Reads the backing array directly when there is one (camera frames).
 */
internal class Sampler(frame: YPlane) {
    private val buf = frame.buffer
    private val arr: ByteArray? = if (buf.hasArray()) buf.array() else null
    private val off = if (arr != null) buf.arrayOffset() else 0
    private val rs = frame.rowStride
    private val ps = frame.pixelStride
    private val maxX = frame.width - 1
    private val maxY = frame.height - 1
    private val uv = frame.uvOffset

    private fun px(i: Int): Int = if (arr != null) arr[off + i].toInt() and 0xFF else buf.get(i).toInt() and 0xFF

    fun get(sx: Float, sy: Float): Int {
        val ix = floor(sx).toInt(); val iy = floor(sy).toInt()
        val fx = sx - ix; val fy = sy - iy
        val x0 = ix.coerceIn(0, maxX) * ps; val x1 = (ix + 1).coerceIn(0, maxX) * ps
        val r0 = iy.coerceIn(0, maxY) * rs; val r1 = (iy + 1).coerceIn(0, maxY) * rs
        val a = px(r0 + x0); val b = px(r0 + x1)
        val c = px(r1 + x0); val d = px(r1 + x1)
        val top = a + (b - a) * fx
        val luma = (top + (c + (d - c) * fx - top) * fy + 0.5f).toInt().coerceIn(0, 255)
        if (uv < 0) return (luma shl 24) or (luma shl 16) or (luma shl 8) or luma
        val cx = (sx + 0.5f).toInt().coerceIn(0, maxX)
        val cy = (sy + 0.5f).toInt().coerceIn(0, maxY)
        val k = uv + (cy shr 1) * rs + (cx and 1.inv())
        val v = px(k) - 128
        val u = px(k + 1) - 128
        val r = (luma + 1.402f * v).toInt().coerceIn(0, 255)
        val g = (luma - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
        val bl = (luma + 1.772f * u).toInt().coerceIn(0, 255)
        return (luma shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

/** Warps the face in [frame] into [out], aligned to [ArcFaceTemplate] (the embedder's input). */
fun alignFace(frame: YPlane, b: FaceBox, out: FaceImage): FaceImage {
    val n = out.size
    val m = ArcFaceTemplate.toFrame(b, n)
    val sm = Sampler(frame)
    for (j in 0 until n) {
        val py = j + 0.5f
        for (i in 0 until n) {
            val px = i + 0.5f
            val s = sm.get(m.mapX(px, py) - 0.5f, m.mapY(px, py) - 0.5f)
            out.grey.px[j * n + i] = s ushr 24
            out.rgb[j * n + i] = s and 0xFFFFFF
        }
    }
    return out
}

/** Frame px → model px: m = f · [scale] + pad. */
class Letterbox(val scale: Float, val padX: Float, val padY: Float) {
    fun toFrameX(mx: Float) = (mx - padX) / scale
    fun toFrameY(my: Float) = (my - padY) / scale

    companion object {
        /** Fits a [w]×[h] frame into an [n]×[n] square, centred, aspect kept. */
        fun fit(w: Int, h: Int, n: Int): Letterbox {
            val s = n.toFloat() / maxOf(w, h)
            return Letterbox(s, (n - w * s) / 2f, (n - h * s) / 2f)
        }
    }
}

/**
 * Writes [frame] letterboxed into an [n]×[n] RGB float tensor in [-1, 1] (pad = 0) and returns the
 * mapping.
 */
fun letterbox(frame: YPlane, n: Int, out: FloatBuffer): Letterbox {
    val lb = Letterbox.fit(frame.width, frame.height, n)
    out.clear()
    val inv = 1f / lb.scale
    val sm = Sampler(frame)
    for (j in 0 until n) {
        val fy = (j + 0.5f - lb.padY) * inv
        for (i in 0 until n) {
            val fx = (i + 0.5f - lb.padX) * inv
            if (fx < 0f || fy < 0f || fx >= frame.width || fy >= frame.height) { out.put(0f); out.put(0f); out.put(0f); continue }
            val s = sm.get(fx - 0.5f, fy - 0.5f)
            out.put(((s shr 16) and 0xFF) / 127.5f - 1f)
            out.put(((s shr 8) and 0xFF) / 127.5f - 1f)
            out.put((s and 0xFF) / 127.5f - 1f)
        }
    }
    out.rewind()
    return lb
}
