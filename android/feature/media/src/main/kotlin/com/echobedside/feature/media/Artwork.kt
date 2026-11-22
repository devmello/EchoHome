package com.echobedside.feature.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Artwork is drawn at 324×324 (NowPlaying.dc.html); every source is scaled to this once. */
const val ART_PX = 324

object Artwork {
    /** Centre-crops [src] to a square [size] ARGB bitmap (a new bitmap; [src] is untouched). */
    fun centerCrop(src: Bitmap, size: Int = ART_PX): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((size - src.width * s) / 2f, (size - src.height * s) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** ~32×32 pixels for [PaletteExtractor]. */
    fun samplePixels(src: Bitmap, size: Int = 32): IntArray {
        val small = Bitmap.createScaledBitmap(src, size, size, true)
        val px = IntArray(size * size)
        small.getPixels(px, 0, size, 0, 0, size, size)
        if (small !== src) small.recycle()
        return px
    }
}

/**
 * Procedural stand-ins for the design's three sample albums (original art from
 * NowPlaying.dc.html, redrawn with gradients; no real album covers). Unknown albums get a
 * gradient from a hue hashed from the title.
 */
object DemoArt {
    private val samples = HashMap<String, Bitmap>()
    /** Fallback covers for real sources without art (Bluetooth): only the last few (420 KB each). */
    private val generic = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 3
    }

    @Synchronized
    fun forAlbum(album: String, title: String): Bitmap = when (album) {
        "Coastline Hours" -> samples.getOrPut(album) { coastline() }
        "Small Orbits" -> samples.getOrPut(album) { orbits() }
        "Night Swim" -> samples.getOrPut(album) { nightSwim() }
        else -> album.ifBlank { title }.let { k -> generic.getOrPut(k) { generic(k) } }
    }

    private val S = ART_PX.toFloat()
    private fun canvas(): Pair<Bitmap, Canvas> = Bitmap.createBitmap(ART_PX, ART_PX, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }
    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private fun c(hex: Long) = hex.toInt()

