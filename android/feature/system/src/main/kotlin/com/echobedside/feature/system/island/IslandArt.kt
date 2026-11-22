package com.echobedside.feature.system.island

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import com.echobedside.core.design.Night
import kotlin.math.max

/** Small drawing pieces for the island (artwork, timer ring, orb, the doorbell stand-in). */
internal class IslandArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val clip = Path()
    private val m = Matrix()
    private var shaderFor: Bitmap? = null
    private val sweepColors = intArrayOf(0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt())
    private var sweep: SweepGradient? = null
    private var sweepSize = 0f

    /** Album art at (x,y) [size] with corner [radius]; [art] null → the designs' sunset stand-in. */
    fun artwork(c: Canvas, art: Bitmap?, x: Float, y: Float, size: Float, radius: Float, alpha: Float, night: Boolean) {
        r.set(x, y, x + size, y + size)
        if (art != null && !art.isRecycled) {
            if (shaderFor !== art) { bmpPaint.shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderFor = art }
            val s = max(size / art.width, size / art.height)
            m.setScale(s, s)
            m.postTranslate(x + (size - art.width * s) / 2f, y + (size - art.height * s) / 2f)
            bmpPaint.shader.setLocalMatrix(m)
            bmpPaint.alpha = (255 * alpha).toInt()
            bmpPaint.colorFilter = if (night) android.graphics.PorterDuffColorFilter(Night.COLOR, android.graphics.PorterDuff.Mode.MULTIPLY) else null
            c.drawRoundRect(r, radius, radius, bmpPaint)
            return
        }
        // linear-gradient(180deg, #17485A 0%, #B9654E 44%, #F7B98A 58%) + sea + sun
        c.save()
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(clip)
        val a = (255 * alpha).toInt()
        if (night) {
            p.shader = null; p.color = Night.COLOR; p.alpha = (a * 0.6f).toInt(); c.drawRect(r, p)
        } else {
            p.shader = LinearGradient(0f, y, 0f, y + size, intArrayOf(0xFF17485A.toInt(), 0xFFB9654E.toInt(), 0xFFF7B98A.toInt(), 0xFFF7B98A.toInt()),
                floatArrayOf(0f, 0.44f, 0.58f, 1f), Shader.TileMode.CLAMP)
            p.alpha = a
            c.drawRect(r, p)
            val big = size > 60
            val seaTop = y + size * (if (big) 67f / 116f else 16f / 28f)
            p.shader = if (big) LinearGradient(0f, seaTop, 0f, y + size, 0xFF0F3E48.toInt(), 0xFF06191E.toInt(), Shader.TileMode.CLAMP) else null
            if (!big) p.color = 0xFF0F3E48.toInt()
            p.alpha = a
            c.drawRect(x, seaTop, x + size, y + size, p)
            val sunD = size * (if (big) 46f / 116f else 12f / 28f)
            val sunX = x + size * (if (big) 35f / 116f else 8f / 28f)
            val sunY = y + size * (if (big) 30f / 116f else 7f / 28f)
            p.shader = if (big) LinearGradient(0f, sunY, 0f, sunY + sunD, 0xFFFFE7BC.toInt(), 0xFFFF6A3D.toInt(), Shader.TileMode.CLAMP) else null
            if (!big) p.color = 0xFFFFB36B.toInt()
            p.alpha = a
            c.drawCircle(sunX + sunD / 2, sunY + sunD / 2, sunD / 2, p)
            p.shader = null
        }
        c.restore()
    }

    /** The 28 px timer ring: r 10.5, stroke 3.5, track 30 % orange, progress from 12 o'clock. */
    fun ring(c: Canvas, cx: Float, cy: Float, size: Float, fraction: Float, color: Int, alpha: Float) {
        val k = size / 28f
        val rad = 10.5f * k
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3.5f * k
        p.strokeCap = Paint.Cap.ROUND
        p.color = color; p.alpha = (255 * 0.3f * alpha * Color.alpha(color) / 255f).toInt()
        c.drawCircle(cx, cy, rad, p)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.001f) {
            p.color = color; p.alpha = (alpha * Color.alpha(color)).toInt()
            r.set(cx - rad, cy - rad, cx + rad, cy + rad)
            c.drawArc(r, -90f, 360f * f, false, p)
        }
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.BUTT
    }

    /** Assistant orb: a rotating conic gradient in a circle, with an inset top rim. */
    fun orb(c: Canvas, cx: Float, cy: Float, size: Float, spinDeg: Float, alpha: Float, night: Boolean) {
        val rad = size / 2f
        if (night) {
            p.shader = null; p.color = Night.COLOR; p.alpha = (255 * alpha).toInt()
            c.drawCircle(cx, cy, rad, p); return
        }
        if (sweep == null || sweepSize != size) { sweep = SweepGradient(0f, 0f, sweepColors, null); sweepSize = size }
        m.setRotate(spinDeg - 90f)
        m.postTranslate(cx, cy)
        sweep!!.setLocalMatrix(m)
        p.shader = sweep
        p.alpha = (255 * alpha).toInt()
        c.drawCircle(cx, cy, rad, p)
        p.shader = LinearGradient(0f, cy - rad, 0f, cy - rad + 4f, Color.argb((153 * alpha).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
        c.drawCircle(cx, cy, rad - 0.75f, p)
        p.style = Paint.Style.FILL; p.shader = null
    }

    /** The doorbell camera stand-in (Island.dc.html): porch, door, lamp, LIVE chip drawn by the caller. */
    fun porch(c: Canvas, snapshot: Bitmap?, x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float, night: Boolean) {
        r.set(x, y, x + w, y + h)
        c.save()
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW); c.clipPath(clip)
        val a = (255 * alpha).toInt()
        if (snapshot != null && !snapshot.isRecycled) {
            if (shaderFor !== snapshot) { bmpPaint.shader = BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderFor = snapshot }
            val s = max(w / snapshot.width, h / snapshot.height)
            m.setScale(s, s); m.postTranslate(x + (w - snapshot.width * s) / 2f, y + (h - snapshot.height * s) / 2f)
            bmpPaint.shader.setLocalMatrix(m); bmpPaint.alpha = a
            c.drawRect(r, bmpPaint)
        } else if (night) {
            p.shader = null; p.color = Night.COLOR; p.alpha = (a * 0.35f).toInt(); c.drawRect(r, p)
        } else {
            p.shader = LinearGradient(0f, y, 0f, y + h, intArrayOf(0xFF2B3340.toInt(), 0xFF1A1F28.toInt(), 0xFF3A3229.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            p.alpha = a; c.drawRect(r, p)
            // door: left 86 top 22 58×130, radius 6 6 0 0
            p.shader = LinearGradient(0f, y + 22, 0f, y + 152, 0xFF5A4636.toInt(), 0xFF3B2D22.toInt(), Shader.TileMode.CLAMP); p.alpha = a
            val d = RectF(x + 86, y + 22, x + 144, y + 152)
            c.drawRoundRect(d, 6f, 6f, p); c.drawRect(d.left, d.top + 6, d.right, d.bottom, p)
            // lamp glow: 14 px, box-shadow 0 0 22px 8px rgba(255,200,110,.55)
            p.shader = android.graphics.RadialGradient(x + 157, y + 47, 26f, intArrayOf(Color.argb((140 * alpha).toInt(), 255, 200, 110), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(x + 157, y + 47, 26f, p)
            p.shader = null; p.color = 0xFFFFD27A.toInt(); p.alpha = a
            c.drawCircle(x + 157, y + 47, 7f, p)
            // ground: bottom 30 px
            p.shader = LinearGradient(0f, y + h - 30, 0f, y + h, 0xFF4A4036.toInt(), 0xFF2C251F.toInt(), Shader.TileMode.CLAMP); p.alpha = a
            c.drawRect(x, y + h - 30, x + w, y + h, p)
            p.shader = null
        }
        c.restore()
    }
}
