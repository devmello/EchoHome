package app.vesper.feature.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.FaceView
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.observeWhileAttached
import java.util.Calendar
import java.util.TimeZone

/**
 * StandBy "Analog" face, from design/Analog.dc.html: Apple's rectangular dial whose minute track
 * (a=446, b=222) and numerals (a=380, b=172) follow a superellipse n=5 (NOTES §5.7), white hands,
 * a second hand in the chosen colour, the date at 9 and the next alarm at 3.
 *
 * Two layers so the per-second cost is only the hands: [Dial] (ticks, numerals, complications)
 * redraws on the minute tick / config change; [Hands] redraws once a second while shown, aligned
 * to the wall-clock second, and stops in [onScreenHidden].
 */
class AnalogFace(context: Context, private val services: VesperServices) : FrameLayout(context), FaceView {
    override val view: View get() = this

    private var config = FaceConfig(color = DEFAULT_COLOR)
    private val dial = Dial(context)
    private val hands = Hands(context)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextAlarm: NextAlarm? = null
    private val cal = Calendar.getInstance()

    /** Far layout (StandBy): 0 normal … 1 far, where the date, alarm and second hand are faded out. */
    private var far = 0f

    private val secondTick = object : Runnable {
        override fun run() {
            if (!running || far >= 1f) return // far: no second hand, the hands move on the minute tick
            hands.invalidate()
            val now = System.currentTimeMillis()
            // next whole second (+4 ms so we land just after it)
            handler.postAtTime(this, SystemClock.uptimeMillis() + (1000 - now % 1000) + 4)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(dial, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hands, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        observeWhileAttached(services.alarms.next) { nextAlarm = it; onTimeChanged() }
    }

    override fun setConfig(config: FaceConfig) {
        if (config == this.config) return
        this.config = config
        dial.invalidate(); hands.invalidate()
    }

    override fun onTimeChanged() {
        dial.refresh()
        hands.invalidate()
    }

    override fun setFarAmount(amount: Float) {
        val a = amount.coerceIn(0f, 1f)
        if (a == far) return
        val wasStopped = far >= 1f
        far = a
        if (config.complications) dial.invalidate()
        hands.invalidate()
        if (wasStopped && a < 1f && running) secondTick.run()
    }

    override fun onScreenShown(args: android.os.Bundle?) {
        if (running) return
        running = true
        secondTick.run()
    }

    override fun onScreenHidden() {
        running = false
        handler.removeCallbacks(secondTick)
    }

    override fun onDetachedFromWindow() {
        onScreenHidden()
        super.onDetachedFromWindow()
    }

    private val night get() = config.night
    private val accent get() = if (night) Night.COLOR else config.color
    private val fg get() = if (night) Night.COLOR else Color.WHITE

    /** Ticks, numerals and complications: static between minutes. */
    private inner class Dial(context: Context) : View(context) {
        private val numeralText = SpecText(46f, 600, -0.02f)
        private val dayShortText = SpecText(15f, 700, 0.08f)
        private val dayNumText = SpecText(30f, 600)
        private val alarmText = SpecText(22f, 600, -0.02f)
        private val alarmIcon = context.icon(R.drawable.ic_alarm_22)
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var dayShort = ""
        private var dayNum = ""
        private var alarmLabel: String? = null

        fun refresh() {
            cal.timeZone = TimeZone.getDefault()
            cal.timeInMillis = System.currentTimeMillis()
            val ds = TimeText.DAYS_SHORT[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val dn = cal.get(Calendar.DAY_OF_MONTH).toString()
            val al = nextAlarm?.let { TimeText.clock(it.triggerAtMillis, android.text.format.DateFormat.is24HourFormat(context), amPm = false) }
            if (ds != dayShort || dn != dayNum || al != alarmLabel) {
                dayShort = ds; dayNum = dn; alarmLabel = al
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 960f, height / 480f)
            for (t in TICKS) {
                tickPaint.color = if (night) (if (t.hour) Night.COLOR else withAlpha(Night.COLOR, 0.55f))
                    else (if (t.hour) Color.WHITE else withAlpha(Color.WHITE, 0.38f))
                canvas.save()
                canvas.rotate(t.rot, t.cx, t.cy)
                rect.set(t.cx - t.w / 2, t.cy - t.len / 2, t.cx + t.w / 2, t.cy + t.len / 2)
                val r = minOf(3f, t.w / 2)
                canvas.drawRoundRect(rect, r, r, tickPaint)
                canvas.restore()
            }
            // numerals: 88×56 boxes centred on the ring, line-height 56, centred text
            for (n in NUMERALS) {
                numeralText.drawCentered(canvas, n.label, n.cx, numeralText.baselineIn(n.cy - 28f, 56f), fg)
            }
            if (config.complications && far < 1f) drawComplications(canvas, 1f - far)
            canvas.restore()
        }

        private fun drawComplications(canvas: Canvas, a: Float) {
            val ac = fade(accent, a)
            val fc = fade(fg, a)
            // Date at 9: column centred in 150..270 from top 214, gap 2
            var y = 214f
            dayShortText.drawCentered(canvas, dayShort, 210f, dayShortText.baselineIn(y), ac)
            y += dayShortText.normalLineHeight + 2f
            dayNumText.drawCentered(canvas, dayNum, 210f, dayNumText.baselineIn(y, 30f), fc)
            // Alarm at 3: icon 18 then the time, gap 4, centred in 690..810
            val label = alarmLabel ?: return
            y = 214f
            drawIcon(canvas, alarmIcon, 750f - 9f, y, 18f, ac)
            y += 18f + 4f
            alarmText.drawCentered(canvas, label, 750f, alarmText.baselineIn(y), fc)
        }
    }

    /** Hour, minute and second hands; the only thing that changes every second. */
    private inner class Hands(context: Context) : View(context) {
        private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val handCal = Calendar.getInstance()

        override fun onDraw(canvas: Canvas) {
            handCal.timeZone = TimeZone.getDefault()
            handCal.timeInMillis = System.currentTimeMillis()
            val h = handCal.get(Calendar.HOUR_OF_DAY)
            val m = handCal.get(Calendar.MINUTE)
            val s = handCal.get(Calendar.SECOND)
            val hourDeg = (h % 12) * 30f + m * 0.5f
            val minDeg = m * 6f + s * 0.1f
            val secDeg = s * 6f

            canvas.save()
            canvas.scale(width / 960f, height / 480f)
            // box-shadow: 0 0 12px rgba(0,0,0,.6) on the hand bodies (sigma 6)
            handPaint.color = fg
            handPaint.setShadowLayer(SHADOW_RADIUS, 0f, 0f, withAlpha(Color.BLACK, 0.6f))
            plain.color = fg
            drawHand(canvas, hourDeg, 132f)
            drawHand(canvas, minDeg, 88f)
            canvas.drawCircle(480f, 240f, 7f, plain)

            // second hand: 3×228 from y=46, pivot 194 px down (= the centre); gone in the far layout
            plain.color = fade(accent, 1f - far)
            canvas.save()
            canvas.rotate(secDeg, 480f, 240f)
            rect.set(478.5f, 46f, 481.5f, 274f)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, plain)
            canvas.restore()
            if (far < 1f) canvas.drawCircle(480f, 240f, 5f, plain)
            plain.color = Color.BLACK
            canvas.drawCircle(480f, 240f, 1.5f, plain)
            canvas.restore()
        }

        /** A 14-wide hand box from [top] to the centre: rounded body to 24 px above the centre, then a 4 px stem. */
        private fun drawHand(canvas: Canvas, deg: Float, top: Float) {
            canvas.save()
            canvas.rotate(deg, 480f, 240f)
            rect.set(474f, top, 486f, 216f)
            canvas.drawRoundRect(rect, 6f, 6f, handPaint)
            rect.set(478f, 210f, 482f, 240f)
            canvas.drawRect(rect, plain)
            canvas.restore()
        }
    }

    private class Tick(val cx: Float, val cy: Float, val w: Float, val len: Float, val rot: Float, val hour: Boolean)
    private class Numeral(val label: String, val cx: Float, val cy: Float)

    companion object {
        val DEFAULT_COLOR = 0xFFFF9F0A.toInt()
        /** Analog.dc.html's colour options (first = default). */
        val COLORS = listOf(0xFFFF9F0A, 0xFFFF453A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFFFFFF).map { it.toInt() }
        /** Skia: sigma = 0.57735·radius + 0.5, so sigma 6 (CSS 12 px) ≈ radius 9.5. */
        private const val SHADOW_RADIUS = 9.5f

        private val TICKS: List<Tick> = (0 until 60).map { i ->
            val hour = i % 5 == 0
            val len = if (hour) 22f else 11f
            val w = if (hour) 5f else 2.5f
            val p = Superellipse.ring(i * 6.0, 446.0, 222.0)
            Tick(p.x - p.nx * len / 2, p.y - p.ny * len / 2, w, len, p.rotDeg, hour)
        }
        private val NUMERALS: List<Numeral> = (1..12).map { h ->
            val p = Superellipse.ring(h * 30.0, 380.0, 172.0)
            Numeral(h.toString(), p.x, p.y)
        }
    }
}
