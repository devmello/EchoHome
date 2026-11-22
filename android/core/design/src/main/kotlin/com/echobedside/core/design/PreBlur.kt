package com.echobedside.core.design

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A pre-blurred copy of a screen's background, in screen px (NOTES §5.2: Android 11 has no
 * RenderEffect, so glass samples this instead of blurring live).
 *
 * @param bitmap the blurred image, usually 1/[downscale] of the screen size
 * @param screenLeft/screenTop where the backdrop's top-left sits in window coordinates
 * @param screenWidth/screenHeight the size it covers in window px (the bitmap is scaled up to it)
 */
class Backdrop(
    val bitmap: Bitmap,
    val screenLeft: Float = 0f,
    val screenTop: Float = 0f,
    val screenWidth: Float,
    val screenHeight: Float,
)

object PreBlur {
    /**
     * Blurs [src] once, applying the glass recipe's saturate/brightness: a Gaussian of [blurPx]
     * (CSS blur radius = sigma) approximated by three box blurs at 1/[downscale] size.
     * ~5 ms for a 960×480 source on the Echo. Run it off the main thread for big sources.
     */
    fun blur(
        src: Bitmap,
        blurPx: Float = GlassTokens.BLUR_PX,
        downscale: Int = 4,
        saturate: Float = GlassTokens.SATURATE,
        brightness: Float = GlassTokens.BRIGHTNESS,
    ): Bitmap {
        val w = max(1, src.width / downscale)
        val h = max(1, src.height / downscale)
        val small = Bitmap.createScaledBitmap(src, w, h, true).let {
            if (it.config == Bitmap.Config.ARGB_8888 && it !== src) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        gaussian(px, w, h, blurPx / downscale)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val blurred = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(saturate) }
        cm.postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
        Canvas(out).drawBitmap(blurred, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        blurred.recycle()
        if (small !== src) small.recycle()
        return out
    }

    fun backdrop(src: Bitmap, screenWidth: Float, screenHeight: Float, blurPx: Float = GlassTokens.BLUR_PX, downscale: Int = 4) =
        Backdrop(blur(src, blurPx, downscale), 0f, 0f, screenWidth, screenHeight)

    /** In-place Gaussian approximation (3 box passes) on ARGB pixels. */
    fun gaussian(px: IntArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.5f) return
        val tmp = IntArray(px.size)
        for (size in boxesForGauss(sigma, 3)) {
            val r = (size - 1) / 2
            boxH(px, tmp, w, h, r)
            boxV(tmp, px, w, h, r)
        }
    }

    internal fun boxesForGauss(sigma: Float, n: Int): IntArray {
        val wIdeal = sqrt(12.0 * sigma * sigma / n + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) { val c = src[row + k.coerceIn(0, w - 1)]; a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            for (x in 0 until w) {
                dst[row + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
                val cin = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val cout = src[row + (x - r).coerceAtLeast(0)]
                a += (cin ushr 24) - (cout ushr 24)
                rr += ((cin shr 16) and 255) - ((cout shr 16) and 255)
                g += ((cin shr 8) and 255) - ((cout shr 8) and 255)
                b += (cin and 255) - (cout and 255)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val d = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (k in -r..r) { val c = src[k.coerceIn(0, h - 1) * w + x]; a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
                val cin = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val cout = src[(y - r).coerceAtLeast(0) * w + x]
                a += (cin ushr 24) - (cout ushr 24)
                rr += ((cin shr 16) and 255) - ((cout shr 16) and 255)
                g += ((cin shr 8) and 255) - ((cout shr 8) and 255)
                b += (cin and 255) - (cout and 255)
            }
        }
    }
}
