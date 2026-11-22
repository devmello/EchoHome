package com.echobedside.core.design

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one Liquid Glass shape (NOTES §3.2) with no live blur:
 *  1. lift shadow (0 10px 30px .45) — skipped over pure black where it can't be seen
 *  2. backdrop: the matching region of a pre-blurred [Backdrop], if any
 *  3. fill: rgba(255,255,255,.09) or a tint (tinted glass, e.g. orange .58)
 *  4. specular overlay: linear-gradient(160deg, .26, .04 36%, 0 64%, .07)
 *  5. rims: 1 px top highlight (.42), bottom (.08), 0.5 px border (.22)
 *
 * Rules (NOTES §3.1): glass is for controls only, never on content; nothing glass on glass
 * (things on a glass surface use plain fills); tint only the primary action.
 */
class GlassPainter {
    var tint: Int? = null
    var backdrop: Backdrop? = null
    var shadow: Boolean = true
    /** Multiplies everything (fades, Night mode dimming). */
    var alpha: Float = 1f
    /** Night mode: no glass (NOTES §5.4), just a dim red outline. */
    var night: Boolean = false

    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = GlassTokens.BORDER_WIDTH }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shadowPath = Path()
    private val matrix = Matrix()
    private var shaderFor: Backdrop? = null
    private var specKey = 0L

    /**
     * Draws the glass in [l,t,r,b] (canvas coords) with corner [radius] (-1 = fully round).
     * [windowX]/[windowY] are the canvas origin's position in the window, used to line up the backdrop.
     */
    fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float = -1f, windowX: Float = 0f, windowY: Float = 0f) {
        rect.set(l, t, r, b)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0 || alpha <= 0f) return
        val rad = if (radius < 0) min(w, h) / 2f else min(radius, min(w, h) / 2f)
        val a = alpha

        if (night) {
            borderPaint.color = withAlpha(Night.COLOR, 0.5f * a)
            canvas.drawRoundRect(rect, rad, rad, borderPaint)
            return
        }

        // 1. shadow, outside the shape only
        val bd = backdrop
        if (shadow && bd != null) {
            shadowPaint.setShadowLayer(GlassTokens.SHADOW_BLUR * 0.8f, 0f, GlassTokens.SHADOW_DY, withAlpha(Color.BLACK, GlassTokens.SHADOW_ALPHA * a))
            shadowPath.reset()
            shadowPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
            canvas.save()
            canvas.clipOutPath(shadowPath)
            canvas.drawPath(shadowPath, shadowPaint)
            canvas.restore()
        }

        // 2. backdrop
        if (bd != null) {
            if (shaderFor !== bd) {
                backdropPaint.shader = BitmapShader(bd.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderFor = bd
            }
            matrix.setScale(bd.screenWidth / bd.bitmap.width, bd.screenHeight / bd.bitmap.height)
            matrix.postTranslate(bd.screenLeft - windowX, bd.screenTop - windowY)
            backdropPaint.shader.setLocalMatrix(matrix)
            backdropPaint.alpha = (255 * a).toInt()
            canvas.drawRoundRect(rect, rad, rad, backdropPaint)
        }

        // 3. fill / tint
        val fill = tint?.let { if (Color.alpha(it) == 255) withAlpha(it, GlassTokens.TINT_ALPHA) else it } ?: GlassTokens.FILL
        fillPaint.color = withAlpha(fill, Color.alpha(fill) / 255f * a)
        canvas.drawRoundRect(rect, rad, rad, fillPaint)

        // 4. specular gradient (rebuilt only when the size or alpha changes)
        val key = (w.toLong() shl 40) or (h.toLong() shl 20) or (a * 1000).toLong()
        if (key != specKey) {
            specKey = key
            val ang = Math.toRadians(GlassTokens.SPECULAR_ANGLE.toDouble())
            val dx = sin(ang).toFloat()
            val dy = -cos(ang).toFloat()
            val len = abs(w * dx) + abs(h * dy)
            val cx = w / 2f
            val cy = h / 2f
            val colors = IntArray(4) { withAlpha(Color.WHITE, GlassTokens.SPECULAR_ALPHAS[it] * a) }
            specPaint.shader = LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                colors, GlassTokens.SPECULAR_STOPS, Shader.TileMode.CLAMP)
            val rimH = min(h * 0.5f, 18f)
            rimTop = LinearGradient(0f, 0f, 0f, rimH, withAlpha(Color.WHITE, GlassTokens.RIM_TOP_ALPHA * a), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            rimBottom = LinearGradient(0f, h, 0f, h - rimH, withAlpha(Color.WHITE, GlassTokens.RIM_BOTTOM_ALPHA * a), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(l, t)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, rad, rad, specPaint)

        // 5. rims: inset 0 1px 1px → a 1.5 px stroke just inside the edge, faded by a gradient
        rimPaint.strokeWidth = 1.5f
        rect.set(0.75f, 0.75f, w - 0.75f, h - 0.75f)
        rimPaint.shader = rimTop
        canvas.drawRoundRect(rect, rad - 0.75f, rad - 0.75f, rimPaint)
        rimPaint.shader = rimBottom
        canvas.drawRoundRect(rect, rad - 0.75f, rad - 0.75f, rimPaint)
        borderPaint.color = withAlpha(GlassTokens.BORDER, Color.alpha(GlassTokens.BORDER) / 255f * a)
        rect.set(0.25f, 0.25f, w - 0.25f, h - 0.25f)
        canvas.drawRoundRect(rect, rad, rad, borderPaint)
        canvas.restore()
    }

    private var rimTop: Shader? = null
    private var rimBottom: Shader? = null
}

