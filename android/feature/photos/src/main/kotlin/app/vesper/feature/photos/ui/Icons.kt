package app.vesper.feature.photos.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** Glyph colour on glass over a bright part of the photo (Apple's label colour on light glass). */
internal val ON_LIGHT = 0xFF1C1C1E.toInt()

/**
 * Relative luminance (0–1) of the blurred backdrop under a window rect, from a few samples of the
 * small pre-blurred bitmap (cheap: ~24 getPixel calls, once per photo / show).
 */
internal fun backdropLuma(bd: app.vesper.core.design.Backdrop, l: Float, t: Float, r: Float, b: Float): Float {
    val bmp = bd.bitmap
    if (bmp.isRecycled || r <= l || b <= t) return 0f
    var sum = 0f
    var n = 0
    for (iy in 0 until 3) for (ix in 0 until 8) {
        val x = l + (r - l) * (ix + 0.5f) / 8f
        val y = t + (b - t) * (iy + 0.5f) / 3f
        val bx = ((x - bd.screenLeft) / bd.screenWidth * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val by = ((y - bd.screenTop) / bd.screenHeight * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val c = bmp.getPixel(bx, by)
        sum += 0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)
        n++
    }
    return sum / n / 255f
}

/** The design's SVG icons as Paths, drawn at a given size and centre. */
internal object Icons {
    /** Pause, viewBox 32: M7 5h6.4v22H7z M18.6 5H25v22h-6.4z */
    val pause: Path = Path().apply {
        addRect(7f, 5f, 13.4f, 27f, Path.Direction.CW)
        addRect(18.6f, 5f, 25f, 27f, Path.Direction.CW)
    }

    /**
     * Play, viewBox 32: a triangle (8,6.3)…(26.4,16)…(8,25.7) with 1.6-radius corners. The arcs
     * are drawn as quadratic corners through the triangle's vertices (same tangents).
     */
    val play: Path = Path().apply {
        moveTo(8f, 6.3f)
        quadTo(8f, 3.445f, 10.4f, 4.9f)
        lineTo(26.4f, 14.6f)
        quadTo(28.71f, 16f, 26.4f, 17.4f)
        lineTo(10.4f, 27.1f)
        quadTo(8f, 28.555f, 8f, 25.7f)
        close()
    }

    /** Chevron down, viewBox 24: m6 9 6 6 6-6 (stroke). */
    val chevronDown: Path = Path().apply { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }

    /** Chevron right, viewBox 24: m9 5 7 7-7 7 (stroke). */
    val chevronRight: Path = Path().apply { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }

    /** Check mark, viewBox 24 (stroke). */
    val check: Path = Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7.5f) }

    /**
     * Draws [path] (in a [viewBox]-unit square) as a [size]-px icon centred on ([cx], [cy]).
     * [strokeWidth] is in viewBox units (as in the SVG); 0 = filled.
     */
    fun draw(canvas: Canvas, path: Path, viewBox: Float, size: Float, cx: Float, cy: Float, color: Int, paint: Paint, strokeWidth: Float = 0f) {
        val s = size / viewBox
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(s, s)
        paint.color = color
        paint.shader = null
        if (strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        } else {
            paint.style = Paint.Style.FILL
        }
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }
}
