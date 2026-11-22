package app.vesper.feature.camera.presence

import app.vesper.feature.camera.capture.YPlane
import kotlin.math.abs

/** One presence sample. */
data class MotionResult(
    /** Fraction of the 80×60 grid that changed by more than the pixel threshold, 0..1. */
    val changed: Float,
    /** Mean luma of the downscaled frame, 0..255. */
    val meanLuma: Int,
    /** [changed] is above the motion threshold (and the frame isn't too dark to judge). */
    val motion: Boolean,
    /** Too dark to see anything: closed shutter or an unlit room. */
    val dark: Boolean,
)

/**
 * Frame differencing on the Y plane, downscaled to [w]×[h] (NOTES §6.6 step 2: "almost free").
 *
 * Each output pixel is the mean of a 2×2 sample of its source block (cheap box filter that
 * also averages sensor noise). Global brightness shifts (auto-exposure, a lamp) are removed by
 * comparing mean-subtracted frames, so only local change counts as motion.
 */
class MotionDetector(
    val w: Int = 80,
    val h: Int = 60,
    /** Per-pixel change (0..255, after removing the global shift) that counts as "changed". */
    private val pixelThreshold: Int = 16,
    /** Fraction of changed pixels that counts as motion (1 % of 80×60 = 48 px). */
    private val motionFraction: Float = 0.01f,
    /** Below this mean luma the frame is "dark" and motion isn't judged. */
    private val darkLuma: Int = 10,
) {
    private var cur = IntArray(w * h)
    private var prev = IntArray(w * h)
    private var prevMean = 0
    private var hasPrev = false

    /** Forget the previous frame (after a pause, the first frame back is never "motion"). */
    fun reset() { hasPrev = false }

    fun process(frame: YPlane): MotionResult {
        downscale(frame, cur, w, h)
        return compare()
    }

    /** For tests and callers that already have a [w]×[h] grey grid. */
    fun processGrey(grey: IntArray): MotionResult {
        require(grey.size == w * h)
        grey.copyInto(cur)
        return compare()
    }

    private fun compare(): MotionResult {
        var sum = 0L
        for (v in cur) sum += v
        val mean = (sum / cur.size).toInt()
        val dark = mean < darkLuma
        var changed = 0
        if (hasPrev) {
            val shift = mean - prevMean
            for (i in cur.indices) if (abs(cur[i] - prev[i] - shift) > pixelThreshold) changed++
        }
        val frac = if (hasPrev) changed.toFloat() / cur.size else 0f
        val motion = hasPrev && !dark && frac >= motionFraction
        // swap buffers
        val t = prev; prev = cur; cur = t
        prevMean = mean
        hasPrev = true
        return MotionResult(frac, mean, motion, dark)
    }

    companion object {
        /** Downscales [src]'s Y plane into [out] ([ow]×[oh]): mean of 4 samples per output pixel. */
        fun downscale(src: YPlane, out: IntArray, ow: Int, oh: Int) {
            val buf = src.buffer
            val rs = src.rowStride
            val ps = src.pixelStride
            val sw = src.width
            val sh = src.height
            if (buf.hasArray()) {
                // Camera1's NV21 buffer: index the array directly (no per-pixel ByteBuffer calls).
                val a = buf.array()
                val off = buf.arrayOffset()
                for (oy in 0 until oh) {
                    val r0 = off + (oy * sh / oh) * rs
                    val r1 = off + ((((oy * 2 + 1) * sh) / (oh * 2)).coerceAtMost(sh - 1)) * rs
                    for (ox in 0 until ow) {
                        val x0 = (ox * sw / ow) * ps
                        val x1 = ((((ox * 2 + 1) * sw) / (ow * 2)).coerceAtMost(sw - 1)) * ps
                        out[oy * ow + ox] = ((a[r0 + x0].toInt() and 0xFF) + (a[r0 + x1].toInt() and 0xFF) +
                            (a[r1 + x0].toInt() and 0xFF) + (a[r1 + x1].toInt() and 0xFF)) shr 2
                    }
                }
                return
            }
            for (oy in 0 until oh) {
                val y0 = oy * sh / oh
                val y1 = (((oy * 2 + 1) * sh) / (oh * 2)).coerceAtMost(sh - 1)
                val r0 = y0 * rs
                val r1 = y1 * rs
                for (ox in 0 until ow) {
                    val x0 = ox * sw / ow
                    val x1 = (((ox * 2 + 1) * sw) / (ow * 2)).coerceAtMost(sw - 1)
                    val a = buf.get(r0 + x0 * ps).toInt() and 0xFF
                    val b = buf.get(r0 + x1 * ps).toInt() and 0xFF
                    val c = buf.get(r1 + x0 * ps).toInt() and 0xFF
                    val d = buf.get(r1 + x1 * ps).toInt() and 0xFF
                    out[oy * ow + ox] = (a + b + c + d) shr 2
                }
            }
        }
    }
}
