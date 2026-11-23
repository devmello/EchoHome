package app.vesper.feature.media

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import kotlin.math.floor

/**
 * Lyrics column (Apple Music style). Synced: the current line large and bright, upcoming lines
 * dimmer, past lines faded; moving to the next line is one eased scroll ([SCROLL_MS]) driven by a
 * fractional "current index", so line position and brightness animate together. Between lines
 * nothing redraws. Plain lyrics are static and drag-scrollable. Alpha is per line (no offscreen
 * layer for an edge mask).
 */
internal class LyricsView(context: Context) : View(context) {
    companion object {
        const val W = NP.COL_W
        const val H = 156f
        const val LINE_H = 36f
        const val GAP = 14f
        const val BLANK_H = 22f
        const val ANCHOR = 38f
        const val SCROLL_MS = 360L

        /** Brightness of a line `d` lines after the current one (d < 0: already sung). */
        fun alphaAt(d: Float): Float = when {
            d <= -1f -> 0.26f
            d < 0f -> 0.26f + 0.74f * (d + 1f)
            d < 1f -> 1f - 0.5f * d
            else -> maxOf(0.5f - 0.1f * (d - 1f), 0.3f)
        }
    }

    private val big by lazy { SpecText(28f, 700, -0.02f) }
    private val small by lazy { SpecText(20f, 500, -0.01f) }

    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }

    private var lines: List<Array<String>> = emptyList()
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var synced = false
    private var message: String? = null

    private var cur = -1f
    private var target = -1
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SCROLL_MS
        interpolator = Motion.EASE
    }
    private var animFrom = -1f
    private var plainScroll = 0f
    private var plainMax = 0f

    init {
        anim.addUpdateListener { cur = animFrom + (target - animFrom) * it.animatedFraction; invalidate() }
    }

    fun showMessage(m: String?) {
        stop()
        message = m; lines = emptyList(); synced = false; invalidate()
    }

    fun setLyrics(l: Lyrics) {
        stop()
        message = null
        when (l) {
            is Lyrics.Synced -> {
                synced = true
                layoutLines(l.lines.map { it.text }, big, 0f)
            }
            is Lyrics.Plain -> {
                synced = false
                layoutLines(l.lines, small, 4f)
                plainScroll = 0f
                plainMax = maxOf(0f, (tops.lastOrNull() ?: 0f) + (heights.lastOrNull() ?: 0f) - H + 20f)
            }
            Lyrics.None -> { synced = false; lines = emptyList(); message = "No lyrics" }
        }
        cur = -1f; target = -1
        invalidate()
    }

    private fun layoutLines(src: List<String>, style: SpecText, gap: Float) {
        val lh = if (style === big) LINE_H else 28f
        val g = if (style === big) GAP else gap
        lines = src.map { t -> Lrc.wrap(t, W - 8f) { style.width(it) }.toTypedArray() }
        tops = FloatArray(lines.size); heights = FloatArray(lines.size)
        var y = 0f
        for (i in lines.indices) {
            val blank = src[i].isEmpty()
            val h = if (blank) (if (style === big) BLANK_H else 14f) else lines[i].size * lh + g
            tops[i] = y; heights[i] = h; y += h
        }
    }

    /** Moves the highlight to line [index] (-1 = before the first line); eased unless [animate] is false. */
    fun setIndex(index: Int, animate: Boolean) {
        if (!synced || index == target) return
        anim.cancel()
        target = index
        if (!animate || !isShown || kotlin.math.abs(index - cur) > 12) { cur = index.toFloat(); invalidate(); return }
        animFrom = cur
        anim.start()
    }

    /** Stops the scroll animation (screen hidden / paused-hidden). */
    fun stop() { anim.cancel(); if (synced) cur = target.toFloat() }

    private fun scrollFor(f: Float): Float {
        if (tops.isEmpty()) return 0f
        val ff = f.coerceIn(0f, (tops.size - 1).toFloat())
        val i = floor(ff).toInt()
        val frac = ff - i
        val next = if (i + 1 < tops.size) tops[i + 1] else tops[i] + heights[i]
        return tops[i] + (next - tops[i]) * frac
    }

    private fun edge(yTop: Float, h: Float): Float {
        val c = yTop + minOf(h, LINE_H) / 2f
        val top = (c / 30f).coerceIn(0f, 1f)
        val bottom = ((H - c) / 26f).coerceIn(0f, 1f)
        return minOf(top, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        val white = Night.map(Color.WHITE, night)
        message?.let {
            small.draw(canvas, it, 0f, small.baselineIn(ANCHOR, LINE_H), withAlpha(white, 0.6f)); return
        }
        if (lines.isEmpty()) return
        if (synced) {
            val scroll = scrollFor(cur)
            for (i in lines.indices) {
                val y = ANCHOR + tops[i] - scroll
                if (y + heights[i] < 0f || y > H) continue
                val a = alphaAt(i - cur) * edge(y, heights[i])
                if (a <= 0.01f) continue
                val col = withAlpha(white, a)
                for ((k, t) in lines[i].withIndex()) {
                    if (t.isEmpty()) continue
                    big.draw(canvas, t, 0f, big.baselineIn(y + k * LINE_H, LINE_H), col)
                }
            }
        } else {
            for (i in lines.indices) {
                val y = tops[i] - plainScroll
                if (y + heights[i] < 0f || y > H) continue
                val a = 0.82f * edge(y, 28f)
                if (a <= 0.01f) continue
                for ((k, t) in lines[i].withIndex()) {
                    if (t.isEmpty()) continue
                    small.draw(canvas, t, 0f, small.baselineIn(y + k * 28f, 28f), withAlpha(white, a))
                }
            }
        }
    }

    private var lastY = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (synced || plainMax <= 0f || lines.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                plainScroll = (plainScroll - (e.y - lastY)).coerceIn(0f, plainMax)
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
}
