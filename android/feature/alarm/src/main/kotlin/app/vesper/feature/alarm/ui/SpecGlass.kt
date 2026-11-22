package app.vesper.feature.alarm.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Night
import app.vesper.core.design.withAlpha
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The glass values of one control in Alarm.dc.html / Timers.dc.html. Those boards tune the NOTES
 * §3.2 recipe per control (Snooze: tint .58, 1.5 px rim .6, inner glow, warm shadow; timer cards:
 * rim .48, shadow 0 18 44), so this is the recipe with every number exposed.
 */
data class GlassSpec(
    val fill: Int = withAlpha(Color.WHITE, 0.09f),
    val border: Int = withAlpha(Color.WHITE, 0.22f),
    /** inset 0 [rimTopWidth]px 1px rgba(255,255,255,[rimTop]) */
    val rimTop: Float = 0.42f,
    val rimTopWidth: Float = 1f,
    /** inset 0 -1px 1px [rimBottom] (0 = none) */
    val rimBottom: Int = withAlpha(Color.WHITE, 0.08f),
    /** inset 0 0 [innerGlowBlur]px [innerGlow] (0 = none) */
    val innerGlow: Int = 0,
    val innerGlowBlur: Float = 24f,
    /** box-shadow 0 [shadowDy]px [shadowBlur]px [shadow] (0 = none) */
    val shadow: Int = withAlpha(Color.BLACK, 0.45f),
    val shadowDy: Float = 10f,
    val shadowBlur: Float = 30f,
    /** The specular overlay: linear-gradient([specAngle]deg, alphas at stops). */
    val specAngle: Float = 160f,
    val specStops: FloatArray = floatArrayOf(0f, 0.36f, 0.64f, 1f),
    val specAlphas: FloatArray = floatArrayOf(0.26f, 0.04f, 0f, 0.07f),
)

/**
 * Draws a [GlassSpec] shape with no live blur (NOTES §5.2). Blurred parts (shadow, inner glow) are
 * rendered once per size into ALPHA_8 bitmaps and then just blitted, so redraws cost a few quads.
 * Optional [backdrop]: the pre-blurred background region (cards over the Timers gradient).
 * Night: a dim red outline only.
 */
class SpecGlassPainter(var spec: GlassSpec) {
    var backdrop: Backdrop? = null
    var night = false
    /** Overrides [GlassSpec.fill] (animated tints). */
    var fillOverride: Int? = null
    var shadowOverride: Int? = null

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spec2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bdPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private var bdFor: Backdrop? = null
    private var cacheKey = ""
    private var shadowBmp: Bitmap? = null
    private var shadowPad = 0f
    private var glowBmp: Bitmap? = null
    private var specShader: Shader? = null
    private var rimTopShader: Shader? = null
    private var rimBottomShader: Shader? = null

