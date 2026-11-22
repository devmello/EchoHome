package app.vesper.feature.system.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.withAlpha
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The glass recipe (NOTES §3.2) with the per-surface values the system designs use
 * (HUD pill, banner, island, Control Center tiles differ slightly from the core tokens:
 * border .3 vs .22, rim 1.5 px .5 vs 1 px .42, 170° vs 160° specular, bigger shadows).
 * `GlassPainter` in :core:design has the tokens fixed, so this is a parameterised copy
 * (contract request filed to add these parameters to core). No live blur: [backdrop] is a
 * pre-blurred snapshot, the shadow is a pre-blurred 9-slice, so per-frame cost is a few draws.
 */
data class GlassSpec(
    val fill: Int = withAlpha(Color.WHITE, 0.09f),
    val borderAlpha: Float = 0.22f,
    val rimTopAlpha: Float = 0.45f,
    val rimTopWidth: Float = 1.5f,
    val rimBottomAlpha: Float = 0.08f,
    val specularAngle: Float = 160f,
    val specularStops: FloatArray = floatArrayOf(0f, 0.38f, 0.62f),
    val specularAlphas: FloatArray = floatArrayOf(0.22f, 0.03f, 0f),
    val shadowDy: Float = 14f,
    val shadowBlur: Float = 36f,
    val shadowAlpha: Float = 0.4f,
) {
    companion object {
        /** HUDs.dc.html pill: rgba(255,255,255,.12), border .3, rim 1.5 px .5, 170° .3→.04 45%→0 70%, 0 14 40 .5. */
        val HUD = GlassSpec(withAlpha(Color.WHITE, 0.12f), 0.3f, 0.5f, 1.5f, 0.1f, 170f,
            floatArrayOf(0f, 0.45f, 0.7f), floatArrayOf(0.3f, 0.04f, 0f), 14f, 40f, 0.5f)
        /** Banners.dc.html compact banner: .12, border .3, rim .5, 170° .26→.03 45%→0 70%, 0 16 44 .45. */
        val BANNER = GlassSpec(withAlpha(Color.WHITE, 0.12f), 0.3f, 0.5f, 1.5f, 0.1f, 170f,
            floatArrayOf(0f, 0.45f, 0.7f), floatArrayOf(0.26f, 0.03f, 0f), 16f, 44f, 0.45f)
        /** The tucked-behind previous banner: .07, border .16, no rim/specular/shadow. */
        val BANNER_BACK = GlassSpec(withAlpha(Color.WHITE, 0.07f), 0.16f, 0f, 1.5f, 0f, 170f,
            floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), 0f, 0f, 0f)
        /** Island "Liquid Glass": rgba(18,18,24,.42), border .28, rim .45, 170° .2→.02 40%→0 70%, 0 14 40 .5. */
        val ISLAND = GlassSpec(Color.argb(107, 18, 18, 24), 0.28f, 0.45f, 1.5f, 0.08f, 170f,
            floatArrayOf(0f, 0.4f, 0.7f), floatArrayOf(0.2f, 0.02f, 0f), 14f, 40f, 0.5f)
        /** Island "Black": #000, border .14, no rim/specular, 0 10 30 .55. */
        val ISLAND_BLACK = GlassSpec(Color.BLACK, 0.14f, 0f, 1.5f, 0f, 170f,
            floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), 10f, 30f, 0.55f)
        /** ControlCenter.dc.html tiles: .09, border .22, rim 1.5 px .45, 160° .22→.03 38%→0 62%, 0 14 36 .4. */
        val CC_TILE = GlassSpec()
        /** Control Center round buttons (settings): .1, border .24, rim .45, 0 8 24 .35. */
        val CC_BUTTON = GlassSpec(withAlpha(Color.WHITE, 0.1f), 0.24f, 0.45f, 1f, 0f, 160f,
            floatArrayOf(0f, 0.38f, 0.62f), floatArrayOf(0.22f, 0.03f, 0f), 8f, 24f, 0.35f)
        /** Big buttons in the expanded banner: border .24/.3, rim .45/.5, 170° .26→0 55%, 0 14 36 .45. */
        val BANNER_BUTTON = GlassSpec(withAlpha(Color.WHITE, 0.1f), 0.24f, 0.45f, 1.5f, 0f, 170f,
            floatArrayOf(0f, 0.55f), floatArrayOf(0.26f, 0f), 14f, 36f, 0.45f)
        val BANNER_PRIMARY = BANNER_BUTTON.copy(fill = Color.argb(158, 10, 132, 255), borderAlpha = 0.3f, rimTopAlpha = 0.5f,
            specularAlphas = floatArrayOf(0.3f, 0f))
    }
}

