package app.vesper.feature.photos.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural stand-in "photos" for demo mode: Canvas ports of the CSS illustrations in
 * Photos.dc.html (coast at golden hour, granite valley in snow, lanterns at a night market),
 * plus variants (mirrored, hue-shifted, other lantern seeds). No real or copyrighted photos.
 */
object StandIns {
    enum class Kind { COAST, VALLEY, LANTERNS }

    data class Spec(val kind: Kind, val mirror: Boolean = false, val hue: Float = 0f, val seed: Int = 3)

    @Synchronized fun render(spec: Spec, w: Int = 960, h: Int = 480): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.save()
        c.scale(w / 960f, h / 480f)
        if (spec.mirror) { c.scale(-1f, 1f, 480f, 240f) }
        when (spec.kind) {
            Kind.COAST -> coast(c)
            Kind.VALLEY -> valley(c)
            Kind.LANTERNS -> lanterns(c, spec.seed)
        }
        c.restore()
        if (spec.hue == 0f) return bmp
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(hueRotate(spec.hue)) })
        bmp.recycle()
        return out
    }

    /** The design's per-photo `transform-origin` for the slow zoom. */
    fun pivot(kind: Kind): Pair<Float, Float> = when (kind) {
        Kind.COAST -> 0.60f to 0.55f
        Kind.VALLEY -> 0.40f to 0.45f
        Kind.LANTERNS -> 0.50f to 0.40f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private fun fillAll(c: Canvas, shader: Shader?, color: Int = 0) {
        paint.shader = shader; paint.color = color; paint.alpha = 255
        c.drawRect(0f, 0f, 960f, 480f, paint)
    }

    /** CSS `clip-path: polygon(x% y%, …)` over the 960×480 box. */
    private fun poly(c: Canvas, color: Int, vararg pct: Int, shader: Shader? = null) {
        val p = Path()
        for (i in pct.indices step 2) {
            val x = pct[i] * 9.6f; val y = pct[i + 1] * 4.8f
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        paint.shader = shader
        paint.color = if (shader != null) 0xFF000000.toInt() else color // a shader takes its alpha from the colour
        c.drawPath(p, paint)
    }

    private fun vertical(vararg stops: Pair<Float, Long>) =
        LinearGradient(0f, 0f, 0f, 480f, stops.map { it.second.toInt() }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)

    private fun coast(c: Canvas) {
        fillAll(c, vertical(0f to 0xFFF6C28B, 0.38f to 0xFFF09060, 0.54f to 0xFFC9604E, 0.55f to 0xFF3E5C78, 1f to 0xFF1D3550))
        // sun: 110 px circle at (600, 180), radial-gradient(circle …) reaches the box corner (r·√2)
        paint.shader = RadialGradient(655f, 235f, 55f * sqrt(2f),
            intArrayOf(0xFFFFF3D6.toInt(), 0xFFFFC36B.toInt(), 0x00FFA050), floatArrayOf(0f, 0.55f, 0.72f), Shader.TileMode.CLAMP)
        c.drawCircle(655f, 235f, 55f, paint)
        // glints on the water: 2 px lines every 13 px from y = 264
        paint.shader = null; paint.color = 0x2EFFC88C
        var y = 264f
        while (y < 480f) { c.drawRect(0f, y, 960f, y + 2f, paint); y += 13f }
        poly(c, 0xFF2A1A1E.toInt(), 0, 34, 9, 30, 18, 38, 27, 44, 33, 52, 38, 60, 44, 66, 47, 100, 0, 100)
        poly(c, 0xFF3A2426.toInt(), 0, 50, 12, 46, 22, 54, 30, 64, 35, 74, 40, 100, 0, 100)
        poly(c, 0xFF1A1012.toInt(), 78, 100, 84, 70, 90, 62, 100, 58, 100, 100)
    }

    private fun valley(c: Canvas) {
        fillAll(c, vertical(0f to 0xFF7FA6C9, 0.55f to 0xFFB9CFE0, 1f to 0xFFE4ECF2))
        poly(c, 0, 0, 100, 0, 40, 6, 22, 14, 16, 20, 20, 24, 34, 27, 60, 30, 100,
            shader = vertical(0f to 0xFF8E98A6, 1f to 0xFF5D6774))
        poly(c, 0, 52, 100, 58, 52, 64, 30, 68, 24, 72, 26, 76, 38, 84, 46, 92, 42, 100, 48, 100, 100,
            shader = vertical(0f to 0xFFA3ADBA, 1f to 0xFF6E7886))
        poly(c, 0xFFF3F6F9.toInt(), 0, 78, 20, 74, 45, 79, 70, 75, 100, 78, 100, 100, 0, 100)
        poly(c, 0xFF2D4235.toInt(), 30, 100, 33, 70, 36, 100, 38, 100, 41, 64, 44, 100, 47, 100, 50, 72, 53, 100)
    }

    private val LANTERN_COLORS = longArrayOf(0xFFFFB340, 0xFFFF6B4A, 0xFFFFD27A, 0xFFFF3B6B)

    private fun lanterns(c: Canvas, seed0: Int) {
        // radial-gradient(ellipse 80% 70% at 50% 30%, #3A1830 0%, #12070F 70%)
        val bg = RadialGradient(480f, 144f, 768f, intArrayOf(0xFF3A1830.toInt(), 0xFF12070F.toInt()), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        bg.setLocalMatrix(Matrix().apply { setScale(1f, 336f / 768f, 480f, 144f) })
        fillAll(c, bg)
        // The design's LCG: seed = (seed * 9301 + 49297) % 233280
        var seed = seed0.toLong()
        fun rnd(): Double { seed = (seed * 9301 + 49297) % 233280; return seed / 233280.0 }
        repeat(16) {
            val x = (rnd() * 960 - 60).roundToInt().toFloat()
            val y = (rnd() * 360 - 40).roundToInt().toFloat()
            val s = (60 + rnd() * 140).roundToInt().toFloat()
            val col = LANTERN_COLORS[(rnd() * 4).toInt()].toInt()
            val o = ((0.45 + rnd() * 0.5) * 100).roundToInt() / 100f
            val r = s / 2f
            val clear = col and 0x00FFFFFF // fade to the same colour, as CSS does in premultiplied space
            // Centre nudged off the pixel centre: Skia's raster radial gradient leaves a dark
            // pixel where the distance is exactly 0.
            paint.shader = RadialGradient(x + r + 0.31f, y + r + 0.27f, r * sqrt(2f), intArrayOf(col, clear), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
            paint.alpha = (o * 255).roundToInt()
            c.drawCircle(x + r, y + r, r, paint)
            paint.alpha = 255
        }
    }

    /** CSS `hue-rotate(deg)`. */
    private fun hueRotate(deg: Float): ColorMatrix {
        val a = Math.toRadians(deg.toDouble())
        val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + cs * 0.787f - sn * 0.213f, 0.715f - cs * 0.715f - sn * 0.715f, 0.072f - cs * 0.072f + sn * 0.928f, 0f, 0f,
            0.213f - cs * 0.213f + sn * 0.143f, 0.715f + cs * 0.285f + sn * 0.140f, 0.072f - cs * 0.072f - sn * 0.283f, 0f, 0f,
            0.213f - cs * 0.213f - sn * 0.787f, 0.715f - cs * 0.715f + sn * 0.715f, 0.072f + cs * 0.928f + sn * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }
}
