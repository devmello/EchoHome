package app.vesper.feature.sleep.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.vesper.core.design.Backdrop
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.SleepState
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.sleep.SleepArt
import app.vesper.feature.sleep.SleepController
import app.vesper.feature.sleep.SleepSettings
import app.vesper.feature.sleep.SleepText
import java.time.ZoneId
import kotlin.math.abs

/**
 * The Sleep screen (pager order 15, `--es screen sleep`). No artboard exists for it (NOTES §2.2
 * lists sleep sounds as still to design), so it's built from the system's parts: the Lists /
 * Timers glass over a deep night gradient (pre-blurred once), Inter at the designs' sizes.
 *
 *  - Left: seven sound tiles (glass controls): the nature row (Rain, Ocean, Fan, Crickets) and the
 *    noise row (White, Pink, Brown). Selected = brighter fill + a ring in the sound's accent.
 *    Stopped, a tap only selects (nothing starts by itself); playing, a tap crossfades to it.
 *  - Right: the timer platter (15 / 30 / 60 / 90 / Off as plain fills on the glass, status line),
 *    Play (or Pause/Resume + Stop), and the tinted primary "Good night" (the bedtime routine).
 *  - Footer: what Good night will do, from the settings.
 * Night: black, red text and outlines. Draws only on changes and at the minute.
 *
 * Args: `sound <id>` selects it, `timer <min>` picks the timer. Debug builds also take
 * `state playing|paused` (a silent preview: the UI, island and Now Playing show it, no audio)
 * and `goodnight preview` (shows the routine's answer line without running it).
 */
