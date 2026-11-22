package app.vesper.feature.camera.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import kotlin.math.cos
import kotlin.math.sin

/** Small drawing helpers shared by the Profiles screens (same recipes as Profiles.dc.html). */
internal class Painter {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val avatar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val letter = HashMap<Float, SpecText>()
    val primaryGlass = GlassPainter().apply { tint = 0xFF0A84FF.toInt() }

    /** Avatar: gradient(160deg, colour lightened 25 %, colour) + initial (800 weight, 0.43 × size). */
    fun avatar(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, name: String, ring: Boolean, alpha: Float = 1f) {
        if (ring) {
            fill.color = withAlpha(Color.WHITE, 0.9f * alpha); c.drawCircle(cx, cy, r + 7f, fill)
            fill.color = withAlpha(Color.BLACK, 0.35f * alpha); c.drawCircle(cx, cy, r + 4f, fill)
        }
        val ang = Math.toRadians(160.0)
        val dx = sin(ang).toFloat() * r; val dy = -cos(ang).toFloat() * r
        avatar.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, lighten(color, 0.25f), color, Shader.TileMode.CLAMP)
        avatar.alpha = (255 * alpha).toInt()
        c.drawCircle(cx, cy, r, avatar)
        val t = letter.getOrPut(r) { SpecText(r * 2f * 0.43f, 800) }
        val s = name.take(1).uppercase()
        t.draw(c, s, cx - t.width(s) / 2f, t.baselineIn(cy - r, 2f * r), withAlpha(Color.WHITE, alpha))
    }

    /** A plain-fill capsule button (things on glass / on content use fills, not glass). Returns its rect. */
    fun button(c: Canvas, text: SpecText, label: String, l: Float, t: Float, h: Float, padX: Float, out: RectF,
               bg: Int = withAlpha(Color.WHITE, 0.16f), fg: Int = Color.WHITE, right: Boolean = false): RectF {
        val w = padX * 2 + text.width(label)
        val x = if (right) l - w else l
        out.set(x, t, x + w, t + h)
        fill.color = bg
        c.drawRoundRect(out, h / 2f, h / 2f, fill)
        text.draw(c, label, x + padX, text.baselineIn(t, h), fg)
        return out
    }

    /** The one tinted (primary) action, as tinted glass. */
    fun primary(c: Canvas, text: SpecText, label: String, l: Float, t: Float, h: Float, padX: Float, out: RectF, right: Boolean = false): RectF {
        val w = padX * 2 + text.width(label)
        val x = if (right) l - w else l
        out.set(x, t, x + w, t + h)
        primaryGlass.draw(c, out.left, out.top, out.right, out.bottom)
        text.draw(c, label, x + padX, text.baselineIn(t, h), Color.WHITE)
        return out
    }

    /** iOS-style switch, 51×31. */
    fun switch(c: Canvas, l: Float, t: Float, on: Boolean, out: RectF): RectF {
        out.set(l, t, l + 51f, t + 31f)
        fill.color = if (on) 0xFF30D158.toInt() else withAlpha(Color.WHITE, 0.22f)
        c.drawRoundRect(out, 15.5f, 15.5f, fill)
        fill.color = Color.WHITE
        c.drawCircle(if (on) l + 51f - 15.5f else l + 15.5f, t + 15.5f, 13.5f, fill)
        return out
    }

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        rect.set(l, t, r, b); fill.color = color; c.drawRoundRect(rect, radius, radius, fill)
    }

    companion object {
        fun lighten(color: Int, f: Float): Int {
            fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
            return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
        }
    }
}

/** Wraps [s] into lines no wider than [maxW]. */
internal fun wrap(t: SpecText, s: String, maxW: Float): List<String> {
    val out = mutableListOf<String>()
    var line = ""
    for (word in s.split(' ')) {
        val next = if (line.isEmpty()) word else "$line $word"
        if (t.width(next) <= maxW || line.isEmpty()) line = next else { out += line; line = word }
    }
    if (line.isNotEmpty()) out += line
    return out
}
