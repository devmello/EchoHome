package com.echobedside.feature.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.withAlpha
import kotlin.math.hypot

class AndroidPathSink(val path: Path) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = path.close()
}

/** A CSS-style transition of one value: when the target changes, animate from the current value. */
class Tween(initial: Float, private val durationMs: Long, private val curve: Interpolator) {
    private var from = initial
    private var to = initial
    private var start = 0L
    var value = initial; private set
    val target get() = to

    fun set(target: Float, now: Long) {
        if (target == to) return
        from = value; to = target; start = now
    }

    fun snap(v: Float) { from = v; to = v; value = v }

    /** Advances to [now]; returns true while still moving. */
    fun step(now: Long): Boolean {
        val f = ((now - start).toFloat() / durationMs).coerceIn(0f, 1f)
        value = from + (to - from) * curve.getInterpolation(f)
        return f < 1f
    }
}

fun rgba(r: Int, g: Int, b: Int, a: Float) = Color.argb((a * 255 + 0.5f).toInt(), r, g, b)

/**
 * Behind everything: #04050A, whatever was on screen before (blurred 22 px, saturate 1.2,
 * opacity .32, scale 1.06) and the top-down darkening gradient. Static: redrawn only when the
 * snapshot changes.
 */
class BackgroundLayer(context: Context) : View(context) {
    private var snapshot: Bitmap? = null
    private val snapPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.32f * 255).toInt() }
    private val shade = Paint()
    private val dst = RectF()

    fun setSnapshot(b: Bitmap?) {
        snapshot?.takeIf { it !== b }?.recycle()
        snapshot = b
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(rgba(4, 5, 10, 0.55f), rgba(4, 5, 10, 0.25f), rgba(4, 5, 10, 0.1f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        val w = width.toFloat(); val h = height.toFloat()
        snapshot?.let {
            val sw = w * 1.06f; val sh = h * 1.06f
            dst.set((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2)
            canvas.drawBitmap(it, null, dst, snapPaint)
        }
        canvas.drawRect(0f, 0f, w, h, shade)
    }

    companion object {
        val BG = 0xFF04050A.toInt()

        /**
         * Blurs a quarter-size snapshot of the screen that was showing (the design's
         * `filter: blur(22px) saturate(1.2)`), once, when the assistant opens.
         */
        fun blurSnapshot(small: Bitmap): Bitmap = PreBlur.blur(small, blurPx = 22f, downscale = 1, saturate = 1.2f, brightness = 1f)
    }
}

/**
 * The voice light pooled under the droplet. Its gradient is drawn once; opacity and scale follow
 * the voice level through View properties, so per-frame changes don't re-record anything.
 */
class GlowLayer(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF(180f, 290f, 780f, 810f)
    var night = false
        set(v) { if (field != v) { field = v; build(); invalidate() } }

    init { build(); pivotX = 480f; pivotY = 550f }

    private fun build() {
        // radial-gradient(ellipse at 50% 50%) sizes to the farthest corner: half-sizes × √2.
        val rx = 300f * 1.41421f
        val ry = 260f * 1.41421f
        val c = if (night) intArrayOf(withAlpha(Night.COLOR, 0.5f), withAlpha(Night.COLOR, 0.32f), withAlpha(Night.COLOR, 0.15f), Color.TRANSPARENT)
        else intArrayOf(rgba(100, 210, 255, 0.55f), rgba(191, 90, 242, 0.42f), rgba(255, 55, 95, 0.22f), Color.TRANSPARENT)
        val g = RadialGradient(480f, 550f, rx, c, floatArrayOf(0f, 0.3f, 0.5f, 0.7f), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, 480f, 550f) })
        paint.shader = g
    }

    override fun onDraw(canvas: Canvas) { canvas.drawOval(oval, paint) }
}

/**
 * The voice droplet: a bead of Liquid Glass with light moving inside (Assistant.dc.html). The shape
 * is a path whose 8 radii wobble with the voice level; the inside is a pre-blurred conic gradient
 * texture, rotated and scaled per frame through a shader matrix (no per-frame bitmaps).
 */
@SuppressLint("ViewConstructor")
class DropletLayer(context: Context, private val onTap: () -> Unit) : View(context) {
    var night = false
        set(v) { if (field != v) { field = v; conic = buildConic(v); conicPaint.shader = BitmapShader(conic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); invalidate() } }

    private val path = Path()
    private val sink = AndroidPathSink(path)
    private val radii = FloatArray(8)
    private val size = Tween(84f, 500, Motion.SPRING)
    private val m = Matrix()
    private val dst = RectF()