@SuppressLint("ViewConstructor")
class SleepScreen internal constructor(
    context: Context,
    private val services: VesperServices,
    private val sleep: SleepController,
) : View(context), VesperScreen {

    private val tTitle = SpecText(28f, 800, -0.025f)
    private val tSub = SpecText(15f, 600)
    private val tClock = SpecText(28f, 700, -0.02f)
    private val tTile = SpecText(19f, 700, -0.01f)
    private val tTileSub = SpecText(13f, 600)
    private val tLabel = SpecText(15f, 700)
    private val tChip = SpecText(16f, 700)
    private val tStatus = SpecText(14f, 600)
    private val tPill = SpecText(19f, 700, -0.01f)
    private val tFoot = SpecText(14f, 600).apply { paint.fontFeatureSettings = "" }

    private val painters = HashMap<String, GlassPainter>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgMatrix = Matrix().apply { setScale(2f, 2f) }
    private val rect = RectF()
    private var bg: Bitmap? = null
    private var backdrop: Backdrop? = null

    private var night = false
    private var shown = false
    private var state = SleepState()
    private var next: NextAlarm? = null
    private var picked: SleepSound = SleepSettings.sound(services.settings)
    private var pickedTimer: Int = SleepSettings.timer(services.settings)
    /** A short line after Good night (replaces the subtitle), and when it goes. */
    private var flash: String? = null
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var pressed: RectF? = null
    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = Runnable { invalidate(); scheduleMinute() }
    private val goHome = Runnable { flash = null; services.navigator.home() }

    private val icons: Map<SleepSound, Glyph> = mapOf(
        SleepSound.RAIN to icon("M7 15a4 4 0 0 1-.6-7.95A5.5 5.5 0 0 1 17 8a3.5 3.5 0 0 1 .5 7H7M8.5 18.5l-1 2.5M12.5 18.5l-1 2.5M16.5 18.5l-1 2.5"),
        SleepSound.OCEAN to icon("M2 8c2 0 2-1.5 4-1.5S8 8 10 8s2-1.5 4-1.5S16 8 18 8s2-1.5 4-1.5M2 13c2 0 2-1.5 4-1.5S8 13 10 13s2-1.5 4-1.5S16 13 18 13s2-1.5 4-1.5M2 18c2 0 2-1.5 4-1.5S8 18 10 18s2-1.5 4-1.5S16 18 18 18s2-1.5 4-1.5"),
        SleepSound.FAN to icon("M12 10.2C10.6 6.6 11.2 3 13.7 2.6c2.6-.4 3 3.4-1.7 7.6M13.6 12.9c3.8.6 6.6 2.9 5.7 5.3-.9 2.4-4.4 1-5.7-5.3M10.4 12.9c-2.4 3-5.8 4.3-7.4 2.3-1.6-2 1.4-4.3 7.4-2.3M12 12h.01"),
        SleepSound.CRICKETS to icon("M19.5 14.2A7.8 7.8 0 1 1 9.8 4.5a6.2 6.2 0 0 0 9.7 9.7M17 3v2.4M15.8 4.2h2.4M20.6 7.6v1.6M19.8 8.4h1.6"),
        SleepSound.WHITE to icon("M2 12h1.6l1.6-7 2 14 2-11 2 8 2-13 2 15 2-10 1.6 4H22"),
        SleepSound.PINK to icon("M2 12h2l2.5-6.5 3 13 3-10 3 7 2.5-3.5H22"),
        SleepSound.BROWN to icon("M2 12c2.5-6 5-6 7.5 0s5 6 7.5 0c1.4-3.3 3-4 5-3"),
    )
    private val icMoon = icon("M20.5 13.6A8.6 8.6 0 1 1 10.4 3.5a6.8 6.8 0 0 0 10.1 10.1")
    private val icStop = Path().apply { addRoundRect(RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, Path.Direction.CW) }
    private val icPlay = Path().apply { moveTo(7.5f, 4.8f); lineTo(19.2f, 12f); lineTo(7.5f, 19.2f); close() }
    private val icPause = Path().apply { addRoundRect(RectF(6f, 5f, 10f, 19f), 1.5f, 1.5f, Path.Direction.CW); addRoundRect(RectF(14f, 5f, 18f, 19f), 1.5f, 1.5f, Path.Direction.CW) }

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(sleep.state) { st ->
            state = st
            st.sound?.let { picked = it }
            if (st.sound != null) pickedTimer = st.timerMinutes
            invalidate()
        }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; painters.values.forEach { it.night = night }; invalidate() } }
        observeWhileAttached(services.alarms.next) { next = it; invalidate() }
        observeWhileAttached(services.settings.changes) { k -> if (k.startsWith("sleep.")) invalidate() }
    }

    // ---------------- lifecycle ----------------

    override fun onScreenShown(args: Bundle?) {
        shown = true
        ensureBackground()
        args?.getString("sound")?.let { id -> SleepSound.of(id)?.let { s -> if (state.sound != null) sleep.switchTo(s) else picked = s } }
        args?.getString("timer")?.toIntOrNull()?.takeIf { it in SleepSounds.TIMER_CHOICES }?.let { pickTimer(it) }
        if (services.config.debug) debugArgs(args)
        invalidate()
        scheduleMinute()
    }

    override fun onScreenHidden() {
        shown = false
        pressed = null
        handler.removeCallbacks(minuteTick)
        if (flash != null) { handler.removeCallbacks(goHome); flash = null }
    }

    private fun scheduleMinute() {
        handler.removeCallbacks(minuteTick)
        if (!shown) return
        val now = System.currentTimeMillis()
        handler.postDelayed(minuteTick, 60_000L - now % 60_000L + 50L)
    }

    private fun ensureBackground() {
        if (bg != null) return
        val b = renderBackground()
        bg = b
        val bd = Backdrop(PreBlur.blur(b, 10f, 2), 0f, 0f, 960f, 480f)
        backdrop = bd
        painters.values.forEach { it.backdrop = bd }
    }

    /** Deep indigo night with a moonlit glow top right and a teal one low left; 480×240, drawn ×2. */
    private fun renderBackground(): Bitmap {
        val w = 480f; val h = 240f
        val b = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w * 0.4f, h, 0xFF0D1233.toInt(), 0xFF05060F.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        fun blob(cx: Float, cy: Float, r: Float, color: Int, a: Float) {
            p.shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(color, a), color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
        }
        blob(0.86f * w, 0.02f * h, 0.42f * w, 0xFF5E5CE6.toInt(), 0.42f)
        blob(0.10f * w, 1.00f * h, 0.40f * w, 0xFF1B6F8A.toInt(), 0.36f)
        blob(0.48f * w, 0.55f * h, 0.30f * w, 0xFF2A2F7A.toInt(), 0.28f)
        return b
    }

    private fun glass(key: String, tint: Int? = null): GlassPainter = painters.getOrPut(key) {
        GlassPainter().also { it.backdrop = backdrop; it.night = night; it.tint = tint }
    }

    // ---------------- drawing ----------------

    override fun onDraw(c: Canvas) {
        hits.clear()
        if (night) c.drawColor(Color.BLACK) else bg?.let { c.drawBitmap(it, bgMatrix, bgPaint) }
        val zone = ZoneId.systemDefault()
        val h24 = DateFormat.is24HourFormat(context)
        val nowWall = System.currentTimeMillis()
        val nowEl = SystemClock.elapsedRealtime()

        // header
        tTitle.draw(c, "Sleep", 32f, tTitle.baselineIn(22f, 28f * 1.1f), ink(Color.WHITE))
        val sub = flash ?: subtitle()
        tSub.draw(c, fit(tSub, sub, 560f), 32f, tSub.baselineIn(57f), ink(withAlpha(Color.WHITE, if (flash != null) 0.95f else 0.72f)))
        val clock = SleepText.clock(nowWall, zone, h24)
        tClock.draw(c, clock, 928f - tClock.width(clock), tClock.baselineIn(22f, 28f * 1.1f), ink(Color.WHITE))
        val alarm = next?.let { "Alarm " + SleepText.clock(it.triggerAtMillis, zone, h24) } ?: "No alarm"
        tSub.draw(c, alarm, 928f - tSub.width(alarm), tSub.baselineIn(57f), ink(withAlpha(Color.WHITE, 0.72f)))

        // tiles
        NATURE.forEachIndexed { i, s -> tile(c, s, 28f + i * 150f, 96f, 138f) }
        NOISE.forEachIndexed { i, s -> tile(c, s, 28f + i * 200f, 244f, 188f, row = 2) }

        drawTimer(c, nowEl, nowWall, zone, h24)
        drawTransport(c)
        drawGoodNight(c)

        // footer: what Good night will do
        val st = services.settings
        val until = (next?.triggerAtMillis)?.let { SleepText.clock(it, zone, h24) }
            ?: SleepSettings.wakeFallback(st).let { (hh, mm) -> SleepText.clock(java.time.LocalDate.now(zone).atTime(hh, mm).atZone(zone).toInstant().toEpochMilli(), zone, h24) }
        val foot = SleepText.routinePreview(SleepSettings.bedtimeDnd(st), SleepSettings.bedtimeNight(st), SleepSettings.bedtimeDim(st),
            if (SleepSettings.bedtimeSound(st)) SleepSettings.sound(st) else null, SleepSettings.timer(st), until)
        tFoot.draw(c, fit(tFoot, foot, 900f), 32f, 480f - 30f - tFoot.descent, ink(withAlpha(Color.WHITE, 0.6f)))
    }

    private fun subtitle(): String {
        val s = state.sound ?: return "Pick a sound and a timer"
        return when {
            state.playing -> "${s.title} is playing"
            else -> "${s.title} is paused"
        }
    }

    private fun tile(c: Canvas, s: SleepSound, x: Float, y: Float, w: Float, row: Int = 1) {
        val h = 136f
        val sel = s == picked
        val loaded = state.sound == s
        val r = RectF(x, y, x + w, y + h)
        pressScale(c, r) {
            (if (sel) glass("tile${row}sel", withAlpha(Color.WHITE, 0.18f)) else glass("tile$row")).draw(c, x, y, x + w, y + h, 28f)
            if (sel) {
                stroke.color = ink(withAlpha(SleepArt.accent(s), if (night) 0.9f else 0.85f)); stroke.strokeWidth = 2f
                rect.set(x + 1f, y + 1f, x + w - 1f, y + h - 1f)
                c.drawRoundRect(rect, 27f, 27f, stroke)
            }
            val iconColor = if (sel) SleepArt.accent(s) else withAlpha(Color.WHITE, 0.85f)
            drawIcon(c, icons.getValue(s), x + 38f, y + 40f, 32f, ink(iconColor), 2.1f)
            val subText = when { loaded && state.playing -> "Playing"; loaded -> "Paused"; else -> "" }
            val nameTop = y + h - 18f - tTile.normalLineHeight - if (subText.isNotEmpty()) tTileSub.normalLineHeight else 0f
            tTile.draw(c, fit(tTile, s.title, w - 36f), x + 20f, tTile.baselineIn(nameTop), ink(Color.WHITE))
            if (subText.isNotEmpty()) {
                val sy = tTileSub.baselineIn(nameTop + tTile.normalLineHeight)
                if (state.playing) { paint.style = Paint.Style.FILL; paint.color = ink(SleepArt.accent(s)); c.drawCircle(x + 24f, sy - 4.5f, 3.5f, paint) }
                tTileSub.draw(c, subText, x + 20f + if (state.playing) 12f else 0f, sy, ink(withAlpha(Color.WHITE, 0.7f)))
            }
        }
        hit(r) { tapSound(s) }
    }

    private fun drawTimer(c: Canvas, nowEl: Long, nowWall: Long, zone: ZoneId, h24: Boolean) {
        val x = RIGHT_X; val y = 96f; val r = 932f; val b = 232f
        glass("platter").draw(c, x, y, r, b, 28f)
        tLabel.draw(c, "Sleep timer", x + 20f, tLabel.baselineIn(y + 16f), ink(withAlpha(Color.WHITE, 0.72f)))
        val n = SleepSounds.TIMER_CHOICES.size
        val gap = 8f
        val cw = (r - x - 40f - gap * (n - 1)) / n
        val active = if (state.sound != null) state.timerMinutes else pickedTimer
        SleepSounds.TIMER_CHOICES.forEachIndexed { i, m ->
            val cx = x + 20f + i * (cw + gap)
            val cr = RectF(cx, y + 44f, cx + cw, y + 88f)
            val on = m == active
            pressScale(c, cr) {
                // plain fills on the glass platter (no glass on glass)
                paint.style = Paint.Style.FILL
                paint.color = if (night) (if (on) Night.COLOR else withAlpha(Night.COLOR, 0.16f)) else if (on) withAlpha(Color.WHITE, 0.92f) else withAlpha(Color.WHITE, 0.12f)
                c.drawRoundRect(cr, 22f, 22f, paint)
                val label = if (m == 0) "Off" else "$m"
                val color = if (on) (if (night) Color.BLACK else 0xFF15172B.toInt()) else ink(Color.WHITE)
                tChip.draw(c, label, cr.centerX() - tChip.width(label) / 2f, cr.centerY() - tChip.normalLineHeight / 2f + tChip.ascent, color)
            }
            hit(cr) { pickTimer(m) }
        }
        val status = SleepText.timerStatus(state, pickedTimer, nowEl, nowWall, zone, h24, sleep.pausedRemainingMs)
        tStatus.draw(c, fit(tStatus, status, r - x - 40f), x + 20f, tStatus.baselineIn(y + 102f), ink(withAlpha(Color.WHITE, 0.72f)))
    }

    private fun drawTransport(c: Canvas) {
        val y = 244f; val b = 304f
        if (state.sound == null) {
            val r = RectF(RIGHT_X, y, 932f, b)
            pressScale(c, r) {
                glass("pill").draw(c, r.left, r.top, r.right, r.bottom)
                pillLabel(c, r, icPlay, "Play ${picked.title}", filledIcon = true)
            }
            hit(r) { sleep.play(picked, pickedTimer) }
        } else {
            val half = (932f - RIGHT_X - 12f) / 2f
            val l = RectF(RIGHT_X, y, RIGHT_X + half, b)
            val rr = RectF(932f - half, y, 932f, b)
            pressScale(c, l) {
                glass("half").draw(c, l.left, l.top, l.right, l.bottom)
                pillLabel(c, l, if (state.playing) icPause else icPlay, if (state.playing) "Pause" else "Resume", filledIcon = true)
            }
            hit(l) { if (state.playing) sleep.pause() else sleep.resume() }
            pressScale(c, rr) {
                glass("half").draw(c, rr.left, rr.top, rr.right, rr.bottom)
                pillLabel(c, rr, icStop, "Stop", filledIcon = true)
            }
            hit(rr) { sleep.stop() }
        }
    }

    private fun drawGoodNight(c: Canvas) {
        val r = RectF(RIGHT_X, 320f, 932f, 380f)
        pressScale(c, r) {
            // the one tinted (primary) action; Night mode: red outline + a faint red fill
            if (night) { paint.style = Paint.Style.FILL; paint.color = withAlpha(Night.COLOR, 0.22f); c.drawRoundRect(r, 30f, 30f, paint) }
            glass("goodnight", VesperColors.PRIMARY).draw(c, r.left, r.top, r.right, r.bottom)
            pillLabel(c, r, null, "Good night", moon = true)
        }
        hit(r) { goodNight() }
    }

    private fun pillLabel(c: Canvas, r: RectF, path: Path?, label: String, filledIcon: Boolean = false, moon: Boolean = false) {
        val iconW = 22f
        val tw = tPill.width(fit(tPill, label, r.width() - 80f))
        val total = iconW + 10f + tw
        val x0 = r.centerX() - total / 2f
        val color = ink(Color.WHITE)
        if (moon) drawIcon(c, icMoon, x0 + iconW / 2f, r.centerY(), iconW, color, 2.2f)
        else if (path != null && filledIcon) {
            c.save(); c.translate(x0, r.centerY() - iconW / 2f); c.scale(iconW / 24f, iconW / 24f)
            paint.style = Paint.Style.FILL; paint.color = color; c.drawPath(path, paint); c.restore()
        }
        tPill.draw(c, fit(tPill, label, r.width() - 80f), x0 + iconW + 10f, r.centerY() - tPill.normalLineHeight / 2f + tPill.ascent, color)
    }

    // ---------------- actions ----------------

    private fun tapSound(s: SleepSound) {
        if (state.sound != null) sleep.switchTo(s) else { picked = s; invalidate() }
    }

    private fun pickTimer(m: Int) {
        if (state.sound != null) sleep.setTimer(m) else { pickedTimer = m; invalidate() }
    }

    private fun goodNight() {
        val report = sleep.bedtime()
        showFlash(SleepText.goodNight(report, ZoneId.systemDefault(), DateFormat.is24HourFormat(context)), home = true)
    }

    private fun showFlash(text: String, home: Boolean) {
        flash = text
        invalidate()
        handler.removeCallbacks(goHome)
        if (home) handler.postDelayed(goHome, 3_000L)
    }

    // ---------------- helpers ----------------

    private fun ink(color: Int) = Night.map(color, night)

    private fun hit(r: RectF, action: () -> Unit) { hits += RectF(r) to action }

    private inline fun pressScale(c: Canvas, r: RectF, draw: () -> Unit) {
        val p = pressed
        if (p != null && p.left == r.left && p.top == r.top && p.right == r.right) {
            c.save(); c.scale(0.96f, 0.96f, r.centerX(), r.centerY()); draw(); c.restore()
        } else draw()
    }

    private val fitCache = HashMap<String, String>()
    private fun fit(t: SpecText, s: String, maxW: Float): String {
        if (maxW <= 0f) return ""
        return fitCache.getOrPut("${t.size}|${maxW.toInt()}|$s") {
            if (t.width(s) <= maxW) s else {
                var n = s.length
                while (n > 1 && t.width(s.substring(0, n).trimEnd() + "…") > maxW) n--
                s.substring(0, n).trimEnd() + "…"
            }
        }.also { if (fitCache.size > 200) fitCache.clear() }
    }

    private class Glyph(val path: Path)

    private fun icon(d: String): Glyph {
        val p = Path()
        SvgPath.parse(d, object : PathSink {
            override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
            override fun close() = p.close()
        })
        return Glyph(p)
    }

    private fun drawIcon(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, color: Int, strokeW: Float) {
        val s = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(s, s)
        stroke.color = color
        stroke.strokeWidth = strokeW / s
        c.drawPath(g.path, stroke)
        c.restore()
    }

    // ---------------- touch ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                pressed = hits.firstOrNull { it.first.contains(e.x, e.y) }?.first
                if (pressed != null) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != null && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) { pressed = null; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (p != null) {
                    invalidate()
                    hits.firstOrNull { it.first.left == p.left && it.first.top == p.top && it.first.right == p.right }?.second?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (pressed != null) { pressed = null; invalidate() }; return true }
        }
        return super.onTouchEvent(e)
    }

    // ---------------- debug ----------------

    private fun debugArgs(args: Bundle?) {
        args ?: return
        when (args.getString("state")) {
            // silent: no AudioTrack, no focus (the user asked for a quiet Echo; review by screenshot)
            "playing" -> sleep.start(picked, pickedTimer, silent = true)
            "paused" -> { sleep.start(picked, pickedTimer, silent = true); sleep.pause() }
            "stopped" -> sleep.stop()
        }
        if (args.getString("goodnight") == "preview") {
            val at = next?.triggerAtMillis
            showFlash(if (at != null) "Good night. Your alarm is at ${SleepText.clock(at, ZoneId.systemDefault(), DateFormat.is24HourFormat(context))}." else "Good night. No alarm is set.", home = false)
        }
    }

    companion object {
        private const val RIGHT_X = 640f
        private val NATURE = listOf(SleepSound.RAIN, SleepSound.OCEAN, SleepSound.FAN, SleepSound.CRICKETS)
        private val NOISE = listOf(SleepSound.WHITE, SleepSound.PINK, SleepSound.BROWN)
    }
}
