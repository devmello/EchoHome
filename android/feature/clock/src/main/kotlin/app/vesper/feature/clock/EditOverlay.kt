package app.vesper.feature.clock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Motion
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import kotlin.math.max

/**
 * Long-press edit mode controls, from design/Customize.dc.html (the zoomed-out face itself is the
 * StandBy stage, scaled by [StandbyScreen]). Control layer only, so this is the one place with glass:
 *  - Night toggle (glass pill, top-left), tinted red while Night is forced on
 *  - Done (top-right): the one tinted primary action (system blue)
 *  - face switcher: a vertical glass page indicator (right), tap a dot or swipe the face
 *  - colour: a glass button that pops open into a capsule of plain-fill swatches (no glass on glass)
 * Empty areas don't consume touches, so swipes reach [StandbyScreen].
 */
@SuppressLint("ViewConstructor")
class EditOverlay(context: Context, private val callbacks: Callbacks) : FrameLayout(context) {

    interface Callbacks {
        fun onDone()
        fun onToggleNight()
        fun onPickFace(index: Int)
        fun onPickColor(color: Int)
        fun onPreviewTap()
    }

    private val labelText = SpecText(15f, 600, -0.01f)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = withAlpha(Color.WHITE, 0.14f) }
    private val ringRect = RectF(96f - 0.5f, 40f - 0.5f, 864f + 0.5f, 424f + 0.5f)

    val night = GlassPill(context, "Night", context.icon(R.drawable.ic_moon_22), 13f, 16f, 15f, 600)
    val done = GlassPill(context, "Done", null, 20f, 20f, 15f, 700).apply { tint = withAlpha(0xFF0A84FF.toInt(), 0.62f) }
    val dots = PageDots(context)
    val colors = ColorCapsule(context)
    private var faceLabel = ""

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(night, LayoutParams(night.measureWidth().toInt(), 44).apply { leftMargin = 22; topMargin = 20 })
        addView(done, LayoutParams(done.measureWidth().toInt(), 44).apply { leftMargin = 960 - 22 - done.measureWidth().toInt(); topMargin = 20 })
        addView(dots, LayoutParams(44, 16).apply { leftMargin = 960 - 26 - 44; topMargin = 232 })
        addView(colors, LayoutParams(ColorCapsule.MAX_W, 52).apply { leftMargin = 960 - 22 - ColorCapsule.MAX_W; topMargin = 480 - 22 - 52 })
        night.setOnClickListener { callbacks.onToggleNight() }
        done.setOnClickListener { callbacks.onDone() }
        dots.onPick = { callbacks.onPickFace(it) }
        colors.onPick = { callbacks.onPickColor(it) }
    }

    fun bind(label: String, faceCount: Int, faceIndex: Int, colorOptions: List<Int>, color: Int, nightOn: Boolean) {
        if (label != faceLabel) { faceLabel = label; invalidate() }
        night.tint = if (nightOn) withAlpha(0xFFBE180E.toInt(), 0.42f) else null
        night.fg = if (nightOn) 0xFFFFD9D4.toInt() else Color.WHITE
        dots.set(faceCount, faceIndex)
        (dots.layoutParams as LayoutParams).let { lp ->
            val h = 16 + 24 * faceCount
            if (lp.height != h) { lp.height = h; lp.topMargin = 240 - h / 2; dots.layoutParams = lp }
        }
        colors.set(colorOptions, color)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(ringRect, 34.5f, 34.5f, ringPaint)
        labelText.drawCentered(canvas, faceLabel, 480f, labelText.baselineIn(436f), withAlpha(Color.WHITE, 0.72f))
    }

    /** The fade-in applies alpha per child: no full-screen offscreen layer per frame. */
    override fun hasOverlappingRendering() = false

    /** A tap on the zoomed face (not on a control). */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean = false

    fun previewHit(x: Float, y: Float) = x in 96f..864f && y in 40f..424f
    fun previewTap() = callbacks.onPreviewTap()
}

/** Glass capsule button: optional icon + label (Customize.dc.html Night / Done). */
@SuppressLint("ViewConstructor")
class GlassPill(context: Context, private val label: String, private val icon: Drawable?, private val padL: Float, private val padR: Float,
                size: Float, weight: Int) : View(context) {
    private val glass = GlassPainter()
    private val text = SpecText(size, weight, -0.01f)
    var tint: Int? = null
        set(v) { if (field != v) { field = v; glass.tint = v; invalidate() } }
    var fg: Int = Color.WHITE
        set(v) { if (field != v) { field = v; invalidate() } }

    fun measureWidth(): Float = padL + (if (icon != null) 17f + 7f else 0f) + text.width(label) + padR

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        glass.draw(canvas, 0f, 0f, width.toFloat(), h)
        var x = padL
        icon?.let { drawIcon(canvas, it, x, (h - 17f) / 2, 17f, fg); x += 17f + 7f }
        text.draw(canvas, label, x, text.baselineIn(0f, h), fg)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { animate().scaleX(1.06f).scaleY(1.06f).setDuration(220).setInterpolator(Motion.SPRING).start() }
            MotionEvent.ACTION_UP -> { animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.SPRING).start(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick() }
            MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(Motion.SPRING).start()
        }
        return true
    }
}

