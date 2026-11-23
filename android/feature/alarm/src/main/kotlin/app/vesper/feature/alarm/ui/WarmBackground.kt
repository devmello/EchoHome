package app.vesper.feature.alarm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import app.vesper.core.design.withAlpha
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The warm gradient of Timers.dc.html, shared by the Timers and Alarms screens. */
object WarmBackground {
    /** The board's layered background, rendered once at 960×480. */
    fun render(): Bitmap {
        val w = 960f; val h = 480f
        val b = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        fun fill() = c.drawRect(0f, 0f, w, h, p)
        // bottom layer first (CSS lists top first)
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF3A160E.toInt(), 0xFF12070A.toInt(), Shader.TileMode.CLAMP); fill()
        fun radial(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, end: Float) {
            p.shader = RadialGradient(0f, 0f, rx, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, end), Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx); postTranslate(cx, cy) }) }
            fill()
        }
        radial(0.55f * w, 1.05f * h, 0.70f * w, 0.70f * h, withAlpha(0xFF7828A0.toInt(), 0.7f), 0.72f)
        radial(0.90f * w, 0.25f * h, 0.55f * w, 0.65f * h, withAlpha(0xFFFF465A.toInt(), 0.6f), 0.7f)
        radial(0.15f * w, 0.15f * h, 0.55f * w, 0.70f * h, withAlpha(0xFFFF963C.toInt(), 0.8f), 0.7f)
        fun band(color: Int, a: Float, b0: Float, mid: Float, b1: Float) {
            val ang = Math.toRadians(118.0)
            val dx = sin(ang).toFloat(); val dy = -cos(ang).toFloat()
            val len = abs(w * dx) + abs(h * dy)
            val c0 = color and 0x00FFFFFF
            p.shader = LinearGradient(w / 2 - dx * len / 2, h / 2 - dy * len / 2, w / 2 + dx * len / 2, h / 2 + dy * len / 2,
                intArrayOf(c0, c0, withAlpha(color, a), c0, c0), floatArrayOf(0f, b0, mid, b1, 1f), Shader.TileMode.CLAMP)
            fill()
        }
        band(0xFFFF5A46.toInt(), 0.38f, 0.56f, 0.66f, 0.76f)
        band(0xFFFFBE5A.toInt(), 0.4f, 0.22f, 0.34f, 0.46f)
        return b
    }
}
