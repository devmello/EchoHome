package com.echobedside.feature.photos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import kotlin.math.ceil

/**
 * The contents of the glass control capsule (Photos.dc.html): album button, pause/play, next.
 * Drawn with plain fills — it sits on glass, so no glass on glass (NOTES §3.1).
 *
 * Capsule: height 56, padding 0 6, gap 2. Album button: height 44, padding 0 14, radius 22,
 * 15 px / 700, fill rgba(255,255,255,.14), 12 px chevron (stroke 3) after a 6 px gap.
 * Pause/next: 44×44 with 22 px icons (next: chevron, stroke 2.6).
 */
@SuppressLint("ViewConstructor")
internal class ControlsBar(context: Context) : View(context) {
    var onAlbum: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    var albumName = "Album"
        set(v) { if (v != field) { field = v; requestLayout(); invalidate() } }
    var paused = false
        set(v) { if (v != field) { field = v; invalidate(); updateDescription() } }
    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }

    private val label = SpecText(15f, 700)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var pressed = -1

    private val albumWidth get() = 14f + label.width(albumName) + 6f + 12f + 14f
    private val albumRight get() = PAD + albumWidth
    private val pauseCx get() = albumRight + GAP + 22f
    private val nextCx get() = albumRight + GAP + 44f + GAP + 22f

    init { updateDescription() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ceil(PAD + albumWidth + GAP + 44f + GAP + 44f + PAD).toInt(), HEIGHT)
    }

    override fun onDraw(canvas: Canvas) {
        val white = if (night) Night.COLOR else Color.WHITE
        val cy = HEIGHT / 2f
        // album button
        rect.set(PAD, cy - 22f, albumRight, cy + 22f)
        paint.color = withAlpha(white, if (pressed == 0) 0.26f else 0.14f)
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        val tw = label.width(albumName)
        label.draw(canvas, albumName, PAD + 14f, label.baselineIn(cy - label.normalLineHeight / 2f), white)
        Icons.draw(canvas, Icons.chevronDown, 24f, 12f, PAD + 14f + tw + 6f + 6f, cy, white, paint, strokeWidth = 3f)
        // pause / play
        if (pressed == 1) { paint.color = withAlpha(white, 0.14f); canvas.drawCircle(pauseCx, cy, 22f, paint) }
        Icons.draw(canvas, if (paused) Icons.play else Icons.pause, 32f, 22f, pauseCx, cy, white, paint)
        // next
        if (pressed == 2) { paint.color = withAlpha(white, 0.14f); canvas.drawCircle(nextCx, cy, 22f, paint) }
        Icons.draw(canvas, Icons.chevronRight, 24f, 22f, nextCx, cy, white, paint, strokeWidth = 2.6f)
    }

    private fun hit(x: Float): Int = when {
        x < albumRight + GAP / 2 -> 0
        x < albumRight + GAP + 44f + GAP / 2 -> 1
        else -> 2
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit(e.x); invalidate() }
            MotionEvent.ACTION_MOVE -> if (e.y < -20 || e.y > height + 20 || hit(e.x) != pressed) { if (pressed != -1) { pressed = -1; invalidate() } }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1; invalidate()
                when (p) { 0 -> onAlbum?.invoke(); 1 -> onPause?.invoke(); 2 -> onNext?.invoke() }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    private fun updateDescription() {
        contentDescription = "Album $albumName, ${if (paused) "Resume slideshow" else "Pause slideshow"}, Next photo"
    }

    companion object {
        const val HEIGHT = 56
        private const val PAD = 6f
        private const val GAP = 2f
    }
}
