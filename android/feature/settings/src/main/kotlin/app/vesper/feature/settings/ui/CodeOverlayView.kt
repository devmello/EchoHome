package app.vesper.feature.settings.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.VesperColors
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.settings.pair.PairPrompt
import app.vesper.feature.settings.pair.Pairing
import kotlinx.coroutines.flow.combine

/**
 * Overlay "settings-code" (z 650, above Control Center): while a browser pairs, a glass sheet
 * with the 6-digit code and a countdown (2 min), then a short "Paired" confirmation.
 * GONE otherwise, so it draws nothing and never takes touches. While shown it is modal: only
 * Cancel acts. Ticks once a second, only while shown. Night mode: red, no glass.
 */
@SuppressLint("ViewConstructor")
internal class CodeOverlayView(context: Context, private val services: VesperServices, private val pairing: Pairing) : View(context) {
    private val glass = GlassPainter()
    private val title = SpecText(26f, 800, -0.02f)
    private val body = SpecText(17f, 500)
    private val digits = SpecText(88f, 700, 0.02f)
    private val small = SpecText(15f, 600)
    private val button = SpecText(17f, 700)
    private val scrim = Paint().apply { color = withAlpha(Color.BLACK, 0.6f) }
    private val check = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val checkPath = Path()

    private var prompt: PairPrompt? = null
    private var night = false
    private var appear = 1f
    private var animator: ValueAnimator? = null

    private val tick = object : Runnable {
        override fun run() {
            val p = prompt ?: return
            if (!p.paired && SystemClock.elapsedRealtime() >= p.expiresAt) { pairing.expire(); return }
            invalidate()
            postDelayed(this, 1000L - SystemClock.elapsedRealtime() % 1000L)
        }
    }
    private val hidePaired = Runnable { pairing.dismissPrompt() }

    init {
        visibility = GONE
        observeWhileAttached(combine(pairing.prompt, services.system.state) { p, s -> p to s.nightActive }) { (p, nt) ->
            night = nt; glass.night = nt
            show(p)
        }
    }

    private fun show(p: PairPrompt?) {
        val was = prompt
        prompt = p
        removeCallbacks(tick); removeCallbacks(hidePaired)
        if (p == null) { visibility = GONE; animator?.cancel(); return }
        if (visibility != VISIBLE || was == null) {
            visibility = VISIBLE
            animator?.cancel()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450; interpolator = Motion.SPRING
                addUpdateListener { appear = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        if (p.paired) postDelayed(hidePaired, 1800) else post(tick)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick); removeCallbacks(hidePaired); animator?.cancel()
    }

    private fun c(color: Int) = Night.map(color, night)

    override fun onDraw(canvas: Canvas) {
        val p = prompt ?: return
        val a = appear.coerceIn(0f, 1f)
        scrim.alpha = (153 * a).toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val cx = width / 2f
        val s = 0.92f + 0.08f * appear
        canvas.save()
        canvas.scale(s, s, cx, height / 2f)
        // An almost opaque base: the pair sheet (QR, URL) behind would otherwise show through
        // the glass and fight the code for attention.
        Draw.roundRect(canvas, L, T, R, B, 40f, withAlpha(if (night) Color.BLACK else BASE, 0.9f * a))
        glass.alpha = a
        glass.draw(canvas, L, T, R, B, 40f)

        if (p.paired) {
            val cy = T + 104f
            Draw.roundRect(canvas, cx - 40f, cy - 40f, cx + 40f, cy + 40f, 40f, c(VesperColors.SUCCESS))
            check.color = if (night) Color.BLACK else Color.WHITE
            checkPath.reset(); checkPath.moveTo(cx - 17f, cy + 1f); checkPath.lineTo(cx - 5f, cy + 13f); checkPath.lineTo(cx + 18f, cy - 12f)
            canvas.drawPath(checkPath, check)
            Draw.centered(canvas, title, "Paired", cx, title.baselineIn(cy + 64f, 34f), c(Color.WHITE))
            for ((i, line) in Draw.wrap(body, "${p.browser} can change the Echo's settings now.", R - L - 80f).withIndex()) {
                Draw.centered(canvas, body, line, cx, body.baselineIn(cy + 104f + i * 24f, 24f), c(withAlpha(Color.WHITE, 0.72f)))
            }
        } else {
            Draw.centered(canvas, title, "Pair ${p.browser}", cx, title.baselineIn(T + 34f, 34f), c(Color.WHITE))
            Draw.centered(canvas, body, "Type this code on the settings page", cx, body.baselineIn(T + 70f, 24f), c(withAlpha(Color.WHITE, 0.72f)))
            val code = p.code.substring(0, 3) + " " + p.code.substring(3)
            Draw.centered(canvas, digits, code, cx, digits.baselineIn(T + 104f, 100f), c(Color.WHITE))
            val left = ((p.expiresAt - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0)
            Draw.centered(canvas, small, "Expires in ${left / 60}:${(left % 60).toString().padStart(2, '0')}", cx,
                small.baselineIn(T + 208f, 20f), c(withAlpha(Color.WHITE, 0.5f)))
            val by = B - 28f - 44f
            Draw.roundRect(canvas, cx - 70f, by, cx + 70f, by + 44f, 22f, c(withAlpha(Color.WHITE, 0.16f)))
            Draw.centered(canvas, button, "Cancel", cx, button.baselineIn(by, 44f), c(Color.WHITE))
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = prompt ?: return false
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val cx = width / 2f
            val by = B - 28f - 44f
            if (!p.paired && e.x in cx - 80f..cx + 80f && e.y in by - 10f..by + 54f) pairing.dismissPrompt()
            if (p.paired) pairing.dismissPrompt()
        }
        return true // modal while shown
    }

    private companion object {
        const val L = 200f
        val BASE = Color.rgb(22, 22, 26)
        const val T = 84f
        const val R = 760f
        const val B = 396f
    }
}
