package com.echobedside.feature.home.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.GlassPainter
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.feature.home.home.TileIcon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Icon paths from Home.dc.html / Commute.dc.html (24-unit viewBox). */
object Icons {
    const val BULB = "M9 21h6v-1H9zm3-19a7 7 0 0 0-4 12.74V17a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1v-2.26A7 7 0 0 0 12 2z"
    const val LAMP_BULB = "M12 2a7 7 0 0 0-4 12.74V17a2 2 0 0 0 2 2h4a2 2 0 0 0 2-2v-2.26A7 7 0 0 0 12 2zM10 20h4v1a2 2 0 0 1-4 0z"
    const val FAN = "M12 11a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3zm.5-8c3 0 4 2.6 2.4 5.2l-1.7 2.6a3 3 0 0 0-1.9-.5l.6-3C12.4 5.6 11 4.5 12.5 3zm8.4 13.1c-1.5 2.6-4.3 2.2-5.7-.5l-1.4-2.8a3 3 0 0 0 1.4-1.4l2.9 1c1.9.6 4.3 1.2 2.8 3.7zM3.6 16.6c-1.5-2.6.3-4.8 3.3-4.7l3.1.2a3 3 0 0 0 .5 1.9l-2.3 2c-1.4 1.3-3.2 3-4.6.6z"
    const val SUN = "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM11 1h2v3h-2zM11 20h2v3h-2zM1 11h3v2H1zM20 11h3v2h-3z"
    const val TV = "M3 5.5A1.5 1.5 0 0 1 4.5 4h15A1.5 1.5 0 0 1 21 5.5v10a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 15.5zM8 19.5a.75.75 0 0 1 .75-.75h6.5a.75.75 0 0 1 0 1.5h-6.5A.75.75 0 0 1 8 19.5z"
    const val MOON = "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"
    const val AWAY = "M12 3 2 11h3v9h5v-6h4v6h5v-9h3z"
    const val LOCK = "M7 10V7a5 5 0 0 1 10 0v3h1a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2zm2 0h6V7a3 3 0 0 0-6 0z"
    const val UNLOCK = "M7 10V7a5 5 0 0 1 9.6-2l-1.8.9A3 3 0 0 0 9 7v3h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"
    const val GARAGE = "M12 3 2 8v13h3v-9h14v9h3V8zM7 14h10v2H7zm0 4h10v2H7z"
    /** Not in the design: a power glyph for switches, a sparkle for unnamed scenes. */
    const val POWER = "M11 2h2v10h-2zM7.05 5.05l1.41 1.41A6 6 0 1 0 15.54 6.46l1.41-1.41A8 8 0 1 1 7.05 5.05z"
    const val SPARKLE = "M12 2l2.2 6.3L20.5 10l-6.3 2.2L12 18.5l-2.2-6.3L3.5 10l6.3-1.7zM19 15l.9 2.1L22 18l-2.1.9L19 21l-.9-2.1L16 18l2.1-.9z"
    /** Stroked icons. */
    const val CAR = "M5 17h14M6 17l1.5-6h9L18 17M8 21v-2M16 21v-2M7.5 11 9 6h6l1.5 5"
    const val MINUS = "M5 12h14"
    const val PLUS = "M5 12h14M12 5v14"

    fun of(i: TileIcon): String = when (i) {
        TileIcon.BULB -> BULB; TileIcon.FAN -> FAN; TileIcon.POWER -> POWER; TileIcon.SUN -> SUN; TileIcon.TV -> TV
        TileIcon.MOON -> MOON; TileIcon.AWAY -> AWAY; TileIcon.SPARKLE -> SPARKLE; TileIcon.LOCK -> LOCK
        TileIcon.UNLOCK -> UNLOCK; TileIcon.GARAGE -> GARAGE
    }

    private val cache = HashMap<String, Path>()
    /** The icon at [size] px with its top-left at 0,0 (cached; translate the canvas to place it). */
    fun path(d: String, size: Float): Path = cache.getOrPut("$size|$d") { SvgPath.path(d, size / 24f) }
}

/** Fill one cached icon at x,y. */
fun Canvas.icon(d: String, x: Float, y: Float, size: Float, paint: Paint) {
    save(); translate(x, y); drawPath(Icons.path(d, size), paint); restore()
}

fun SpecText.drawCentered(c: Canvas, s: String, cx: Float, baseline: Float, color: Int) = draw(c, s, cx - width(s) / 2f, baseline, color)
fun SpecText.drawRight(c: Canvas, s: String, right: Float, baseline: Float, color: Int) = draw(c, s, right - width(s), baseline, color)

