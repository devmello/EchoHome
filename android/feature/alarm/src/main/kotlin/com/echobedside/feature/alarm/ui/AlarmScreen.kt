package com.echobedside.feature.alarm.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.echobedside.core.design.EchoColors
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.AlarmPhase
import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.HaEntity
import com.echobedside.core.model.Weather
import com.echobedside.core.model.WeatherCondition
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.alarm.AlarmFormat
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Alarm.dc.html: Ringing (sunrise glow breathing behind a giant tinted Snooze, left, and a glass
 * Stop, right), Snoozed (live countdown + track, Stop Alarm), Good morning (weather, up next,
 * lamp suggestion, Ring again / Done). Not in the pager: the alarm store opens it when it rings.
 *
 * Follows services.alarms.phase. Debug: `--es screen alarm --es alarm_demo ringing|snoozed|morning`
 * (also `--es demo …` once :app stops treating a string `demo` as the sample-data toggle) shows a
 * state without an alarm; its buttons then just move between the demo states like the design.
 *
 * Night mode: Ringing and Good morning are full colour (it's time to wake up); Snoozed follows
 * Night (red, no glow) because you're back in bed.
 */
@SuppressLint("ViewConstructor")
class AlarmScreen(context: Context, private val services: EchoServices) : AbsLayout(context), EchoScreen {
    private enum class Mode { NONE, RING, SNOOZE, MORNING }

    private var mode = Mode.NONE
    private var demo: Mode? = null
    private var demoSnoozedAt = 0L
    private var label = "Alarm"
    private var snoozeUntil = 0L
    private var snoozeLenMs = 9 * 60_000L
    private var shown = false
    private var night = false
    private val handler = Handler(Looper.getMainLooper())

    private val sky = SkyView(context)
    private val sun = SunView(context)
    private val ringText = RingText()
    private val snoozeText = SnoozeText()
    private val morning = MorningView()

    private val snoozeBtn = GlassButton(context, GlassSpec(
        fill = withAlpha(0xFFFF9500.toInt(), 0.58f), border = withAlpha(Color.WHITE, 0.35f),
        rimTop = 0.6f, rimTopWidth = 1.5f, rimBottom = withAlpha(0xFF782800.toInt(), 0.25f),
        innerGlow = withAlpha(0xFFFFDCA0.toInt(), 0.18f), innerGlowBlur = 24f,
        shadow = withAlpha(0xFF782800.toInt(), 0.45f), shadowDy = 14f, shadowBlur = 40f,
        specAngle = 170f, specStops = floatArrayOf(0f, 0.36f, 0.6f, 1f), specAlphas = floatArrayOf(0.38f, 0.06f, 0f, 0.1f),
    ), SpecText(34f, 700, -0.02f), "Snooze").apply { icon = Icon.SNOOZE; iconSize = 34f; gap = 14f; radius = 56f }

    private val stopBtn = GlassButton(context, plainGlass(0.1f, 0.24f, 0.45f, 0.3f, 0.08f), SpecText(34f, 700, -0.02f), "Stop")
        .apply { icon = Icon.CLOSE; iconSize = 28f; iconStroke = 2.6f; gap = 12f; radius = 56f }

    private val stopAlarmBtn = GlassButton(context, plainGlass(0.1f, 0.24f, 0.45f, 0.3f, null), SpecText(28f, 700, -0.02f), "Stop Alarm")
        .apply { radius = 44f }

    private val ringAgainBtn = GlassButton(context, GlassSpec(
        fill = withAlpha(Color.WHITE, 0.09f), border = withAlpha(Color.WHITE, 0.22f), rimTop = 0.42f, rimBottom = 0,
        shadow = withAlpha(Color.BLACK, 0.45f), shadowDy = 8f, shadowBlur = 24f,
        specAngle = 165f, specStops = floatArrayOf(0f, 0.5f), specAlphas = floatArrayOf(0.26f, 0f),
    ), SpecText(16f, 600), "Ring again").apply { radius = 24f }

    private val doneBtn = GlassButton(context, GlassSpec(
        fill = withAlpha(EchoColors.BLUE, 0.62f), border = withAlpha(Color.WHITE, 0.28f), rimTop = 0.5f, rimBottom = 0,
        shadow = withAlpha(Color.BLACK, 0.45f), shadowDy = 8f, shadowBlur = 24f,
        specAngle = 165f, specStops = floatArrayOf(0f, 0.5f), specAlphas = floatArrayOf(0.3f, 0f),
    ), SpecText(16f, 700), "Done").apply { radius = 24f }

    private val lampBtn = CircleButton(context).apply { icon = Icon.BULB; iconSize = 26f }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        place(sky, 0f, 0f, 960, 480)
        place(sun, -240f, 100f, 1440, 900)
        place(ringText, 0f, 0f, 960, 300)
        place(snoozeText, 0f, 0f, 960, 300)
        place(morning, 0f, 0f, 960, 400)
        // Ringing: grid left 28 right 28 bottom 26, two columns, gap 16, height 112
        place(snoozeBtn, 28f, 342f, 444, 112)
        place(stopBtn, 488f, 342f, 444, 112)
        // Snoozed: 420×88 centred, bottom 30
        place(stopAlarmBtn, 270f, 362f, 420, 88)
        // Good morning: bottom 30, height 48
        place(ringAgainBtn, 48f, 402f, ringAgainBtn.wrapWidth(20f, 20f), 48)
        val dw = doneBtn.wrapWidth(24f, 24f)
        place(doneBtn, 912f - dw, 402f, dw, 48)
        morning.place(lampBtn, 0f, 0f, 56, 56) // positioned in MorningView.layoutCards
        ringText.setLayerType(LAYER_TYPE_HARDWARE, null) // the text shadow is rasterised once, not per glow frame

        snoozeBtn.onTap = { if (demo != null) { demo = Mode.SNOOZE; demoSnoozedAt = System.currentTimeMillis(); refresh() } else services.alarms.snooze() }
        stopBtn.onTap = { if (demo != null) { demo = Mode.MORNING; refresh() } else services.alarms.stop() }
        stopAlarmBtn.onTap = stopBtn.onTap
        // Ring again: snooze from Good morning (in the demo it rings at once, like the design).
        ringAgainBtn.onTap = { if (demo != null) { demo = Mode.RING; refresh() } else services.alarms.snooze() }
        doneBtn.onTap = { if (demo != null) { demo = null; services.navigator.home() } else services.alarms.dismissGoodMorning() }
        lampBtn.onTap = { morning.toggleLamp() }

        observeWhileAttached(services.alarms.phase) { refresh() }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; refresh() } }
        observeWhileAttached(services.weather.weather) { morning.invalidate() }
        observeWhileAttached(services.calendar.events) { morning.invalidate() }
        observeWhileAttached(services.homeAssistant.entities) { morning.updateLamp() }
    }

    private fun plainGlass(fill: Float, border: Float, rim: Float, spec0: Float, spec3: Float?) = GlassSpec(
        fill = withAlpha(Color.WHITE, fill), border = withAlpha(Color.WHITE, border), rimTop = rim, rimTopWidth = 1.5f,
        rimBottom = withAlpha(Color.WHITE, 0.08f), shadow = withAlpha(Color.BLACK, 0.45f), shadowDy = 14f, shadowBlur = 40f,
        specAngle = 170f,
        specStops = if (spec3 != null) floatArrayOf(0f, 0.36f, 0.6f, 1f) else floatArrayOf(0f, 0.36f, 0.6f),
        specAlphas = if (spec3 != null) floatArrayOf(spec0, 0.04f, 0f, spec3) else floatArrayOf(spec0, 0.04f, 0f),
    )

    // --- state ---

    override fun onScreenShown(args: Bundle?) {
        shown = true
        if (args != null) {
            val d = (args.get("alarm_demo") ?: args.get("demo")) as? String
            demo = when (d) { "ringing", "ring" -> Mode.RING; "snoozed", "snooze" -> Mode.SNOOZE; "morning" -> Mode.MORNING; else -> null }
            if (demo == Mode.SNOOZE) demoSnoozedAt = System.currentTimeMillis() - 12_000 // like the board, a few seconds in
        }
        refresh()
    }

    override fun onScreenHidden() {
        shown = false
        handler.removeCallbacks(ticker)
        sun.stopBreathing()
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val d = demo
        if (d != null) {
            mode = d
            label = "Wake Up"
            snoozeLenMs = 9 * 60_000L
            snoozeUntil = demoSnoozedAt + snoozeLenMs
            if (d == Mode.SNOOZE && now >= snoozeUntil) { demo = Mode.RING; mode = Mode.RING }
        } else {
            when (val p = services.alarms.phase.value) {
                is AlarmPhase.Ringing -> { mode = Mode.RING; label = p.alarm.label }
                is AlarmPhase.Snoozed -> { mode = Mode.SNOOZE; label = p.alarm.label; snoozeUntil = p.untilMillis; snoozeLenMs = p.alarm.snoozeMinutes * 60_000L }
                is AlarmPhase.GoodMorning -> mode = Mode.MORNING
                else -> {
                    mode = Mode.NONE
                    // Nothing to show (stopped elsewhere, or Done): back to StandBy.
                    if (shown && services.navigator.current.value == "alarm") services.navigator.home()
                }
            }
        }
        val snoozeNight = night && mode == Mode.SNOOZE
        ringText.visibility = vis(mode == Mode.RING)
        snoozeBtn.visibility = vis(mode == Mode.RING)
        stopBtn.visibility = vis(mode == Mode.RING)
        snoozeText.visibility = vis(mode == Mode.SNOOZE)
        stopAlarmBtn.visibility = vis(mode == Mode.SNOOZE)
        stopAlarmBtn.glass.night = snoozeNight
        stopAlarmBtn.color = if (snoozeNight) Night.COLOR else Color.WHITE
        morning.visibility = vis(mode == Mode.MORNING)
        ringAgainBtn.visibility = vis(mode == Mode.MORNING)
        doneBtn.visibility = vis(mode == Mode.MORNING)
        if (mode == Mode.MORNING) morning.updateLamp()

        // Glow (design: sunOpacity / sunScale / skyOpacity), 1.2 s transitions between states.
        when (mode) {
            Mode.RING -> sun.to(1f, 1f, breathe = shown)
            Mode.SNOOZE -> sun.to(if (snoozeNight) 0f else 0.32f, 0.9f, false)
            Mode.MORNING -> sun.to(0.55f, 0.9f, false)
            Mode.NONE -> sun.to(0f, 0.9f, false)
        }
        sky.animate().alpha(when (mode) { Mode.MORNING -> 1f; Mode.NONE -> 0f; else -> if (snoozeNight) 0f else 0.4f }).setDuration(1200).setInterpolator(Motion.EASE).start()
        if (!shown) sun.stopBreathing()

        ringText.invalidate(); snoozeText.invalidate(); morning.invalidate()
        handler.removeCallbacks(ticker)
        if (shown && mode != Mode.NONE) scheduleTick()
    }

    /** Snoozed: every second (countdown). Otherwise on the minute (clock). */
    private fun scheduleTick() {
        val now = System.currentTimeMillis()
        val delay = if (mode == Mode.SNOOZE) 1000 - now % 1000 else 60_000 - now % 60_000
        handler.postDelayed(ticker, delay + 5)
    }

    private val ticker = Runnable {
        if (mode == Mode.SNOOZE && demo == Mode.SNOOZE && System.currentTimeMillis() >= snoozeUntil) { refresh(); return@Runnable }
        ringText.invalidate(); snoozeText.invalidate(); morning.invalidate()
        scheduleTick()
    }

    private fun vis(b: Boolean) = if (b) View.VISIBLE else View.GONE

    private fun clockText(): String = if (demo == Mode.RING) "7:00" else AlarmFormat.clock(context, System.currentTimeMillis())

    // --- Ringing text: label row, 200 px time, date ---

    private inner class RingText : View(context) {
        private val lab = SpecText(19f, 600, -0.01f)
        private val clock = SpecText(200f, 600, -0.045f).apply {
            // text-shadow: 0 6px 40px rgba(120,40,0,.35)
            paint.setShadowLayer(34f, 0f, 6f, withAlpha(0xFF782800.toInt(), 0.35f))
        }
        private val date = SpecText(20f, 600, -0.01f)

        override fun onDraw(c: Canvas) {
            val rowTop = 34f
            val rowH = maxOf(20f, lab.normalLineHeight)
            val labW = lab.width(label)
            val x0 = 480f - (20f + 8f + labW) / 2
            val ink = withAlpha(Color.WHITE, 0.86f)
            Icons.draw(c, Icon.ALARM, x0 + 10f, rowTop + rowH / 2, 20f, ink, 2.2f)
            lab.draw(c, label, x0 + 28f, lab.baselineIn(rowTop, rowH), ink)
            val clockTop = rowTop + rowH + 4 + 6
            val t = clockText()
            clock.draw(c, t, 480f - clock.width(t) / 2, clock.baselineIn(clockTop, 200f * 0.86f), Color.WHITE)
            val dateTop = clockTop + 172f + 4 + 6
            val dl = AlarmFormat.dateLine(System.currentTimeMillis())
            date.draw(c, dl, 480f - date.width(dl) / 2, date.baselineIn(dateTop), withAlpha(Color.WHITE, 0.78f))
        }
    }

    // --- Snoozed: label, countdown, track ---

    private inner class SnoozeText : View(context) {
        private val lab = SpecText(19f, 600, -0.01f)
        private val count = SpecText(196f, 600, -0.045f)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis()
            val remaining = (snoozeUntil - now).coerceAtLeast(0)
            val n = night
            val s = "Snoozed · rings again at ${AlarmFormat.clock(context, snoozeUntil)}"
            lab.draw(c, s, 480f - lab.width(s) / 2, lab.baselineIn(48f), Night.map(withAlpha(Color.WHITE, 0.72f), n))
            val top = 48f + lab.normalLineHeight + 18f
            val cd = AlarmFormat.countdown(remaining)
            count.draw(c, cd, 480f - count.width(cd) / 2, count.baselineIn(top, 196f * 0.86f), Night.map(EchoColors.ORANGE, n))
            val ty = top + 196f * 0.86f + 26f
            r.set(300f, ty, 660f, ty + 6f)
            track.color = Night.map(withAlpha(Color.WHITE, 0.14f), n)
            c.drawRoundRect(r, 3f, 3f, track)
            val pct = if (snoozeLenMs > 0) (1f - remaining.toFloat() / snoozeLenMs).coerceIn(0f, 1f) else 0f
            if (pct > 0f) {
                r.set(300f, ty, 300f + 360f * pct, ty + 6f)
                track.color = Night.map(EchoColors.ORANGE, n)
                c.drawRoundRect(r, 3f, 3f, track)
            }
        }
    }

    // --- Good morning ---

    private inner class MorningView : AbsLayout(context) {
        private val date = SpecText(20f, 600, -0.01f)
        private val title = SpecText(76f, 700, -0.035f)
        private val clock = SpecText(64f, 600, -0.04f)
        private val cardTitle = SpecText(15f, 600)
        private val big = SpecText(48f, 600, -0.03f)
        private val sub = SpecText(15f, 500)
        private val event = SpecText(24f, 700, -0.02f)
        private val lampName = SpecText(22f, 700, -0.02f)
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(Color.WHITE, 0.08f) }
        private val r = RectF()

        private var lampEntity: HaEntity? = null
        private var demoLampOn = true
        private val lampShown get() = services.config.secrets.haConfigured && lampEntity != null || demoLamp
        private val demoLamp get() = !services.config.secrets.haConfigured && (demo != null || services.config.demoData)

        init { setWillNotDraw(false) }

        fun updateLamp() {
            lampEntity = if (!services.config.secrets.haConfigured) null else {
                val ents = services.homeAssistant.entities.value
                val chosen = services.settings.getString("alarm.lamp.entity", "")
                ents[chosen] ?: ents.values.filter { it.domain == "light" }
                    .firstOrNull { it.friendlyName.contains("bedside", true) } ?: ents.values.firstOrNull { it.domain == "light" && it.friendlyName.contains("lamp", true) }
            }
            val on = if (demoLamp) demoLampOn else lampEntity?.state == "on"
            lampBtn.visibility = if (lampShown) VISIBLE else GONE
            lampBtn.set(if (on) 0xFFFFB340.toInt() else withAlpha(Color.WHITE, 0.14f), if (on) 0xFF3A1A00.toInt() else Color.WHITE, Icon.BULB)
            layoutCards()
            invalidate()
        }

        fun toggleLamp() {
            if (demoLamp) { demoLampOn = !demoLampOn; updateLamp(); return }
            val e = lampEntity ?: return
            services.scope.launch {
                try { services.homeAssistant.callService("light", "toggle", e.entityId) } catch (_: Exception) {}
            }
        }

        private fun cardCount() = if (lampShown) 3 else 2
        private fun cardW() = (864f - 16f * (cardCount() - 1)) / cardCount()

        private fun layoutCards() {
            if (cardCount() == 3) {
                val x = 48f + 2 * (cardW() + 16f)
                place(lampBtn, x + cardW() - 22f - 56f, 186f + 150f - 20f - 56f, 56, 56)
            }
        }

        override fun onDraw(c: Canvas) {
            val now = System.currentTimeMillis()
            date.draw(c, AlarmFormat.dateLine(now), 48f, date.baselineIn(44f), withAlpha(Color.WHITE, 0.7f))
            title.draw(c, "Good morning", 48f, title.baselineIn(44f + date.normalLineHeight + 4f, 76f), Color.WHITE)
            val t = clockText().let { if (demo != null) "7:12" else it }
            clock.draw(c, t, 912f - clock.width(t), clock.baselineIn(50f, 64f), withAlpha(Color.WHITE, 0.92f))

            val cw = cardW()
            val sec = withAlpha(Color.WHITE, 0.7f)
            for (i in 0 until cardCount()) {
                val x = 48f + i * (cw + 16f)
                r.set(x, 186f, x + cw, 336f)
                c.drawRoundRect(r, 28f, 28f, cardPaint)
            }
            val top = 186f + 20f
            val bottom = 186f + 150f - 20f
            // 1. Weather
            run {
                val x = 48f + 22f
                val w: Weather? = services.weather.weather.value
                val loc = w?.location?.ifEmpty { null } ?: "Weather"
                cardTitle.draw(c, loc, x, cardTitle.baselineIn(top, 30f), sec)
                val (icon, ic) = weatherIcon(w)
                Icons.draw(c, icon, 48f + cw - 22f - 15f, top + 15f, 30f, ic, 2f)
                if (w != null) {
                    val bTop = bottom - (48f + 6f + sub.normalLineHeight)
                    big.draw(c, "${w.temperature}°", x, big.baselineIn(bTop, 48f), Color.WHITE)
                    sub.draw(c, "${AlarmFormat.condition(w.condition, w.isNight)} · H:${w.high}° L:${w.low}°", x, sub.baselineIn(bTop + 54f), sec)
                } else {
                    sub.draw(c, "No forecast yet", x, sub.baselineIn(bottom - sub.normalLineHeight), sec)
                }
            }
            // 2. Up next
            run {
                val x = 48f + cw + 16f + 22f
                cardTitle.draw(c, "Up next", x, cardTitle.baselineIn(top), EchoColors.ORANGE)
                val e: CalendarEvent? = nextEvent(now)
                val l1 = e?.title ?: "Nothing scheduled"
                val l2 = e?.let { ev -> AlarmFormat.eventTime(context, ev.startMillis) + ((ev.location?.ifBlank { null } ?: ev.subtitle.ifBlank { null })?.let { " · $it" } ?: "") }
                    ?: "Your day is clear"
                val bTop = bottom - (24f * 1.15f + 6f + sub.normalLineHeight)
                val maxW = cw - 44f
                event.draw(c, ellipsize(event, l1, maxW), x, event.baselineIn(bTop, 24f * 1.15f), Color.WHITE)
                sub.draw(c, ellipsize(sub, l2, maxW), x, sub.baselineIn(bTop + 24f * 1.15f + 6f), sec)
            }
            // 3. Morning scene (lamp suggestion; only with Home Assistant, or in the demo)
            if (cardCount() == 3) {
                val x = 48f + 2 * (cw + 16f) + 22f
                cardTitle.draw(c, "Morning scene", x, cardTitle.baselineIn(top), sec)
                val name = if (demoLamp) "Bedside lamp" else lampEntity?.friendlyName ?: ""
                val line = when {
                    demoLamp -> if (demoLampOn) "Wake scene · 60%" else "Off"
                    lampEntity?.state == "on" -> lampEntity!!.attributes.optInt("brightness", -1).let { b -> if (b >= 0) "On · ${Math.round(b / 2.55f)}%" else "On" }
                    else -> "Off"
                }
                val bTop = bottom - (lampName.normalLineHeight + 4f + sub.normalLineHeight)
                val maxW = cw - 44f - 56f - 10f
                lampName.draw(c, ellipsize(lampName, name, maxW), x, lampName.baselineIn(bTop), Color.WHITE)
                sub.draw(c, line, x, sub.baselineIn(bTop + lampName.normalLineHeight + 4f), sec)
            }
        }

        private fun nextEvent(now: Long): CalendarEvent? =
            services.calendar.events.value.filter { !it.allDay && it.startMillis >= now }.minByOrNull { it.startMillis }
                ?: services.calendar.events.value.firstOrNull { it.endMillis > now }

        private fun weatherIcon(w: Weather?): Pair<Icon, Int> = when (w?.condition) {
            null, WeatherCondition.CLEAR -> if (w?.isNight == true) Icon.MOON to withAlpha(Color.WHITE, 0.85f) else Icon.SUN to EchoColors.YELLOW
            WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING -> Icon.RAIN to EchoColors.CYAN
            else -> Icon.CLOUD to withAlpha(Color.WHITE, 0.85f)
        }

        private fun ellipsize(t: SpecText, s: String, max: Float): String {
            if (t.width(s) <= max) return s
            var n = s.length
            while (n > 1 && t.width(s.take(n) + "…") > max) n--
            return s.take(n).trimEnd() + "…"
        }
    }
}

