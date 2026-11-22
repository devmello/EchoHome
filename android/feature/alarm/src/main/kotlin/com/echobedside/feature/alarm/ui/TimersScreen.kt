package com.echobedside.feature.alarm.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.EchoColors
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.EchoTimer
import com.echobedside.core.model.TimerStore
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.alarm.AlarmFormat
import com.echobedside.feature.alarm.RealTimerStore
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Timers.dc.html: up to three live timers on glass Live-Activity-style platters over a warm
 * gradient, "5 min timer" add button, pause/resume/cancel, finished timers flash green with a
 * Repeat button. Countdowns redraw once a second; the ring moves with a 1 s linear transition only
 * when it would move ≥ 2 px (otherwise it steps with the second). Nothing runs while hidden.
 * Debug: `--es screen timers --es timers_demo 1` adds the board's Pasta / Oven / Tea timers.
 */
@SuppressLint("ViewConstructor")
class TimersScreen(context: Context, private val services: EchoServices) : AbsLayout(context), EchoScreen {
    private val title = SpecText(28f, 800, -0.025f)
    private val summary = SpecText(14f, 600)
    private val empty = SpecText(30f, 700)
    private val hint = SpecText(15f, 600)
    private var night = false
    private var shown = false
    private var list: List<EchoTimer> = emptyList()
    private val cards = HashMap<String, TimerCard>()
    private val handler = Handler(Looper.getMainLooper())

