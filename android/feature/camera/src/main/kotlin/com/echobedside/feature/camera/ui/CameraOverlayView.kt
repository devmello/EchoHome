package com.echobedside.feature.camera.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.GlassPainter
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.Presence
import com.echobedside.core.model.Profile
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.camera.CameraPhase
import com.echobedside.feature.camera.CameraPipeline
import com.echobedside.feature.camera.CameraUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** What the overlay shows; [CameraUi] normally, or a forced step from the "profiles-demo" screen. */
enum class DemoStep { EMPTY, DETECTING, RECOGNIZED, PERSONALIZED }

/**
 * The Profiles.dc.html layer above every screen (z 250, under the island):
 *  - camera-in-use label + green dot, top-right, whenever the camera samples
 *    ("Presence · 2 fps", "Face check", "Recognized")
 *  - the dimmed shared face when the room is empty (StandBy only; 0.35 opacity over black)
 *  - "Who's there?" glass pill while the detection burst runs
 *  - the "Hi <name>" glass greeting with alarm / event chips and "Not me"
 *  - "On-device recognition · no images saved" while faces are checked
 *
 * Empty areas never consume touches; a touch anywhere also tells presence someone's here.
 * It also tells the pipeline when our window is visible (the camera runs only then).
 */
@SuppressLint("ViewConstructor")
class CameraOverlayView(context: Context, private val services: EchoServices, private val pipeline: CameraPipeline) : View(context) {

    private var ui = CameraUi()
    private var demo: DemoStep? = null
    private var demoProfile: Profile? = null
    private var screen = "standby"
    private var night = false
    private var micBadge = false

    // text styles (Profiles.dc.html)
    private val label = SpecText(12f, 700)
    private val pillText = SpecText(15f, 700)
    private val hiText = SpecText(30f, 800, -0.025f)
    private val subText = SpecText(15f, 600)
    private val chipText = SpecText(13f, 700)
    private val buttonText = SpecText(14f, 700)
    private val letterText = SpecText(36f, 800)
    private val footText = SpecText(12f, 600)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pillGlass = GlassPainter().apply { tint = withAlpha(Color.WHITE, 0.10f) }
    private val cardGlass = GlassPainter().apply { tint = withAlpha(Color.WHITE, 0.10f) }
    private val cardSpec = Paint(Paint.ANTI_ALIAS_FLAG)
    private var avatarFor = 0

