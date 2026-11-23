package app.vesper.feature.clock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.View
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import java.util.Calendar
import java.util.TimeZone

/** Time as the clock shows it: "6:15 AM", or "06:15" in 24 h. */
fun formatClock(millis: Long, is24: Boolean, zone: TimeZone = TimeZone.getDefault()): String {
    val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
    val h = c.get(Calendar.HOUR_OF_DAY)
    val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
    return if (is24) "${h.toString().padStart(2, '0')}:$m" else "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "AM" else "PM"}"
}

/**
 * Where the two extras sit on a face. Corners by default (top-left personal line, bottom-right
 * sun / moon); the Analog dial fills the corners, so there they sit inside the dial.
 */
object ExtrasLayout {
    class Spot(val x: Float, val y: Float)
    class Slots(val personal: Spot, val astro: Spot, val astroShown: Boolean = true)

    private val DEFAULT = Slots(Spot(28f, 18f), Spot(960f - 28f - AstroView.W, 480f - 26f - AstroView.H))
    private val ANALOG = Slots(Spot((960f - PersonalView.W) / 2f, 128f), Spot(70f, 292f))
    // Weather already has its own sunrise module; the Calendar's agenda column is full.
    private val NO_ASTRO = Slots(DEFAULT.personal, DEFAULT.astro, astroShown = false)

    fun forFace(id: String): Slots = when (id) {
        "analog" -> ANALOG
        "weather", "calendar" -> NO_ASTRO
        else -> DEFAULT
    }
}

/**
 * Night face extra: the moon (glyph with the lit part, name and %) and sunrise / sunset, small
 * and in Night's red. Recomputed only by [update] (the minute tick, a day change, new weather or
 * location), drawn only when its strings change.
 */
class AstroView(context: Context) : View(context) {
    private val nameText = SpecText(14f, 600, 0.01f)
    private val timesText = SpecText(14f, 500, 0.01f)
    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val lit = Path()
    private val oval = RectF()

    private var illumination = 0.0
    private var waxing = true
    private var north = true
    private var title = ""
    private var rise = ""
    private var set = ""

    /** Recomputes from [now]; invalidates only if something visible changed. [sun] = sunrise / sunset to show. */
    fun update(now: Long, sun: Pair<Long, Long>?, is24: Boolean, lat: Double) {
        val p = MoonMath.phase(now)
        val t = "${p.name} · ${p.percent}%"
        val r = sun?.let { formatClock(it.first, is24) } ?: "--"
        val s = sun?.let { formatClock(it.second, is24) } ?: "--"
        // the glyph only changes by a visible amount every few minutes; the strings carry the %
        if (t != title || r != rise || s != set || (lat >= 0) != north) {
            title = t; rise = r; set = s; north = lat >= 0
            illumination = p.illumination; waxing = p.waxing
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (title.isEmpty()) return
        val c = Night.COLOR
        val cx = W - GLYPH / 2f
        val cy = H / 2f
        drawMoon(canvas, cx, cy, GLYPH / 2f - 1f, c)
        val right = W - GLYPH - 12f
        // two lines, right aligned against the glyph, centred on it
        val lh = nameText.normalLineHeight
        val top = cy - (lh * 2 + 3f) / 2f
        val b1 = nameText.baselineIn(top)
        nameText.draw(canvas, title, right - nameText.width(title), b1, c)
        val b2 = timesText.baselineIn(top + lh + 3f)
        val c2 = withAlpha(c, 0.75f)
        val wSet = timesText.width(set)
        val wRise = timesText.width(rise)
        var x = right - wSet
        timesText.draw(canvas, set, x, b2, c2)
        x -= ARROW + 4f
        drawArrow(canvas, x + ARROW / 2f, b2 - timesText.ascent * 0.38f, down = true, c2)
        x -= 16f + wRise
        timesText.draw(canvas, rise, x, b2, c2)
        x -= ARROW + 4f
        drawArrow(canvas, x + ARROW / 2f, b2 - timesText.ascent * 0.38f, down = false, c2)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, down: Boolean, color: Int) {
        arrow.color = color
        val h = 5.5f
        val d = if (down) 1f else -1f
        lit.rewind()
        lit.moveTo(cx, cy - h * d); lit.lineTo(cx, cy + h * d)
        lit.moveTo(cx - 4f, cy + (h - 4f) * d); lit.lineTo(cx, cy + h * d); lit.lineTo(cx + 4f, cy + (h - 4f) * d)
        canvas.drawPath(lit, arrow)
    }

    /** The disc: a dim rim, the lit part filled (a half disc plus or minus the terminator ellipse). */
    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        rimPaint.color = withAlpha(color, 0.45f)
        canvas.drawCircle(cx, cy, r, rimPaint)
        val k = illumination
        if (k < 0.01) return
        litPaint.color = color
        if (k > 0.99) { canvas.drawCircle(cx, cy, r, litPaint); return }
        // lit side: right when waxing in the northern hemisphere, mirrored otherwise
        val rightLit = waxing == north
        val tw = r * kotlin.math.abs(1f - 2f * k.toFloat()) // terminator half-width
        lit.rewind()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // outer limb: top → bottom through the lit side
        lit.arcTo(oval, -90f, if (rightLit) 180f else -180f, true)
        // terminator back to the top: crescent bulges to the lit side, gibbous to the dark side
        oval.set(cx - tw, cy - r, cx + tw, cy + r)
        val crescent = k < 0.5
        val sweepRight = (crescent && rightLit) || (!crescent && !rightLit)
        lit.arcTo(oval, 90f, if (sweepRight) -180f else 180f, false)
        lit.close()
        canvas.drawPath(lit, litPaint)
    }