    /** CSS `box-shadow: 0 0 Bpx` → Android shadow radius (sigma = B/2, radius ≈ (sigma - 0.5)/0.577). */
    private fun glow(blurPx: Float) = ((blurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(1f)

    private fun label(cv: Canvas, text: String, x: Float, top: Float, color: Int) {
        val t = SpecText(11f, 700, 0.22f)
        t.draw(cv, text, x, t.baselineIn(top), color)
    }

    private fun coastline(): Bitmap {
        val (b, cv) = canvas()
        val p = paint()
        p.shader = LinearGradient(0f, 0f, 0f, S, intArrayOf(c(0xFF17485A), c(0xFFB9654E), c(0xFFF7B98A), c(0xFFF7B98A)),
            floatArrayOf(0f, 0.44f, 0.58f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, S, S, p)
        // Sun with glow.
        p.shader = LinearGradient(0f, 92f, 0f, 222f, intArrayOf(c(0xFFFFE7BC), c(0xFFFF8A4C), c(0xFFFF6A3D)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        p.setShadowLayer(glow(60f), 0f, 0f, withAlpha(c(0xFFFF9650), 0.65f))
        cv.drawCircle(162f, 157f, 65f, p)
        p.clearShadowLayer()
        // Water.
        p.shader = LinearGradient(0f, 188f, 0f, S, c(0xFF0F3E48), c(0xFF06191E), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 188f, S, S, p)
        // Reflection: 3 px stripes every 11 px, masked by an ellipse fading from the top centre.
        val r = RectF(92f, 192f, 232f, 296f)
        val layer = cv.saveLayer(r, null)
        p.shader = null
        p.color = withAlpha(c(0xFFFFA05F), 0.8f)
        var y = r.top
        while (y < r.bottom) { cv.drawRect(r.left, y, r.right, minOf(y + 3f, r.bottom), p); y += 11f }
        val mask = paint()
        mask.shader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.2f, 0.8f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(r.width() / 2f, r.height()); postTranslate(r.centerX(), r.top) })
        }
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        cv.drawRect(r, mask)
        cv.restoreToCount(layer)
        label(cv, "COASTLINE HOURS", 22f, 20f, withAlpha(c(0xFFFFF0E1), 0.9f))
        return b
    }

    private fun orbits(): Bitmap {
        val (b, cv) = canvas()
        val p = paint()
        val cx = 0.28f * S; val cy = 0.26f * S
        p.shader = RadialGradient(cx, cy, hypot(S - cx, S - cy), intArrayOf(c(0xFF6A34B8), c(0xFF2A0F57), c(0xFF12052A)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, S, S, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        for ((size, a) in listOf(260f to 0.35f, 190f to 0.55f, 120f to 0.75f)) {
            p.color = withAlpha(c(0xFFFFAADC), a)
            cv.drawCircle(192f, 192f, size / 2f - 0.75f, p)
        }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(157f + 0.35f * 70f, 157f + 0.3f * 70f, hypot(0.65f * 70f, 0.7f * 70f),
            intArrayOf(c(0xFFFFC2DD), c(0xFFFF4F9A), c(0xFFB21E62)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        p.setShadowLayer(glow(40f), 0f, 0f, withAlpha(c(0xFFFF4F9A), 0.6f))
        cv.drawCircle(192f, 192f, 35f, p)
        p.shader = null
        p.color = Color.WHITE
        p.setShadowLayer(glow(14f), 0f, 0f, withAlpha(Color.WHITE, 0.9f))
        cv.drawCircle(279f, 131f, 7f, p)
        p.clearShadowLayer()
        val t = SpecText(11f, 700, 0.22f)
        label(cv, "SMALL ORBITS", 22f, S - 20f - t.normalLineHeight, withAlpha(c(0xFFFFDCF0), 0.9f))
        return b
    }

    private fun nightSwim(): Bitmap {
        val (b, cv) = canvas()
        val p = paint()
        // linear-gradient(165deg): direction (sin, -cos) of 165°.
        val ang = Math.toRadians(165.0)
        val dx = sin(ang).toFloat(); val dy = -cos(ang).toFloat()
        val len = S * (kotlin.math.abs(dx) + kotlin.math.abs(dy))
        p.shader = LinearGradient(S / 2 - dx * len / 2, S / 2 - dy * len / 2, S / 2 + dx * len / 2, S / 2 + dy * len / 2,
            intArrayOf(c(0xFF0D2A66), c(0xFF050D26), c(0xFF02050F)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, S, S, p)
        // Moon: box-shadow 0 0 50px 8px → a spread circle with a glow.
        p.shader = null
        p.color = Color.TRANSPARENT
        p.setShadowLayer(glow(50f), 0f, 0f, withAlpha(c(0xFFA0E1FF), 0.45f))
        p.color = withAlpha(c(0xFFA0E1FF), 0.01f)
        cv.drawCircle(245f, 77f, 31f + 8f, p)
        p.clearShadowLayer()
        p.color = c(0xFFEAF7FF)
        cv.drawCircle(245f, 77f, 31f, p)
        // Ripples: repeating-radial-gradient(ellipse 70% 40% at 50% 120%) in x -40..364, y 150..364,
        // a 3 px ring every 19 px, faded in from the top (mask 0 → 35%).
        val r = RectF(-40f, 150f, S + 40f, S + 40f)
        val rx = 0.7f * r.width(); val ry = 0.4f * r.height()
        val ecx = r.centerX(); val ecy = r.top + 1.2f * r.height()
        val layer = cv.saveLayer(0f, r.top, S, S, null)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = withAlpha(c(0xFF3FD0FF), 0.6f)
        cv.save()
        cv.translate(ecx, ecy)
        cv.scale(1f, ry / rx)
        val farthest = hypot(r.width() / 2f, (ecy - r.top) * rx / ry)
        var rad = 17.5f
        while (rad < farthest + 19f) { cv.drawCircle(0f, 0f, rad, p); rad += 19f }
        cv.restore()
        val mask = paint()
        mask.shader = LinearGradient(0f, r.top, 0f, r.top + 0.35f * r.height(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        mask.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        cv.drawRect(0f, r.top, S, S, mask)
        cv.restoreToCount(layer)
        label(cv, "NIGHT SWIM", 22f, 20f, withAlpha(c(0xFFDCF5FF), 0.9f))
        return b
    }

    private fun generic(key: String): Bitmap {
        val (b, cv) = canvas()
        val hue = ((key.hashCode() and 0x7fffffff) % 360).toFloat()
        val p = paint()
        p.shader = LinearGradient(0f, 0f, S, S, hslToRgb(hue, 0.55f, 0.32f), hslToRgb(hue + 40f, 0.6f, 0.12f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, S, S, p)
        p.shader = RadialGradient(S * 0.7f, S * 0.3f, S * 0.5f, withAlpha(hslToRgb(hue - 30f, 0.8f, 0.6f), 0.7f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, S, S, p)
        return b
    }
}