/**
 * A container drawn as glass: surfaces, capsules (radius -1), tiles, sheets, popovers.
 * Children draw on top with plain fills (no glass on glass).
 */
open class GlassLayout(context: Context) : FrameLayout(context) {
    val glass = GlassPainter()
    /** -1 = fully round (capsule / circle). */
    var cornerRadius: Float = -1f
        set(v) { field = v; invalidate() }
    var tint: Int?
        get() = glass.tint
        set(v) { glass.tint = v; invalidate() }
    var backdrop: Backdrop?
        get() = glass.backdrop
        set(v) { glass.backdrop = v; invalidate() }
    var night: Boolean
        get() = glass.night
        set(v) { glass.night = v; invalidate() }

    private val tracker = GlassTracker(this) { glass.backdrop != null }

    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        tracker.update()
        glass.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, tracker.x, tracker.y)
    }
}

/**
 * Keeps glass over a [Backdrop] correct while it moves: translation/scroll changes don't call
 * onDraw, so on every frame it checks the view's window position and invalidates if it moved.
 * Also lets the lift shadow draw outside the view (sets the parent's clipChildren = false).
 */
class GlassTracker(private val view: View, private val active: () -> Boolean) {
    private val loc = IntArray(2)
    var x = 0f; private set
    var y = 0f; private set

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        // Skip views that won't draw (a hidden parent): otherwise they'd invalidate every frame.
        if (active() && view.isShown) {
            view.getLocationInWindow(loc)
            if (loc[0].toFloat() != x || loc[1].toFloat() != y) {
                x = loc[0].toFloat(); y = loc[1].toFloat()
                view.invalidate()
            }
        }
        true
    }

    init {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                (v.parent as? ViewGroup)?.clipChildren = false
                v.viewTreeObserver.addOnPreDrawListener(preDraw)
            }
            override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(preDraw)
        })
    }

    /** Call at the start of onDraw. */
    fun update() {
        if (!active()) return
        view.getLocationInWindow(loc)
        x = loc[0].toFloat(); y = loc[1].toFloat()
    }
}

/**
 * Glass slider (Control Center brightness / volume, Now Playing volume): a glass track with a
 * white fill rising from the bottom (vertical) or growing from the left (horizontal).
 */
open class GlassSlider(context: Context, val vertical: Boolean) : View(context) {
    val glass = GlassPainter()
    var value: Float = 0.5f
        set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
    var cornerRadius: Float = -1f
    var fillColor: Int = withAlpha(Color.WHITE, 0.92f)
    var night: Boolean = false
        set(v) { field = v; glass.night = v; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val bar = Path()
    private val fillPath = Path()
    private val rect = RectF()
    private val tracker = GlassTracker(this) { glass.backdrop != null }
    private var startValue = 0f
    private var startPos = 0f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = if (cornerRadius < 0) min(w, h) / 2f else cornerRadius
        tracker.update()
        glass.draw(canvas, 0f, 0f, w, h, rad, tracker.x, tracker.y)
        // Fill = shape ∩ bar, as a path so its edges are anti-aliased (clipPath isn't, on HWUI).
        shape.reset(); bar.reset()
        rect.set(0f, 0f, w, h)
        shape.addRoundRect(rect, rad, rad, Path.Direction.CW)
        if (vertical) rect.set(0f, h * (1 - value), w, h) else rect.set(0f, 0f, w * value, h)
        bar.addRect(rect, Path.Direction.CW)
        fillPath.op(shape, bar, Path.Op.INTERSECT)
        fill.color = if (night) Night.COLOR else fillColor
        canvas.drawPath(fillPath, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pos = if (vertical) -e.y else e.x
        val span = (if (vertical) height else width).toFloat().coerceAtLeast(1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); startValue = value; startPos = pos }
            MotionEvent.ACTION_MOVE -> { value = startValue + (pos - startPos) / span; onChange?.invoke(value) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onRelease?.invoke(value)
        }
        return true
    }
}