class SysGlassPainter(var spec: GlassSpec) {
    var backdrop: Backdrop? = null
    /** Overrides [GlassSpec.fill] (e.g. the HUD pill's red/green message tints). */
    var fillOverride: Int? = null
    var alpha = 1f
    var night = false
    /** Draw the lift shadow. Off over pure black, where it can't be seen. */
    var shadow = true

    private val rect = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f }
    private val matrix = Matrix()
    private var shaderFor: Bitmap? = null
    private var specKey = Long.MIN_VALUE
    private var rimTop: Shader? = null
    private var rimBottom: Shader? = null
    private val shaderCache = HashMap<Long, Triple<Shader?, Shader, Shader>>()

    private fun buildShaders(key: Long, w: Float, h: Float, a: Float, s: GlassSpec) {
        val ang = Math.toRadians(s.specularAngle.toDouble())
        val dx = sin(ang).toFloat()
        val dy = -cos(ang).toFloat()
        // CSS gradient line length for this angle
        val len = abs(w * dx) + abs(h * dy)
        val cx = w / 2f; val cy = h / 2f
        val colors = IntArray(s.specularAlphas.size) { withAlpha(Color.WHITE, s.specularAlphas[it] * a) }
        val sp = if (s.specularAlphas.all { it == 0f }) null else
            LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, colors, s.specularStops, Shader.TileMode.CLAMP)
        val rimH = min(h * 0.5f, 18f)
        val rt = LinearGradient(0f, 0f, 0f, rimH, withAlpha(Color.WHITE, s.rimTopAlpha * a), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        val rb = LinearGradient(0f, h, 0f, h - rimH, withAlpha(Color.WHITE, s.rimBottomAlpha * a), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        specPaint.shader = sp; rimTop = rt; rimBottom = rb
        // animating sizes/alphas (island morph, fades) would grow it without bound: keep it small
        if (shaderCache.size >= 24) shaderCache.clear()
        shaderCache[key] = Triple(sp, rt, rb)
    }

    /**
     * Draws glass in [l,t,r,b] with corner [radius] (-1 = fully round). [windowX]/[windowY] = where the
     * canvas origin is in the window (to line the backdrop up).
     */
    /**
     * [parts]: [SHADOW], [BACKDROP], [SURFACE] (fill, specular, rims, border) or [ALL]. Drawing the parts
     * one after the other into separate RenderNodes looks the same as [ALL] and lets a new backdrop
     * re-record only the backdrop part (Control Center).
     */
    fun draw(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float = -1f, windowX: Float = 0f, windowY: Float = 0f, parts: Int = ALL) {
        val w = r - l
        val h = b - t
        if (w <= 0.5f || h <= 0.5f || alpha <= 0.004f) return
        val rad = if (radius < 0) min(w, h) / 2f else min(radius, min(w, h) / 2f)
        val a = alpha
        val s = spec
        rect.set(l, t, r, b)
        if (night) {
            if (parts and SURFACE == 0) return
            fillPaint.color = withAlpha(Color.BLACK, a)
            canvas.drawRoundRect(rect, rad, rad, fillPaint)
            borderPaint.color = withAlpha(Night.COLOR, 0.55f * a)
            borderPaint.strokeWidth = 1f
            rect.inset(0.5f, 0.5f)
            canvas.drawRoundRect(rect, rad - 0.5f, rad - 0.5f, borderPaint)
            borderPaint.strokeWidth = 0.5f
            return
        }
        if (parts and SHADOW != 0 && shadow && s.shadowAlpha > 0f) ShadowSlice.draw(canvas, rect, rad, s.shadowDy, s.shadowBlur, s.shadowAlpha * a)

        val bd = backdrop
        if (parts and BACKDROP != 0 && bd != null && !bd.bitmap.isRecycled) {
            if (shaderFor !== bd.bitmap) {
                backdropPaint.shader = BitmapShader(bd.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderFor = bd.bitmap
            }
            matrix.setScale(bd.screenWidth / bd.bitmap.width, bd.screenHeight / bd.bitmap.height)
            matrix.postTranslate(bd.screenLeft - windowX, bd.screenTop - windowY)
            backdropPaint.shader.setLocalMatrix(matrix)
            backdropPaint.alpha = (255 * a).roundToInt()
            canvas.drawRoundRect(rect, rad, rad, backdropPaint)
        }
        if (parts and SURFACE == 0) return

        val fill = fillOverride ?: s.fill
        fillPaint.color = withAlpha(fill, Color.alpha(fill) / 255f * a)
        canvas.drawRoundRect(rect, rad, rad, fillPaint)

        val key = (w.roundToInt().toLong() shl 42) or (h.roundToInt().toLong() shl 21) or (a * 1000).roundToInt().toLong() or (s.hashCode().toLong() shl 50)
        if (key != specKey) {
            specKey = key
            // one painter often draws many tile sizes (Control Center): keep each size's shaders
            val cached = shaderCache[key]
            if (cached != null) { specPaint.shader = cached.first; rimTop = cached.second; rimBottom = cached.third }
            else buildShaders(key, w, h, a, s)
        }
        canvas.save()
        canvas.translate(l, t)
        if (specPaint.shader != null) { rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, rad, rad, specPaint) }
        if (s.rimTopAlpha > 0f || s.rimBottomAlpha > 0f) {
            val sw = s.rimTopWidth
            rimPaint.strokeWidth = sw
            rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            if (s.rimTopAlpha > 0f) { rimPaint.shader = rimTop; canvas.drawRoundRect(rect, rad - sw / 2, rad - sw / 2, rimPaint) }
            if (s.rimBottomAlpha > 0f) { rimPaint.shader = rimBottom; canvas.drawRoundRect(rect, rad - sw / 2, rad - sw / 2, rimPaint) }
        }
        borderPaint.color = withAlpha(Color.WHITE, s.borderAlpha * a)
        rect.set(0.25f, 0.25f, w - 0.25f, h - 0.25f)
        canvas.drawRoundRect(rect, rad, rad, borderPaint)
        canvas.restore()
    }

    companion object {
        const val SHADOW = 1
        const val BACKDROP = 2
        const val SURFACE = 4
        const val ALL = 7
    }
}