    companion object {
        const val W = 330f
        const val H = 40f
        const val GLYPH = 34f
        const val ARROW = 8f
    }

    init { minimumWidth = W.toInt() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(W.toInt(), H.toInt())
}

/**
 * StandBy's personal line: avatar dot, name, their next alarm and next event, e.g.
 * "(M) Maya · ⏰ 6:15 AM · Dentist 9:00 AM". Fades in on a switch and out on return to shared.
 * Colour = the person's own, Night's red at night.
 */
class PersonalView(context: Context) : View(context) {
    private val nameText = SpecText(16f, 700, -0.005f)
    private val bodyText = SpecText(16f, 600, -0.005f)
    private val initialText = SpecText(14f, 800)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val icon: Drawable = context.getDrawable(R.drawable.ic_cx_alarm)!!.mutate()
    private val ellipsis = "…"

    private var name = ""
    private var initial = ""
    private var color = 0
    private var alarm: String? = null
    private var event: String? = null
    private var night = false
    private var fade: ValueAnimator? = null
    private var shownFor: String? = null

    init { alpha = 0f }

    /** Hidden while faces slide (the line belongs to the current face's position). */
    fun setSuppressed(on: Boolean) { visibility = if (on) INVISIBLE else VISIBLE }

    val hasPerson: Boolean get() = name.isNotEmpty()

    /**
     * Shows [personName] (null = back to shared: fades out, then clears). Texts and colour update
     * in place while the same person stays.
     */
    fun bind(personId: String?, personName: String?, personColor: Int, alarmText: String?, eventText: String?, night: Boolean) {
        if (personName == null) {
            shownFor = null
            fadeTo(0f) { name = ""; alarm = null; event = null }
            return
        }
        val changed = name != personName || color != personColor || alarm != alarmText || event != eventText || this.night != night
        val newPerson = shownFor != personId
        name = personName; initial = personName.take(1).uppercase(); color = personColor
        alarm = alarmText; event = eventText; this.night = night
        shownFor = personId
        if (changed) invalidate()
        if (newPerson || alpha < 1f) fadeTo(1f, from = if (newPerson) 0f else null)
    }

    private fun fadeTo(target: Float, from: Float? = null, end: (() -> Unit)? = null) {
        fade?.cancel()
        if (from != null) alpha = from
        if (alpha == target) { end?.invoke(); invalidate(); return }
        fade = ValueAnimator.ofFloat(alpha, target).apply {
            duration = 700
            interpolator = Motion.EASE
            addUpdateListener { alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) { end?.invoke(); invalidate() } }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (name.isEmpty()) return
        val fg = if (night) Night.COLOR else color
        val sub = if (night) Night.COLOR else withAlpha(0xFFFFFFFF.toInt(), 0.82f)
        val cy = H / 2f
        // avatar: filled disc with the initial (day), outline with the initial (night)
        val r = AVATAR / 2f
        if (night) { ring.color = fg; canvas.drawCircle(r, cy, r - 0.75f, ring) }
        else { dot.color = fg; canvas.drawCircle(r, cy, r, dot) }
        val ic = initialText
        ic.draw(canvas, initial, r - ic.width(initial) / 2f, ic.baselineIn(cy - ic.normalLineHeight / 2f),
            if (night) fg else 0xFFFFFFFF.toInt())
        var x = AVATAR + 10f
        val base = nameText.baselineIn(cy - nameText.normalLineHeight / 2f)
        x = nameText.draw(canvas, name, x, base, if (night) fg else 0xFFFFFFFF.toInt())
        alarm?.let { a ->
            x = sep(canvas, x, base, sub)
            icon.setBounds(0, 0, 18, 18); icon.setTint(sub)
            canvas.save(); canvas.translate(x, cy - 9f); icon.draw(canvas); canvas.restore()
            x = bodyText.draw(canvas, a, x + 18f + 5f, base, sub)
        }
        event?.let { e ->
            x = sep(canvas, x, base, sub)
            val room = W - x
            bodyText.draw(canvas, fit(e, room), x, base, sub)
        }
    }

    private fun sep(canvas: Canvas, x: Float, base: Float, color: Int): Float =
        bodyText.draw(canvas, "·", x + 8f, base, withAlpha(color, 0.6f)) + 8f

    /** [s] shortened with an ellipsis to fit [room] px; the trailing time stays ("Dentist 9:00 AM"). */
    private fun fit(s: String, room: Float): String {
        if (bodyText.width(s) <= room) return s
        // keep the time words at the end: cut the title, not the time
        val tail = Regex("\\s(\\S+\\s?(AM|PM)?)$").find(s)?.value ?: ""
        var head = s.removeSuffix(tail)
        while (head.length > 1 && bodyText.width(head + ellipsis + tail) > room) head = head.dropLast(1)
        return head.trimEnd() + ellipsis + tail
    }

    companion object {
        const val W = 320f
        const val H = 32f
        const val AVATAR = 26f
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(W.toInt(), H.toInt())
}