    private var bg: Bitmap? = null
    private var backdrop: Backdrop? = null
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val addBtn = GlassButton(context, GlassSpec(
        fill = withAlpha(Color.WHITE, 0.1f), border = withAlpha(Color.WHITE, 0.26f), rimTop = 0.45f, rimBottom = 0,
        shadow = withAlpha(Color.BLACK, 0.3f), shadowDy = 8f, shadowBlur = 24f,
        specAngle = 165f, specStops = floatArrayOf(0f, 0.55f), specAlphas = floatArrayOf(0.26f, 0f),
    ), SpecText(15f, 700), "5 min timer").apply { icon = Icon.PLUS; iconSize = 18f; iconStroke = 2.6f; gap = 7f; radius = 22f }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        // padding 0 18 0 14; right 28, top 22, height 44
        val w = addBtn.wrapWidth(14f, 18f)
        place(addBtn, 932f - w, 22f, w, 44)
        addBtn.onTap = { services.timers.start(5 * 60_000L, "Timer") }
        observeWhileAttached(services.timers.timers) { bind(it) }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; applyNight() } }
    }

    override fun onScreenShown(args: Bundle?) {
        shown = true
        if (args?.getString("timers_demo") != null) (services.timers as? RealTimerStore)?.seedDemo()
        ensureBackground()
        bind(services.timers.timers.value)
        tick()
    }

    override fun onScreenHidden() {
        shown = false
        handler.removeCallbacks(ticker)
        cards.values.forEach { it.stopAnim() }
    }

    private fun ensureBackground() {
        if (bg != null) return
        val b = renderBackground()
        bg = b
        // backdrop-filter: blur(20px) saturate(1.8) brightness(1.1), done once.
        backdrop = Backdrop(PreBlur.blur(b, 20f, 4, 1.8f, 1.1f), 0f, 0f, 960f, 480f)
        cards.values.forEach { it.glass.backdrop = backdrop; it.invalidate() }
        addBtn.glass.backdrop = backdrop
        invalidate()
    }

    private fun bind(timers: List<EchoTimer>) {
        list = timers
        val ids = timers.map { it.id }.toSet()
        cards.keys.filter { it !in ids }.forEach { id -> cards.remove(id)?.let { it.stopAnim(); removeView(it) } }
        // Centred row, 280 px cards, gap 24, top 88 (container left/right 28).
        val total = timers.size * 280f + (timers.size - 1).coerceAtLeast(0) * 24f
        var x = 28f + (904f - total) / 2f
        for (t in timers) {
            val card = cards.getOrPut(t.id) { TimerCard(context).also { it.glass.backdrop = backdrop; it.setNight(night) } }
            place(card, x, 88f, 280, 300)
            card.bind(t)
            x += 304f
        }
        addBtn.enabledLook = timers.size < TimerStore.MAX_TIMERS
        invalidate()
        if (shown) tick()
    }

    private fun applyNight() {
        addBtn.glass.night = night
        addBtn.color = if (night) Night.COLOR else Color.WHITE
        addBtn.invalidate()
        cards.values.forEach { it.setNight(night) }
        invalidate()
    }

    private val ticker = Runnable { tick() }

    private fun tick() {
        handler.removeCallbacks(ticker)
        if (!shown) return
        val now = SystemClock.elapsedRealtime()
        cards.values.forEach { it.update(now) }
        invalidate() // summary line
        if (list.any { it.state == EchoTimer.State.RUNNING }) {
            // Next whole second of the soonest-ending running timer, so digits flip on time.
            val phase = list.filter { it.state == EchoTimer.State.RUNNING }.minOf { ((it.endsAtElapsed - now) % 1000 + 1000) % 1000 }
            handler.postDelayed(ticker, (if (phase == 0L) 1000L else phase) + 2)
        }
    }

    override fun onDraw(c: Canvas) {
        if (night) c.drawColor(Color.BLACK) else bg?.let { c.drawBitmap(it, 0f, 0f, bgPaint) }
        val white = Night.map(Color.WHITE, night)
        title.draw(c, "Timers", 32f, title.baselineIn(22f, 28f * 1.1f), white)
        summary.draw(c, summaryText(), 32f, summary.baselineIn(22f + 28f * 1.1f + 2f), Night.map(withAlpha(Color.WHITE, 0.78f), night))
        if (list.isEmpty()) {
            val s = "No timers running"
            empty.draw(c, s, 480f - empty.width(s) / 2, empty.baselineIn(200f), Night.map(withAlpha(Color.WHITE, 0.86f), night))
        }
        val h = "Try “Hey Nova, add a 12-minute rice timer”"
        hint.draw(c, h, 480f - hint.width(h) / 2, 480f - 18f - hint.descent, Night.map(withAlpha(Color.WHITE, 0.72f), night))
    }

    private fun summaryText(): String {
        val running = list.count { it.state == EchoTimer.State.RUNNING }
        val done = list.count { it.state == EchoTimer.State.DONE }
        val paused = list.size - running - done
        return listOfNotNull(
            if (running > 0) "$running running" else null,
            if (done > 0) "$done done" else null,
            if (paused > 0) "$paused paused" else null,
        ).joinToString(" · ").ifEmpty { "Nothing running" }
    }

    /** The board's layered background, rendered once at 960×480. */
    private fun renderBackground(): Bitmap {
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

    // --- one timer platter ---

    private inner class TimerCard(context: Context) : AbsLayout(context) {
        val glass = SpecGlassPainter(GlassSpec(
            fill = withAlpha(Color.WHITE, 0.09f), border = withAlpha(Color.WHITE, 0.26f), rimTop = 0.48f, rimTopWidth = 1.5f,
            rimBottom = withAlpha(Color.WHITE, 0.08f), shadow = withAlpha(Color.BLACK, 0.35f), shadowDy = 18f, shadowBlur = 44f,
            specAngle = 160f, specStops = floatArrayOf(0f, 0.36f, 0.6f, 1f), specAlphas = floatArrayOf(0.24f, 0.03f, 0f, 0.06f),
        ))
        private val labelT = SpecText(18f, 700, -0.015f)
        private val totalT = SpecText(14f, 600)
        private val left40 = SpecText(40f, 600, -0.03f)
        private val left32 = SpecText(32f, 600, -0.03f)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f }
        private val oval = RectF()
        private val loc = IntArray(2)
        private val main = CircleButton(context).apply { iconSize = 22f }
        private val cancel = CircleButton(context).apply { iconSize = 20f; iconStroke = 2.8f; icon = Icon.CLOSE }
        private val flash = FlashView(context)
        private val content = object : View(context) { override fun onDraw(c: Canvas) = drawContent(c) }
        private var t: EchoTimer? = null
        private var n = false
        private var shownFrac = 1f
        private var fracAnim: ValueAnimator? = null
        private var text = ""

        init {
            setWillNotDraw(false)
            clipChildren = false
            place(flash, 0f, 0f, 280, 300)
            place(content, 0f, 0f, 280, 300)
            val rowH = labelT.normalLineHeight
            val btnTop = 18f + rowH + 12f + 150f + 14f
            // two 54 px circles, gap 16, centred
            place(main, 140f - 8f - 54f, btnTop, 54, 54)
            place(cancel, 140f + 8f, btnTop, 54, 54)
            main.onTap = {
                t?.let { tm ->
                    when (tm.state) {
                        EchoTimer.State.RUNNING -> services.timers.pause(tm.id)
                        EchoTimer.State.PAUSED -> services.timers.resume(tm.id)
                        EchoTimer.State.DONE -> (services.timers as? RealTimerStore)?.restart(tm.id)
                            ?: run { services.timers.dismiss(tm.id); services.timers.start(tm.durationMs, tm.label) }
                    }
                }
            }
            cancel.onTap = { t?.let { tm -> if (tm.state == EchoTimer.State.DONE) services.timers.dismiss(tm.id) else services.timers.cancel(tm.id) } }
        }

        fun setNight(night: Boolean) { n = night; glass.night = night; styleButtons(); invalidate() }

        fun bind(tm: EchoTimer) {
            val stateChanged = t?.state != tm.state
            t = tm
            styleButtons()
            val done = tm.state == EchoTimer.State.DONE
            glass.fillOverride = if (done) withAlpha(EchoColors.GREEN, 0.24f) else null
            glass.shadowOverride = if (done) withAlpha(EchoColors.GREEN, 0.35f) else null
            if (stateChanged) { fracAnim?.cancel(); shownFrac = frac(SystemClock.elapsedRealtime()) }
            if (done && shown && !n) flash.start() else flash.stop()
            update(SystemClock.elapsedRealtime())
            invalidate()
        }

        private fun styleButtons() {
            val tm = t ?: return
            val done = tm.state == EchoTimer.State.DONE
            val icon = when (tm.state) { EchoTimer.State.RUNNING -> Icon.PAUSE; EchoTimer.State.PAUSED -> Icon.PLAY; EchoTimer.State.DONE -> Icon.REPEAT }
            if (n) {
                main.set(withAlpha(Night.COLOR, 0.35f), Night.COLOR, icon)
                cancel.set(withAlpha(Night.COLOR, 0.16f), Night.COLOR, Icon.CLOSE)
            } else {
                main.set(if (done) EchoColors.GREEN else EchoColors.ORANGE, if (done) 0xFF04210E.toInt() else 0xFF2A1200.toInt(), icon)
                cancel.set(withAlpha(Color.WHITE, 0.16f), Color.WHITE, Icon.CLOSE)
            }
        }

        private fun frac(now: Long): Float {
            val tm = t ?: return 1f
            if (tm.state == EchoTimer.State.DONE) return 1f
            return (tm.remainingMs(now).toFloat() / tm.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

        /** Once a second: new digits; ring transitions linearly over 1 s if it moves ≥ 2 px. */
        fun update(now: Long) {
            val tm = t ?: return
            val newText = if (tm.state == EchoTimer.State.DONE) "Done" else AlarmFormat.countdown(tm.remainingMs(now))
            val target = frac(now)
            val px = abs(target - shownFrac) * 402.1f
            if (px >= 2f && shown && tm.state == EchoTimer.State.RUNNING) {
                fracAnim?.cancel()
                fracAnim = ValueAnimator.ofFloat(shownFrac, target).apply {
                    duration = 1000; interpolator = LinearInterpolator()
                    addUpdateListener { shownFrac = it.animatedValue as Float; content.invalidate() }
                    start()
                }
            } else if (px > 0.01f) { fracAnim?.cancel(); shownFrac = target; content.invalidate() }
            if (newText != text) { text = newText; content.invalidate() }
        }

        fun stopAnim() { fracAnim?.cancel(); fracAnim = null; flash.stop() }

        @Suppress("UNNECESSARY_SAFE_CALL")
        override fun invalidate() { super.invalidate(); (content as View?)?.invalidate() }

        override fun onDraw(c: Canvas) {
            getLocationInWindow(loc)
            glass.draw(c, width.toFloat(), height.toFloat(), 34f, loc[0].toFloat(), loc[1].toFloat())
        }

        /** Label, total, ring and time, above the flash layer. */
        private fun drawContent(c: Canvas) {
            val tm = t ?: return
            val white = Night.map(Color.WHITE, n)
            val rowH = labelT.normalLineHeight
            // label + total on a shared baseline (align-items: baseline)
            val base = labelT.baselineIn(18f, rowH)
            labelT.draw(c, tm.label, 20f, base, white)
            val tot = AlarmFormat.total(tm.durationMs)
            totalT.draw(c, tot, 260f - totalT.width(tot), base, Night.map(withAlpha(Color.WHITE, 0.72f), n))
            // ring: r 64, stroke 9, from 12 o'clock, clockwise
            val top = 18f + rowH + 12f
            val cx = 140f; val cy = top + 75f
            oval.set(cx - 64f, cy - 64f, cx + 64f, cy + 64f)
            trackPaint.color = Night.map(withAlpha(Color.WHITE, 0.18f), n)
            c.drawOval(oval, trackPaint)
            ringPaint.color = Night.map(when (tm.state) {
                EchoTimer.State.DONE -> EchoColors.GREEN
                EchoTimer.State.PAUSED -> withAlpha(Color.WHITE, 0.6f)
                EchoTimer.State.RUNNING -> EchoColors.ORANGE
            }, n)
            if (shownFrac > 0.001f) c.drawArc(oval, -90f, 360f * shownFrac, false, ringPaint)
            val big = if (tm.state != EchoTimer.State.DONE && tm.remainingMs() >= 3_600_000) left32 else left40
            big.draw(c, text, cx - big.width(text) / 2, big.baselineIn(cy - big.normalLineHeight / 2), white)
        }
    }

    /** The done flash: green .24 ↔ .42 every 500 ms (an extra layer animating alpha only). */
    private class FlashView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(EchoColors.GREEN, 0.237f) }
        private val r = RectF()
        private var anim: ValueAnimator? = null
        init { alpha = 0f }
        override fun onDraw(c: Canvas) { r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, 34f, 34f, p) }
        fun start() {
            if (anim != null) return
            anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 500; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; interpolator = Motion.EASE
                addUpdateListener { alpha = it.animatedValue as Float }
                start()
            }
        }
        fun stop() { anim?.cancel(); anim = null; alpha = 0f }
    }
}