/**
 * CSS `box-shadow: 0 dy blur rgba(0,0,0,a)` for rounded rects without per-frame blurring:
 * one blurred quarter-capsule is rendered per blur size, then drawn as a 9-slice around the shape
 * (corners scaled to the radius). Cheap enough to animate (the island morph).
 */
object ShadowSlice {
    private const val R = 64 // reference corner radius in the cached bitmap
    private val cache = HashMap<Int, Bitmap>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val clip = Path()

    private fun bitmapFor(blur: Int): Bitmap = cache.getOrPut(blur) {
        // CSS blur radius ≈ 2σ; the mask is a 2R×2R rounded square with σ padding on each side.
        val sigma = blur / 2f
        val pad = (sigma * 3).roundToInt()
        val size = 2 * R + 2 * pad
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val inside = run {
                val fx = x + 0.5f - pad; val fy = y + 0.5f - pad
                if (fx < 0 || fy < 0 || fx > 2 * R || fy > 2 * R) false else {
                    val dx = fx - R; val dy = fy - R
                    dx * dx + dy * dy <= R * R
                }
            }
            px[y * size + x] = if (inside) 0xFF000000.toInt() else 0
        }
        PreBlur.gaussian(px, size, size, sigma)
        Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun draw(canvas: Canvas, shape: RectF, radius: Float, dy: Float, blur: Float, alpha: Float) {
        if (alpha <= 0.004f || blur <= 0f) return
        val bmp = bitmapFor(blur.roundToInt())
        val pad = (bmp.width - 2 * R) / 2
        val sPad = pad.toFloat()
        paint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
        val l = shape.left; val t = shape.top + dy; val r = shape.right; val b = shape.bottom + dy
        val c = radius
        val bw = bmp.width; val bh = bmp.height
        val cs = pad + R // source corner size (pad + radius)
        // destination corner extents: blur pad + scaled radius
        val dl = l - sPad; val dt = t - sPad; val dr = r + sPad; val db = b + sPad
        val cxl = l + c; val cxr = r - c; val cyt = t + c; val cyb = b - c
        if (cxr < cxl || cyb < cyt) return
        // outer shadow only (CSS box-shadow never draws under the element)
        clip.reset(); clip.addRoundRect(shape, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(clip)
        fun slice(sl: Int, st: Int, sr: Int, sb: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            if (x1 <= x0 || y1 <= y0) return
            src.set(sl, st, sr, sb); dst.set(x0, y0, x1, y1)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        // corners
        slice(0, 0, cs, cs, dl, dt, cxl, cyt)
        slice(bw - cs, 0, bw, cs, cxr, dt, dr, cyt)
        slice(0, bh - cs, cs, bh, dl, cyb, cxl, db)
        slice(bw - cs, bh - cs, bw, bh, cxr, cyb, dr, db)
        // edges (1 px wide middle column/row of the source, stretched)
        slice(cs - 1, 0, cs, cs, cxl, dt, cxr, cyt)
        slice(cs - 1, bh - cs, cs, bh, cxl, cyb, cxr, db)
        slice(0, cs - 1, cs, cs, dl, cyt, cxl, cyb)
        slice(bw - cs, cs - 1, bw, cs, cxr, cyt, dr, cyb)
        // centre (mostly hidden under the shape, but visible at the bottom where dy shifts it)
        slice(cs - 1, cs - 1, cs, cs, cxl, cyt, cxr, cyb)
        canvas.restore()
    }
}
