package app.vesper.feature.photos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import app.vesper.core.design.Night

/**
 * One full-screen photo. The crossfade and slow zoom are View properties only (alpha, scaleX/Y
 * around a pivot), so they never re-record this view's drawing: the RenderThread just
 * composites the same bitmap with a new transform each frame.
 */
internal class PhotoLayer(context: Context) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    var bitmap: Bitmap? = null
        set(v) { if (field !== v) { field = v; invalidate() } }

    /** Night mode: red monochrome at [dim] brightness; null = full colour. */
    fun setNight(night: Boolean, dim: Float) {
        val f: ColorFilter? = if (night) ColorMatrixColorFilter(nightMatrix(dim)) else null
        paint.colorFilter = f
        invalidate()
    }

    init {
        // Nothing in front of the bitmap: let alpha apply per draw, no offscreen layer.
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        if (b.width == width && b.height == height) canvas.drawBitmap(b, 0f, 0f, paint)
        else canvas.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), paint)
    }

    companion object {
        /** Luminance → Night red (#BE180E), scaled by [dim] (NOTES §3.3: ~0.42). */
        fun nightMatrix(dim: Float): ColorMatrix {
            val boost = 1.25f // keep mid-tones readable once everything is one dim red
            val r = ((Night.COLOR shr 16) and 255) / 255f * dim * boost
            val g = ((Night.COLOR shr 8) and 255) / 255f * dim * boost
            val b = (Night.COLOR and 255) / 255f * dim * boost
            fun row(k: Float) = floatArrayOf(k * 0.2126f, k * 0.7152f, k * 0.0722f, 0f, 0f)
            return ColorMatrix(row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, 1f, 0f))
        }
    }
}
