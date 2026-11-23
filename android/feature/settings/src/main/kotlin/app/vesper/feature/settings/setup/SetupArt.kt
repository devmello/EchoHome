package app.vesper.feature.settings.setup

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.Backdrop
import app.vesper.core.design.PreBlur
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha

/**
 * The setup's wallpaper: soft colour fields on near-black, drawn once (no image asset) and
 * pre-blurred once for the glass (NOTES §5.2). It's wider than the screen: each step shifts it
 * [PARALLAX] px to the left, so moving through the setup drifts the light a little.
 */
internal object SetupWallpaper {
    const val PARALLAX = 22f
    val WIDTH = (960 + PARALLAX * (SetupStep.entries.size - 1)).toInt()
    const val HEIGHT = 480

    class Art(val bitmap: Bitmap, val backdrop: Backdrop) {
        fun recycle() { bitmap.recycle(); backdrop.bitmap.recycle() }
    }

    /** ~15 ms on the Echo; call it off the main thread. RGB_565 with dithering: no alpha needed. */
    fun render(): Art {
        val bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.drawColor(0xFF05070D.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        fun blob(cx: Float, cy: Float, r: Float, color: Int, a: Float) {
            p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, a), withAlpha(color, a * 0.42f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
        blob(150f, 70f, 470f, VesperColors.BLUE, 0.62f)
        blob(620f, -20f, 420f, VesperColors.PURPLE, 0.44f)
        blob(1010f, 300f, 460f, VesperColors.PINK, 0.36f)
        blob(380f, 540f, 400f, VesperColors.CYAN, 0.30f)
        blob(1120f, 30f, 300f, VesperColors.ORANGE, 0.26f)
        // A light scrim baked in, so white type reads anywhere and the blurred copy matches.
        p.shader = null
        p.color = withAlpha(Color.BLACK, 0.22f)
        c.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), p)
        return Art(bmp, PreBlur.backdrop(bmp, WIDTH.toFloat(), HEIGHT.toFloat()))
    }
}

/** Line glyphs for the setup, in a 24-unit box like the designs' icons (stroke 2.2, round caps). */
internal object Glyph {
    enum class Kind { WIFI, NAME, LOCATION, PHONE, PERSON, CLOCK, SWIPE, PULL, SPARKLE, CHECK, LEFT, RIGHT }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    /** Main thread only (shared paint and path). */
    fun draw(c: Canvas, kind: Kind, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        path.reset()
        when (kind) {
            Kind.WIFI -> {
                for (r in floatArrayOf(4f, 8f, 12f)) {
                    oval.set(12f - r, 19.5f - r, 12f + r, 19.5f + r)
                    c.drawArc(oval, 225f, 90f, false, paint)
                }
                fillCircle(c, 12f, 19.5f, 1.6f)
            }
            Kind.NAME -> { // AirPlay: a screen with a triangle under it
                path.moveTo(7.5f, 16f); path.lineTo(3f, 16f); path.lineTo(3f, 4f); path.lineTo(21f, 4f); path.lineTo(21f, 16f); path.lineTo(16.5f, 16f)
                c.drawPath(path, paint)
                path.reset(); path.moveTo(12f, 13.5f); path.lineTo(16.8f, 20.5f); path.lineTo(7.2f, 20.5f); path.close()
                paint.style = Paint.Style.FILL; c.drawPath(path, paint)
            }
            Kind.LOCATION -> {
                path.moveTo(12f, 21.5f); path.lineTo(6.13f, 13.81f)
                oval.set(5f, 3f, 19f, 17f); path.arcTo(oval, 147f, 246f, false)
                path.close()
                c.drawPath(path, paint)
                c.drawCircle(12f, 10f, 2.4f, paint)
            }
            Kind.PHONE -> {
                oval.set(7f, 2.5f, 17f, 21.5f); c.drawRoundRect(oval, 2.6f, 2.6f, paint)
                c.drawLine(10.5f, 18.2f, 13.5f, 18.2f, paint)
            }
            Kind.PERSON -> {
                c.drawCircle(12f, 8f, 3.8f, paint)
                path.moveTo(4.8f, 20.5f); path.cubicTo(4.8f, 16f, 8f, 13.6f, 12f, 13.6f); path.cubicTo(16f, 13.6f, 19.2f, 16f, 19.2f, 20.5f)
                c.drawPath(path, paint)
            }
            Kind.CLOCK -> {
                c.drawCircle(12f, 12f, 9f, paint)
                path.moveTo(12f, 7f); path.lineTo(12f, 12f); path.lineTo(15.5f, 14f)
                c.drawPath(path, paint)
            }
            Kind.SWIPE -> {
                c.drawLine(4f, 12f, 20f, 12f, paint)
                path.moveTo(8f, 8f); path.lineTo(4f, 12f); path.lineTo(8f, 16f)
                path.moveTo(16f, 8f); path.lineTo(20f, 12f); path.lineTo(16f, 16f)
                c.drawPath(path, paint)
            }
            Kind.PULL -> {
                c.drawLine(6f, 4f, 18f, 4f, paint)
                c.drawLine(12f, 8.5f, 12f, 20f, paint)
                path.moveTo(7.5f, 15.5f); path.lineTo(12f, 20f); path.lineTo(16.5f, 15.5f)
                c.drawPath(path, paint)
            }
            Kind.SPARKLE -> {
                path.moveTo(12f, 2.5f); path.quadTo(13f, 11f, 21.5f, 12f); path.quadTo(13f, 13f, 12f, 21.5f)
                path.quadTo(11f, 13f, 2.5f, 12f); path.quadTo(11f, 11f, 12f, 2.5f); path.close()
                paint.style = Paint.Style.FILL; c.drawPath(path, paint)
            }
            Kind.CHECK -> { path.moveTo(5f, 12.5f); path.lineTo(10f, 17.5f); path.lineTo(19f, 7f); c.drawPath(path, paint) }
            Kind.LEFT -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f); c.drawPath(path, paint) }
            Kind.RIGHT -> { path.moveTo(9f, 5f); path.lineTo(16f, 12f); path.lineTo(9f, 19f); c.drawPath(path, paint) }
        }
        c.restore()
    }

    private fun fillCircle(c: Canvas, x: Float, y: Float, r: Float) {
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y, r, paint)
        paint.style = Paint.Style.STROKE
    }
}