    // animated values
    private var dim = 0f
    private var cardIn = 0f        // opacity, 0.35 s ease
    private var cardSpring = 0f    // translate/scale, 0.6 s spring
    private var dimAnim: ValueAnimator? = null
    private var cardFade: ValueAnimator? = null
    private var cardMove: ValueAnimator? = null
    private var greeting: Profile? = null
    private var chips: List<String> = emptyList()
    private val notMe = RectF()
    private var pressingNotMe = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeWhileAttached(pipeline.ui) { ui = it; update() }
        observeWhileAttached(services.navigator.current) { screen = it; update() }
        observeWhileAttached(services.system.state) { s ->
            if (s.nightActive != night || s.micMutedSoftware != micBadge) { night = s.nightActive; micBadge = s.micMutedSoftware; invalidate() }
        }
        observeWhileAttached(com.echobedside.feature.camera.CameraFeature.demo) { d -> demo = d?.first; demoProfile = d?.second; update() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        pipeline.setVisible(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        pipeline.setVisible(false)
        dimAnim?.cancel(); cardFade?.cancel(); cardMove?.cancel()
        super.onDetachedFromWindow()
    }

    // --- state ---

    private val phase: CameraPhase get() = when (demo) {
        DemoStep.EMPTY -> CameraPhase.PRESENCE
        DemoStep.DETECTING -> CameraPhase.DETECTING
        DemoStep.RECOGNIZED, DemoStep.PERSONALIZED -> CameraPhase.RECOGNIZED
        null -> ui.phase
    }

    private fun update() {
        val wantDim = when (demo) {
            DemoStep.EMPTY -> true
            null -> ui.presence == Presence.EMPTY && screen == "standby"
            else -> false
        }
        animateDim(if (wantDim) 1f else 0f)
        val g = when (demo) { DemoStep.RECOGNIZED -> demoProfile; null -> ui.greeting; else -> null }
        if (g != null && g != greeting) { greeting = g; chips = chipsFor(g) }
        animateCard(g != null)
        invalidate()
        if (phase == CameraPhase.DETECTING) postInvalidateOnAnimation()
        contentDescription = labelText()
    }

    private fun labelText(): String? = when (phase) {
        CameraPhase.OFF -> null
        CameraPhase.PRESENCE -> "Presence · ${ui.presenceFps} fps"
        CameraPhase.DETECTING -> "Face check"
        CameraPhase.RECOGNIZED -> "Recognized"
        CameraPhase.ENROLLING -> "Enrolling face"
    }

    private fun chipsFor(p: Profile): List<String> {
        // Sample data in demo mode (Profiles.dc.html: Maya's alarm and dinner).
        if (services.config.demoData && p.id == "maya") return listOf("Alarm 6:15 AM", "Dinner 7:00 PM")
        val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<String>()
        services.alarms.next.value?.let { out += "Alarm " + fmt.format(Instant.ofEpochMilli(it.triggerAtMillis).atZone(zone)) }
        val now = System.currentTimeMillis()
        services.calendar.events.value.firstOrNull { !it.allDay && it.startMillis > now }?.let {
            out += it.title.take(18) + " " + fmt.format(Instant.ofEpochMilli(it.startMillis).atZone(zone))
        }
        return out
    }

    private fun animateDim(to: Float) {
        if ((dimAnim?.isRunning == true && dimTarget == to) || (dimAnim?.isRunning != true && dim == to)) return
        dimTarget = to
        dimAnim?.cancel()
        dimAnim = ValueAnimator.ofFloat(dim, to).apply {
            duration = 900; interpolator = Motion.EASE
            addUpdateListener { dim = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    private var dimTarget = 0f

    private fun animateCard(show: Boolean) {
        val to = if (show) 1f else 0f
        if (cardTarget == to) return
        cardTarget = to
        cardFade?.cancel(); cardMove?.cancel()
        cardFade = ValueAnimator.ofFloat(cardIn, to).apply {
            duration = 350; interpolator = Motion.EASE
            addUpdateListener { cardIn = it.animatedValue as Float; invalidate() }
            start()
        }
        cardMove = ValueAnimator.ofFloat(cardSpring, to).apply {
            duration = 600; interpolator = Motion.SPRING
            addUpdateListener { cardSpring = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    private var cardTarget = 0f

    private fun c(color: Int) = Night.map(color, night)

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        if (dim > 0f) canvas.drawColor(withAlpha(Color.BLACK, 0.65f * dim))
        drawIndicator(canvas)
        if (phase == CameraPhase.DETECTING) { drawPill(canvas); postInvalidateOnAnimation() }
        if (cardIn > 0.001f) greeting?.let { drawCard(canvas, it) }
        if (phase == CameraPhase.DETECTING || phase == CameraPhase.RECOGNIZED) {
            val top = height - 14f - footText.normalLineHeight
            footText.draw(canvas, "On-device recognition · no images saved", 22f, footText.baselineIn(top), c(withAlpha(Color.WHITE, 0.5f)))
        }
    }

    /** right: 18px; top: 16px; label 12/700 .7, gap 6, 9 px #30D158 dot with a soft glow. */
    private fun drawIndicator(canvas: Canvas) {
        val text = labelText() ?: return
        val right = width - 18f - if (micBadge) 34f + 12f else 0f
        val rowH = label.normalLineHeight
        val top = 16f
        val dotCx = right - 4.5f
        val dotCy = top + rowH / 2f
        val green = c(0xFF30D158.toInt())
        glowPaint.color = withAlpha(green, 0f)
        glowPaint.setShadowLayer(8f, 0f, 0f, withAlpha(green, 0.8f))
        glowPaint.color = green
        canvas.drawCircle(dotCx, dotCy, 4.5f, glowPaint)
        val tw = label.width(text)
        label.draw(canvas, text, right - 9f - 6f - tw, label.baselineIn(top, rowH), c(withAlpha(Color.WHITE, 0.7f)))
    }

    /** "Who's there?": 220×48 capsule at top 18, centred; 16 px spinner, gap 10, 15/700 text. */
    private fun drawPill(canvas: Canvas) {
        val l = (width - 220f) / 2f
        val t = 18f
        pillGlass.night = night
        pillGlass.draw(canvas, l, t, l + 220f, t + 48f)
        val text = "Who’s there?"
        val tw = pillText.width(text)
        val group = 16f + 10f + tw
        val x0 = l + (220f - group) / 2f
        val cy = t + 24f
        rect.set(x0 + 1.25f, cy - 8f + 1.25f, x0 + 16f - 1.25f, cy + 8f - 1.25f)
        arcPaint.color = c(withAlpha(Color.WHITE, 0.3f))
        canvas.drawOval(rect, arcPaint)
        arcPaint.color = c(Color.WHITE)
        // border-top-color on a circle = a 90° arc centred on the top; spins at 360° per 792 ms.
        val spin = ((System.currentTimeMillis() / 2.2) % 360).toFloat()
        canvas.drawArc(rect, -135f + spin, 90f, false, arcPaint)
        pillText.draw(canvas, text, x0 + 26f, pillText.baselineIn(t, 48f), c(Color.WHITE))
    }

    /** The greeting sheet: 560×132 at bottom 30, radius 34, padding 18/22, gap 18. */
    private fun drawCard(canvas: Canvas, p: Profile) {
        val w = 560f; val h = 132f
        val l = (width - w) / 2f
        val t = height - 30f - h
        val a = cardIn
        val dy = (1f - cardSpring) * 40f
        val s = 0.92f + 0.08f * cardSpring
        canvas.save()
        canvas.translate(0f, dy)
        canvas.scale(s, s, l + w / 2f, t + h / 2f)

        cardGlass.alpha = a
        cardGlass.night = night
        cardGlass.draw(canvas, l, t, l + w, t + h, 34f)
        if (!night) {
            // the sheet's own specular: linear-gradient(165deg, .26 0%, .03 40%, 0 70%)
            if (cardSpec.shader == null || cardSpecAlpha != a) {
                cardSpecAlpha = a
                val ang = Math.toRadians(165.0)
                val dx = sin(ang).toFloat(); val dyy = -cos(ang).toFloat()
                val len = kotlin.math.abs(w * dx) + kotlin.math.abs(h * dyy)
                val cx = l + w / 2f; val cy = t + h / 2f
                cardSpec.shader = LinearGradient(cx - dx * len / 2, cy - dyy * len / 2, cx + dx * len / 2, cy + dyy * len / 2,
                    intArrayOf(withAlpha(Color.WHITE, 0.26f * a), withAlpha(Color.WHITE, 0.03f * a), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.4f, 0.7f), Shader.TileMode.CLAMP)
            }
            rect.set(l, t, l + w, t + h)
            canvas.drawRoundRect(rect, 34f, 34f, cardSpec)
        }

        // avatar: 84 px, gradient(160deg, lighter, colour), rings 4 px black .35 then 7 px white .9
        val ax = l + 22f + 42f
        val ay = t + h / 2f
        paint.style = Paint.Style.FILL
        paint.color = c(withAlpha(Color.WHITE, 0.9f * a)); canvas.drawCircle(ax, ay, 42f + 7f, paint)
        paint.color = withAlpha(Color.BLACK, 0.35f * a); canvas.drawCircle(ax, ay, 42f + 4f, paint)
        val base = c(p.color)
        if (avatarFor != base) {
            avatarFor = base
            val light = lighten(base, 0.25f)
            val ang = Math.toRadians(160.0)
            val dx = sin(ang).toFloat() * 42f; val dyy = -cos(ang).toFloat() * 42f
            avatarPaint.shader = LinearGradient(-dx, -dyy, dx, dyy, light, base, Shader.TileMode.CLAMP)
        }
        avatarPaint.alpha = (255 * a).toInt()
        canvas.save(); canvas.translate(ax, ay); canvas.drawCircle(0f, 0f, 42f, avatarPaint); canvas.restore()
        val letter = p.name.take(1).uppercase()
        letterText.draw(canvas, letter, ax - letterText.width(letter) / 2f, letterText.baselineIn(ay - 42f, 84f), withAlpha(c(Color.WHITE), a))

        // "Not me": h44, padding 0 16, radius 22, 14/700, bg .16; right edge at the padding
        val bw = 32f + buttonText.width("Not me")
        val br = l + w - 22f
        notMe.set(br - bw, t + (h - 44f) / 2f, br, t + (h + 44f) / 2f)
        paint.color = c(withAlpha(Color.WHITE, (if (pressingNotMe) 0.28f else 0.16f) * a))
        canvas.drawRoundRect(notMe, 22f, 22f, paint)
        buttonText.draw(canvas, "Not me", notMe.left + 16f, buttonText.baselineIn(notMe.top, 44f), withAlpha(c(Color.WHITE), a))

        // text column
        val tx = l + 22f + 84f + 18f
        val colH = hiText.normalLineHeight + 2f + subText.normalLineHeight + (if (chips.isNotEmpty()) 10f + 28f else 0f)
        var y = t + (h - colH) / 2f
        hiText.draw(canvas, "Hi ${p.name}", tx, hiText.baselineIn(y), withAlpha(c(Color.WHITE), a))
        y += hiText.normalLineHeight + 2f
        subText.draw(canvas, "Switched to your clock, alarm and music", tx, subText.baselineIn(y), withAlpha(c(withAlpha(Color.WHITE, 0.8f)), a))
        y += subText.normalLineHeight + 10f
        var cx = tx
        for (chip in chips) {
            val cw = 20f + chipText.width(chip)
            rect.set(cx, y, cx + cw, y + 28f)
            paint.color = c(withAlpha(Color.WHITE, 0.16f * a))
            canvas.drawRoundRect(rect, 14f, 14f, paint)
            chipText.draw(canvas, chip, cx + 10f, chipText.baselineIn(y, 28f), withAlpha(c(Color.WHITE), a))
            cx += cw + 8f
        }
        canvas.restore()
    }
    private var cardSpecAlpha = -1f

    private fun lighten(color: Int, f: Float): Int {
        fun ch(v: Int) = (v + (255 - v) * f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val onButton = greeting != null && cardIn > 0.5f && notMe.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pipeline.onTouch()
                if (!onButton) return false
                pressingNotMe = true; invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressingNotMe) {
                    pressingNotMe = false
                    if (onButton) {
                        if (demo != null) com.echobedside.feature.camera.CameraFeature.showDemo(DemoStep.EMPTY, null)
                        else pipeline.notMe()
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressingNotMe = false; invalidate(); return true }
        }
        return pressingNotMe
    }
}