/** CSS text-overflow: ellipsis. */
fun SpecText.ellipsize(s: String, max: Float): String {
    if (width(s) <= max) return s
    var t = s
    while (t.isNotEmpty() && width("$t…") > max) t = t.dropLast(1)
    return t.trimEnd() + "…"
}

/** Word wrap to [max] px, at most [lines] lines (the last one ellipsized). */
fun SpecText.wrap(s: String, max: Float, lines: Int): List<String> {
    val out = ArrayList<String>()
    var line = ""
    val words = s.split(' ')
    for ((i, w) in words.withIndex()) {
        val cand = if (line.isEmpty()) w else "$line $w"
        if (width(cand) <= max || line.isEmpty()) line = cand
        else {
            out += line
            if (out.size == lines - 1) { out += ellipsize(words.drop(i).joinToString(" "), max); return out }
            line = w
        }
    }
    if (line.isNotEmpty()) out += ellipsize(line, max)
    return out
}

fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 255) + (((b shr s) and 255) - ((a shr s) and 255)) * t).toInt().coerceIn(0, 255) shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

/** CSS linear-gradient(angle) endpoints over a w×h box (0deg = to top, clockwise). */
fun cssLinear(angleDeg: Float, w: Float, h: Float, colors: IntArray, stops: FloatArray?, ox: Float = 0f, oy: Float = 0f): LinearGradient {
    val a = Math.toRadians(angleDeg.toDouble())
    val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
    val len = abs(w * dx) + abs(h * dy)
    val cx = ox + w / 2; val cy = oy + h / 2
    return LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2, colors, stops, Shader.TileMode.CLAMP)
}

const val INK_DARK = 0xFF111114.toInt()
/** Control-Center "on" tile: rgba(255,255,255,0.88). */
val GLASS_ON = withAlpha(Color.WHITE, 0.88f)
val GLASS_OFF = withAlpha(Color.WHITE, 0.09f)

/**
 * A Home.dc.html glass tile: the glass recipe (pre-blurred backdrop, rims, specular, shadow),
 * a 0.25 s fill change between off (glass) and on (rgba(255,255,255,.88)), a spring on press,
 * and Night mode (red outline, red ink). Subclasses draw their content in [drawContent].
 */
@SuppressLint("ViewConstructor")
abstract class GlassTile(context: Context, private val radius: Float, private val backdrop: () -> Backdrop?) : View(context) {
    /**
     * No live shadow: GlassPainter's setShadowLayer on a path is re-blurred on the CPU every frame
     * (≈ 100 ms frames on the Echo with 11 tiles). The screen bakes the shadows into its wallpaper
     * instead ([com.echobedside.feature.home.home.HomeWallpaper.shadows]).
     */
    protected val glass = GlassPainter().apply { shadow = false }
    var night = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** 0 = off (glass), 1 = on (white). */
    protected var onT = 0f
        private set
    private var anim: ValueAnimator? = null
    var onTap: ((x: Float, y: Float) -> Unit)? = null
    /** False = no press spring (e.g. the thermostat, whose buttons are inside it). */
    protected open val springs = true
    protected val clip = Path()
    protected val rect = RectF()

    fun setOn(on: Boolean, animate: Boolean) {
        val target = if (on) 1f else 0f
        if (target == onT && anim == null) return
        anim?.cancel(); anim = null
        if (!animate || !isAttachedToWindow) { onT = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(onT, target).apply {
            duration = 250; interpolator = Motion.EASE
            addUpdateListener { onT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { anim = null } })
            start()
        }
    }

    /** Ink colour (white → #111114 as the tile turns on), Night-mapped. */
    protected fun ink(alpha: Float = 1f): Int = Night.map(withAlpha(lerpColor(Color.WHITE, INK_DARK, onT), alpha), night)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        glass.night = night
        glass.backdrop = backdrop()
        glass.tint = if (night) null else if (onT <= 0f) null else lerpColor(GLASS_OFF, GLASS_ON, onT)
        glass.draw(canvas, 0f, 0f, w, h, radius, left + translationX, top + translationY)
        if (night && onT > 0f) { // Night: "on" is a faint red fill instead of white
            fill.color = withAlpha(Night.COLOR, 0.22f * onT)
            rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, radius, radius, fill)
        }
        drawContent(canvas, w, h)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    protected abstract fun drawContent(canvas: Canvas, w: Float, h: Float)

    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; if (springs) press(true) }
            MotionEvent.ACTION_UP -> {
                if (springs) press(false)
                if (abs(e.x - downX) < 24 && abs(e.y - downY) < 24) { onTap?.invoke(e.x, e.y); performClick() }
            }
            MotionEvent.ACTION_CANCEL -> if (springs) press(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun press(down: Boolean) {
        animate().cancel()
        if (down) animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).setInterpolator(Motion.EASE).start()
        else animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.SPRING).start()
    }
}