/** radial-gradient(ellipse 70% 55% at 50% 0%, rgba(40,60,120,.35), transparent 70%). */
private class SkyView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { alpha = 0f }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val rx = w * 0.70f; val ry = h * 0.55f
        p.shader = RadialGradient(0f, 0f, rx, intArrayOf(withAlpha(0xFF283C78.toInt(), 0.35f), 0), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx); postTranslate(w / 2f, 0f) }) }
    }
    override fun onDraw(c: Canvas) = c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
}

/**
 * The sunrise light blooming from the bottom: a 1440×900 ellipse at (-240, 100) filled with
 * radial-gradient(ellipse at 50% 50%, …) — farthest-corner, so the 100 % ellipse is √2 × the box's
 * half-axes. Drawn once; breathing animates only scale/alpha (RenderNode properties, no re-record).
 */
private class SunView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var breath: ValueAnimator? = null
    private var target = Triple(0f, 0.9f, false)

    init { alpha = 0f; scaleX = 0.9f; scaleY = 0.9f }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val rx = w / 2f * sqrt(2f); val ry = h / 2f * sqrt(2f)
        p.shader = RadialGradient(0f, 0f, rx,
            intArrayOf(0xF2FFC478.toInt(), withAlpha(0xFFFF8228.toInt(), 0.78f), withAlpha(0xFFD23E14.toInt(), 0.45f), withAlpha(0xFF5A141E.toInt(), 0.18f), 0),
            floatArrayOf(0f, 0.22f, 0.42f, 0.6f, 0.72f), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx); postTranslate(w / 2f, h / 2f) }) }
        pivotX = w / 2f; pivotY = h / 2f
    }

    override fun onDraw(c: Canvas) = c.drawOval(0f, 0f, width.toFloat(), height.toFloat(), p)

    fun to(a: Float, s: Float, breathe: Boolean) {
        val t = Triple(a, s, breathe)
        if (t == target && (!breathe || breath != null)) return
        target = t
        stopBreathing()
        visibility = if (a <= 0f && alpha <= 0f) INVISIBLE else VISIBLE
        animate().alpha(a).scaleX(s).scaleY(s).setDuration(1200).setInterpolator(Motion.EASE)
            .withEndAction { if (a <= 0f) visibility = INVISIBLE; if (breathe) startBreathing() }.start()
    }

    /** Ringing: opacity 1 ↔ .82 and scale 1.06 ↔ .97, toggling every second (2 s period). */
    private fun startBreathing() {
        if (breath != null) return
        breath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val s = 1.06f - 0.09f * f
                scaleX = s; scaleY = s
                alpha = 1f - 0.18f * f
            }
            start()
        }
    }

    fun stopBreathing() { breath?.cancel(); breath = null; if (target.third) target = target.copy(third = false) }
}