    /** Draws at (0,0,w,h) in the current canvas; [wx]/[wy] = canvas origin in window px (backdrop). */
    fun draw(c: Canvas, w: Float, h: Float, radius: Float = -1f, wx: Float = 0f, wy: Float = 0f) {
        if (w <= 0 || h <= 0) return
        val r = if (radius < 0) min(w, h) / 2 else min(radius, min(w, h) / 2)
        if (night) {
            stroke.shader = null; stroke.strokeWidth = 1f; stroke.color = withAlpha(Night.COLOR, 0.55f)
            rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            c.drawRoundRect(rect, r, r, stroke)
            return
        }
        ensureCache(w, h, r)
        val s = spec
        // shadow (outside the shape only; baked into the bitmap)
        val shadowColor = shadowOverride ?: s.shadow
        shadowBmp?.let { if (Color.alpha(shadowColor) > 0) { bmp.color = shadowColor; c.drawBitmap(it, -shadowPad, -shadowPad, bmp) } }
        rect.set(0f, 0f, w, h)
        // backdrop
        backdrop?.let { bd ->
            if (bdFor !== bd) { bdPaint.shader = BitmapShader(bd.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); bdFor = bd }
            m.setScale(bd.screenWidth / bd.bitmap.width, bd.screenHeight / bd.bitmap.height)
            m.postTranslate(bd.screenLeft - wx, bd.screenTop - wy)
            bdPaint.shader.setLocalMatrix(m)
            c.drawRoundRect(rect, r, r, bdPaint)
        }
        fill.color = fillOverride ?: s.fill
        c.drawRoundRect(rect, r, r, fill)
        glowBmp?.let { bmp.color = s.innerGlow; c.drawBitmap(it, 0f, 0f, bmp) }
        spec2.shader = specShader
        c.drawRoundRect(rect, r, r, spec2)
        // rims: a stroke just inside the edge, faded down from the top / up from the bottom
        stroke.strokeWidth = s.rimTopWidth
        val inset = s.rimTopWidth / 2
        rect.set(inset, inset, w - inset, h - inset)
        stroke.shader = rimTopShader; stroke.color = Color.WHITE
        c.drawRoundRect(rect, r - inset, r - inset, stroke)
        if (rimBottomShader != null) {
            stroke.strokeWidth = 1f
            rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f)
            stroke.shader = rimBottomShader
            c.drawRoundRect(rect, r - 0.5f, r - 0.5f, stroke)
        }
        stroke.shader = null; stroke.strokeWidth = 0.5f; stroke.color = s.border
        rect.set(0.25f, 0.25f, w - 0.25f, h - 0.25f)
        c.drawRoundRect(rect, r, r, stroke)
    }

    private fun ensureCache(w: Float, h: Float, r: Float) {
        val s = spec
        val key = "$w/$h/$r/${System.identityHashCode(s)}"
        if (key == cacheKey) return
        cacheKey = key
        shadowBmp?.recycle(); glowBmp?.recycle(); shadowBmp = null; glowBmp = null
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        if (Color.alpha(s.shadow) > 0 || shadowOverride != null) {
            val sigma = s.shadowBlur / 2f
            shadowPad = sigma * 3 + abs(s.shadowDy)
            val bw = (w + 2 * shadowPad).toInt() + 1
            val bh = (h + 2 * shadowPad).toInt() + 1
            shadowBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8).also { b ->
                val cv = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; maskFilter = BlurMaskFilter(blurRadius(sigma), BlurMaskFilter.Blur.NORMAL) }
                cv.drawRoundRect(RectF(shadowPad, shadowPad + s.shadowDy, shadowPad + w, shadowPad + s.shadowDy + h), r, r, p)
                cv.drawRoundRect(RectF(shadowPad, shadowPad, shadowPad + w, shadowPad + h), r, r, clear)
            }
        }
        if (Color.alpha(s.innerGlow) > 0) {
            glowBmp = Bitmap.createBitmap(w.toInt() + 1, h.toInt() + 1, Bitmap.Config.ALPHA_8).also { b ->
                val cv = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; maskFilter = BlurMaskFilter(blurRadius(s.innerGlowBlur / 2f), BlurMaskFilter.Blur.INNER) }
                cv.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
            }
        }
        // CSS linear-gradient(θdeg): direction (sin θ, -cos θ), length |w sin θ| + |h cos θ|, through the centre.
        val a = Math.toRadians(s.specAngle.toDouble())
        val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
        val len = abs(w * dx) + abs(h * dy)
        val colors = IntArray(s.specAlphas.size) { withAlpha(Color.WHITE, s.specAlphas[it]) }
        specShader = LinearGradient(w / 2 - dx * len / 2, h / 2 - dy * len / 2, w / 2 + dx * len / 2, h / 2 + dy * len / 2, colors, s.specStops, Shader.TileMode.CLAMP)
        val rimH = min(h * 0.5f, r.coerceAtLeast(8f))
        rimTopShader = LinearGradient(0f, 0f, 0f, rimH, withAlpha(Color.WHITE, s.rimTop), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rimBottomShader = if (Color.alpha(s.rimBottom) == 0) null
            else LinearGradient(0f, h, 0f, h - rimH, s.rimBottom, s.rimBottom and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    /** CSS blur σ → BlurMaskFilter radius (Skia: σ = 0.57735 r + 0.5). */
    private fun blurRadius(sigma: Float) = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
}