    private var conic = buildConic(false)
    private val conicPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(conic, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 0.08f) }
    private val rimTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f
        shader = LinearGradient(0f, 0f, 0f, 1f, withAlpha(Color.WHITE, 0.65f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val rimBottom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
        shader = LinearGradient(0f, 0f, 0f, 1f, Color.TRANSPARENT, withAlpha(Color.WHITE, 0.18f), Shader.TileMode.CLAMP)
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // radial-gradient(ellipse 58% 40% at 34% 22%, .8 0, .12 55%, 0 70%), unit radius: scaled per frame
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(withAlpha(Color.WHITE, 0.8f), withAlpha(Color.WHITE, 0.12f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 0.7f), Shader.TileMode.CLAMP)
    }
    private val shadowTex = buildShadow()
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.5f * 255).toInt() }

    // Per-frame values, set by [frame].
    private var amp = 3f
    private var spin = 0f
    private var inner = 0.55f
    private var innerScale = 0.96f
    private var t = 0.0
    private var cx = 480f
    private var cy = 412f

    /**
     * One animation frame. [phase] is "idle", "listen", "think" or "answer"; [talking] = the answer
     * is still being revealed or spoken.
     */
    fun frame(now: Long, phase: String, level: Float, talking: Boolean) {
        t = now / 1000.0
        size.set(Blob.size(phase), now)
        size.step(now)
        val targetAmp = when { phase == "listen" -> 9f * level; phase == "answer" && talking -> 6f; else -> 3f }
        amp += (targetAmp - amp) * 0.18f // border-radius transition .28s
        spin = ((t * if (phase == "listen") 160.0 else 60.0) % 360.0).toFloat()
        val targetInner = if (phase == "idle") 0.55f else 0.95f
        inner += (targetInner - inner) * 0.15f
        innerScale += ((0.9f + 0.25f * level) - innerScale) * 0.35f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val s = size.value
        cx = width / 2f
        cy = height - 26f - s / 2f
        val l = cx - s / 2; val tp = cy - s / 2

        // 0 12px 34px rgba(0,0,0,.5)
        val ss = s * SHADOW_TEX / SHADOW_DISC
        dst.set(cx - ss / 2, cy + 12 - ss / 2, cx + ss / 2, cy + 12 + ss / 2)
        canvas.drawBitmap(shadowTex, null, dst, shadowPaint)

        path.rewind()
        Blob.path(sink, l, tp, s, s, Blob.radii(t, amp, radii))
        canvas.drawPath(path, fill)

        // inset rims (under the light, as in CSS); unit gradients placed by matrix, no per-frame shaders
        m.setScale(1f, s * 0.3f); m.postTranslate(0f, tp)
        rimTop.shader.setLocalMatrix(m)
        canvas.drawPath(path, rimTop)
        m.setScale(1f, s * 0.25f); m.postTranslate(0f, tp + s * 0.75f)
        rimBottom.shader.setLocalMatrix(m)
        canvas.drawPath(path, rimBottom)

        // conic light: 150% box, rotate(spin) scale(innerScale) about the centre
        val box = s * 1.5f * innerScale
        m.setScale(box / conic.width, box / conic.height)
        m.postTranslate(cx - box / 2, cy - box / 2)
        m.postRotate(spin, cx, cy)
        conicPaint.shader.setLocalMatrix(m)
        conicPaint.alpha = (inner * 255).toInt()
        canvas.drawPath(path, conicPaint)

        m.setScale(0.58f * s, 0.40f * s)
        m.postTranslate(l + 0.34f * s, tp + 0.22f * s)
        highlight.shader.setLocalMatrix(m)
        canvas.drawPath(path, highlight)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = size.value / 2 + 12
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return hypot(e.x - cx, e.y - cy) <= r
        if (e.actionMasked == MotionEvent.ACTION_UP && hypot(e.x - cx, e.y - cy) <= r + 20) onTap()
        return true
    }

    companion object {
        private const val CONIC = 96
        private const val SHADOW_TEX = 72f
        private const val SHADOW_DISC = 32f

        /** conic-gradient(from 0deg, #64D2FF, #BF5AF2, #FF375F, #FF9F0A, #64D2FF) + blur(14px), at 96 px. */
        fun buildConic(night: Boolean): Bitmap {
            val b = Bitmap.createBitmap(CONIC, CONIC, Bitmap.Config.ARGB_8888)
            val colors = if (night) intArrayOf(Night.COLOR, 0xFF6E0E08.toInt(), Night.COLOR, 0xFF4A0905.toInt(), Night.COLOR)
            else intArrayOf(0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt())
            val c = CONIC / 2f
            // CSS conic starts at 12 o'clock, Android's sweep at 3 o'clock.
            val g = SweepGradient(c, c, colors, null).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, c, c) }) }
            Canvas(b).drawRect(0f, 0f, CONIC.toFloat(), CONIC.toFloat(), Paint().apply { shader = g })
            val px = IntArray(CONIC * CONIC)
            b.getPixels(px, 0, CONIC, 0, 0, CONIC, CONIC)
            // 14 px blur on a 174 px (150% of 116) box → 7.7 px at 96 px
            PreBlur.gaussian(px, CONIC, CONIC, 7.7f)
            b.setPixels(px, 0, CONIC, 0, 0, CONIC, CONIC)
            return b
        }

        private fun buildShadow(): Bitmap {
            val n = SHADOW_TEX.toInt()
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            Canvas(b).drawCircle(n / 2f, n / 2f, SHADOW_DISC / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            val px = IntArray(n * n)
            b.getPixels(px, 0, n, 0, 0, n, n)
            PreBlur.gaussian(px, n, n, 6f)
            b.setPixels(px, 0, n, 0, 0, n, n)
            return b
        }
    }
}
