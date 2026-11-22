package com.echobedside.feature.photos.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

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