/** Vertical glass page indicator: 44 wide, 24 px per face, current dot 8×22 white, others 8×8 at .42. */
class PageDots(context: Context) : View(context) {
    private val glass = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var count = 0
    private var index = 0
    var onPick: ((Int) -> Unit)? = null

    fun set(count: Int, index: Int) {
        if (count != this.count || index != this.index) { this.count = count; this.index = index; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        glass.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat())
        for (i in 0 until count) {
            val cy = 8f + 24f * i + 12f
            val h = if (i == index) 22f else 8f
            paint.color = if (i == index) Color.WHITE else withAlpha(Color.WHITE, 0.42f)
            rect.set(18f, cy - h / 2, 26f, cy + h / 2)
            canvas.drawRoundRect(rect, 4f, 4f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = ((e.y - 8f) / 24f).toInt()
            if (i in 0 until count && i != index) onPick?.invoke(i)
        }
        return true
    }
}

/**
 * Colour button that pops open into the swatch capsule (NOTES §3.1 rule 5: menus pop out of the
 * control that opened them). One glass surface; the swatches are plain fills. Right-anchored.
 */
class ColorCapsule(context: Context) : View(context) {
    private val glass = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var options: List<Int> = emptyList()
    private var current = Color.WHITE
    private var progress = 0f
    private var anim: ValueAnimator? = null
    var open = false
        private set
    var onPick: ((Int) -> Unit)? = null

    fun set(options: List<Int>, current: Int) {
        if (options != this.options) { this.options = options; if (options.isEmpty()) setOpen(false, animate = false) }
        this.current = current
        visibility = if (options.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    fun setOpen(o: Boolean, animate: Boolean = true) {
        if (o == open && anim == null) return
        open = o
        anim?.cancel()
        if (!animate) { progress = if (o) 1f else 0f; invalidate(); return }
        anim = ValueAnimator.ofFloat(progress, if (o) 1f else 0f).apply {
            duration = if (o) 520 else 360
            interpolator = if (o) Motion.SPRING_SOFT else Motion.EASE
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { anim = null } })
            start()
        }
    }

    private fun openWidth() = 8f + options.size * 40f + options.size * 2f + 44f + 4f
    private fun currentWidth() = 52f + (openWidth() - 52f) * progress

    override fun onDraw(canvas: Canvas) {
        if (options.isEmpty()) return
        val r = width.toFloat()
        val h = height.toFloat()
        val cw = currentWidth()
        val l = r - cw
        glass.shadow = true
        glass.draw(canvas, l, 0f, r, h)
        // toggle: 44×44 at the right (4 px padding), 28 px swatch with a black + white ring
        val tcx = r - 4f - 22f
        val cy = h / 2
        paint.color = withAlpha(Color.WHITE, 0.85f); canvas.drawCircle(tcx, cy, 14f + 3.5f, paint)
        paint.color = withAlpha(Color.BLACK, 0.6f); canvas.drawCircle(tcx, cy, 14f + 2f, paint)
        paint.color = current; canvas.drawCircle(tcx, cy, 14f, paint)
        if (progress <= 0.01f) return
        canvas.save()
        canvas.clipRect(l + 4f, 0f, r, h)
        val a = progress.coerceIn(0f, 1f)
        options.forEachIndexed { i, c ->
            val cx = r - 4f - 44f - 2f - (options.size - i) * 42f + 20f + 2f
            if (c == current) {
                paint.color = withAlpha(Color.WHITE, a); canvas.drawCircle(cx, cy, 13f + 4f, paint)
                paint.color = withAlpha(Color.BLACK, 0.7f * a); canvas.drawCircle(cx, cy, 13f + 2f, paint)
            }
            paint.color = withAlpha(c, a); canvas.drawCircle(cx, cy, 13f, paint)
            if (c != current) {
                ring.strokeWidth = 0.5f; ring.color = withAlpha(Color.WHITE, 0.3f * a)
                canvas.drawCircle(cx, cy, 12.75f, ring)
            }
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = width.toFloat()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return e.x >= r - currentWidth()
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        if (e.x >= r - 4f - 44f) { setOpen(!open); return true }
        if (!open) return true
        options.forEachIndexed { i, c ->
            val cx = r - 4f - 44f - 2f - (options.size - i) * 42f + 20f + 2f
            if (e.x in (cx - 20f)..(cx + 20f)) { onPick?.invoke(c); return true }
        }
        return true
    }

    companion object {
        /** Room for 9 swatches. */
        val MAX_W = max(52, (8 + 9 * 42 + 44 + 4))
    }
}
