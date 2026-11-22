package app.vesper.feature.system.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * The music island's five equaliser bars (Island.dc.html: 3.5 px wide, 3 px apart, heights
 * 6 + 16·|sin(t·(3.1 + 0.9i) + i)|) as their own 30×24 px View.
 *
 * Why a View: the island overlay covers the whole window, and under hardware rendering an
 * invalidate damages a view's whole bounds (the rect argument is ignored), so animating the bars
 * inside the overlay repainted the full screen and every glow layer under it ~30 times a second.
 * Animating this view damages only its own 30×24 px, which the Echo's GPU repaints alone
 * (EGL_KHR_partial_update). It ticks at [FRAME_MS] (~15 fps, plenty for 22 px bars) only while
 * [playing] and shown; paused and Night are static (the overlay sets [playing] false).
 */
internal class IslandEqualizer(context: Context) : View(context) {
    var color = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    var playing = false
        set(v) { if (v != field) { field = v; invalidate() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tick = Runnable { invalidate() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun hasOverlappingRendering() = false

    override fun onDraw(c: Canvas) {
        val t = SystemClock.uptimeMillis() / 1000f
        paint.color = color
        val cy = H / 2f
        var x = 0f
        for (i in 0 until 5) {
            val bh = if (playing) 6f + 16f * abs(sin(t * (3.1f + i * 0.9f) + i)) else 3.5f
            rect.set(x, cy - bh / 2f, x + BAR, cy + bh / 2f)
            c.drawRoundRect(rect, 2f, 2f, paint)
            x += BAR + GAP
        }
        removeCallbacks(tick)
        if (playing && isShown) postDelayed(tick, FRAME_MS)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) removeCallbacks(tick) else if (playing) invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        const val BAR = 3.5f
        const val GAP = 3f
        /** Width of the five bars (29.5 px); the view is [W_PX] wide. */
        const val W = 5 * BAR + 4 * GAP
        const val W_PX = 30
        const val H = 24f
        const val H_PX = 24
        /** ~15 fps. */
        const val FRAME_MS = 66L
    }
}
