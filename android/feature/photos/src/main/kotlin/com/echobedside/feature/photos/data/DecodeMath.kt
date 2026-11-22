package com.echobedside.feature.photos.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a preview becomes one 960×480 frame (pure math, unit tested; PhotoDecoder applies it).
 *
 *  - Landscape photos fill the screen (CSS `object-fit: cover`), cropped a little more from the
 *    bottom than the top (faces and horizons sit high).
 *  - Portrait and square-ish photos ([fits]) are shown whole at full height, centred, over a
 *    blurred, darkened fill of themselves — the usual photo-frame treatment.
 *  - Decoding uses the largest power-of-two `inSampleSize` that still leaves enough pixels.
 */
object DecodeMath {
    /** Below this width/height ratio a photo is fitted instead of cropped. */
    const val FIT_BELOW_ASPECT = 1.2f
    /** Share of the vertical excess cropped from the top when filling. */
    const val CROP_BIAS_TOP = 0.4f

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun fits(srcW: Int, srcH: Int): Boolean = srcH > 0 && srcW.toFloat() / srcH < FIT_BELOW_ASPECT

    /** Largest power of two ≤ the downscale factor needed, so the decoded image still covers the target. */
    fun inSampleSize(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        val scale = if (fits(srcW, srcH)) dstH.toFloat() / srcH else max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        if (scale >= 1f) return 1
        var s = 1
        while (s * 2 <= 1f / scale) s *= 2
        return s
    }

    /** The part of a [w]×[h] image to scale into [dstW]×[dstH] when filling the screen. */
    fun coverSource(w: Int, h: Int, dstW: Int, dstH: Int, biasTop: Float = CROP_BIAS_TOP): Rect {
        val target = dstW.toFloat() / dstH
        return if (w.toFloat() / h > target) {
            val cw = min(w, (h * target).roundToInt())
            val l = (w - cw) / 2
            Rect(l, 0, l + cw, h)
        } else {
            val ch = min(h, (w / target).roundToInt())
            val t = ((h - ch) * biasTop).roundToInt()
            Rect(0, t, w, t + ch)
        }
    }

    /** Where a fitted (portrait) photo goes on the [dstW]×[dstH] frame: full height, centred. */
    fun fitDest(w: Int, h: Int, dstW: Int, dstH: Int): Rect {
        val dw = min(dstW, (w * dstH.toFloat() / h).roundToInt())
        val dh = if (dw == dstW) (h * dstW.toFloat() / w).roundToInt() else dstH
        val l = (dstW - dw) / 2
        val t = (dstH - dh) / 2
        return Rect(l, t, l + dw, t + dh)
    }
}
