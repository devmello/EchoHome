package app.vesper.feature.system.hud

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.SystemState
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.system.SystemFeature
import app.vesper.feature.system.SystemSettings
import app.vesper.feature.system.SystemUi
import app.vesper.feature.system.ui.ColorTween
import app.vesper.feature.system.ui.EASE
import app.vesper.feature.system.ui.GlassSpec
import app.vesper.feature.system.ui.Icon
import app.vesper.feature.system.ui.Icons
import app.vesper.feature.system.ui.Snapshot
import app.vesper.feature.system.ui.SysGlassPainter
import app.vesper.feature.system.ui.Tween
import app.vesper.feature.system.ui.baselineCentered
import app.vesper.feature.system.ui.bezier
import app.vesper.feature.system.ui.drawRight
import app.vesper.feature.system.ui.lerp
import kotlin.math.roundToInt

/**
 * System indicators (HUDs.dc.html), overlay z 500. Never takes touches (pointer-events: none).
 *  - The capsule drops from the top edge (under the Echo's buttons): translateY −90 → 16 with
 *    cubic-bezier(0.3, 1.45, 0.5, 1) 0.55 s, scale 0.7 → 1, opacity 0.25 s; width 0.4 s spring;
 *    background 0.3 s; level fill 0.35 s spring.
 *  - Persistent privacy badges top-right while mic / camera are off (hidden while the capsule
 *    talks about the same thing), plus a green camera-in-use badge.
 * Draws nothing at rest: frames run only during a transition.
 */
@SuppressLint("ViewConstructor")
internal class HudOverlay(context: Context, private val ui: SystemUi) : View(context) {
    private val queue = HudQueue()
    private val handler = Handler(Looper.getMainLooper())
    private val glass = SysGlassPainter(GlassSpec.HUD)
    private var state = SystemState()

    // what the capsule shows (kept while it slides away)
    private var shown: Hud? = null
    private val y = Tween(HIDDEN_Y)
    private val scale = Tween(0.7f)
    private val opacity = Tween(0f)
    private val width = Tween(360f)
    private val level = Tween(0f)
    private val bg = ColorTween(LEVEL_BG)

    private val title = SpecText(18f, 700, -0.01f)
    private val sub = SpecText(13f, 600)
    private val pct = SpecText(18f, 700)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val tick = Runnable { advance() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        observeWhileAttached(ui.hud) { post(it) }
        // launch / new-intent debug extras (`--es island story`, `--es cc open`, `--es banner 0`): same path as the broadcast
        if (ui.debugBuild) observeWhileAttached(ui.services.intents) { i ->
            val ex = i.extras ?: return@observeWhileAttached
            if (DEBUG_KEYS.any { ex.containsKey(it) }) ui.debug.value = android.os.Bundle(ex).apply { putLong("_t", System.nanoTime()) }
        }
        // settings page, applied live
        observeWhileAttached(ui.services.settings.changes) { k ->
            when (k) {
                SystemSettings.HUD_SECONDS, SystemSettings.HUD_BADGES -> { readSettings(); invalidate() }
                SystemSettings.NIGHT_MODE -> SystemFeature.applyNightSetting(ui.services)
            }
        }
        observeWhileAttached(ui.services.system.state) { s ->
            val badgesBefore = badges(state)
            state = s
            glass.night = s.nightActive
            if (badges(s) != badgesBefore) invalidate()
        }
        observeWhileAttached(ui.debug) { b ->
            when (b?.getString("hud")) {
                "volume" -> post(Hud.Level(LevelKind.VOLUME, b.getFloat("value", 0.6f)))
                "brightness" -> post(Hud.Level(LevelKind.BRIGHTNESS, b.getFloat("value", 0.45f)))
                "micoff" -> post(Hud.Message(MessageKind.MIC_OFF))
                "micon" -> post(Hud.Message(MessageKind.MIC_ON))
                "camoff" -> post(Hud.Message(MessageKind.CAMERA_OFF_SHUTTER))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (context as? Activity)?.let { ui.activity = it }
        ui.controls.start()
        readSettings()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Poll the shutter/gating latch only while we're on screen.
        ui.monitor.setActive(visibility == VISIBLE)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        ui.monitor.setActive(false)
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = false

    fun post(h: Hud) {
        // With the island on screen, the HUD plays inside it (the capsule would drop onto it).
        if (ui.islandHud?.invoke(h) == true) return
        val now = SystemClock.uptimeMillis()
        val wasShowing = queue.current != null
        queue.post(h, now)
        apply(queue.current, appearing = !wasShowing)
        schedule()
    }

    private fun advance() {
        val now = SystemClock.uptimeMillis()
        if (queue.tick(now)) apply(queue.current, appearing = false)
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(tick)
        val d = queue.deadline() ?: return
        handler.postAtTime(tick, d)
    }

    private fun apply(h: Hud?, appearing: Boolean) {
        if (h == null) {
            // slide back up behind the top edge
            y.to(HIDDEN_Y, 550, DROP)
            scale.to(0.7f, 550, DROP)
            opacity.to(0f, 250, EASE)
            invalidate()
            return
        }
        val targetW = when (h) { is Hud.Level -> 360f; is Hud.Message -> h.kind.widthPx.toFloat() }
        val targetBg = when (h) { is Hud.Level -> LEVEL_BG; is Hud.Message -> h.kind.tint }
        if (appearing || opacity.target == 0f) {
            takeBackdrop()
            width.snap(targetW); bg.snap(targetBg)
            if (h is Hud.Level) level.snap(h.value)
            y.to(SHOWN_Y, 550, DROP); scale.to(1f, 550, DROP); opacity.to(1f, 250, EASE)
        } else {
            width.to(targetW, 400, WIDTH_SPRING)
            bg.to(targetBg, 300)
            if (h is Hud.Level) {
                if (shown !is Hud.Level || (shown as Hud.Level).kind != h.kind) level.snap(h.value) else level.to(h.value, 350, WIDTH_SPRING)
            }
        }
        shown = h
        contentDescription = describe(h)
        announceForAccessibility(contentDescription)
        invalidate()
    }

    private fun takeBackdrop() {
        // The capsule's glass samples what's under it: a tiny pre-blurred snapshot, once per appearance.
        // PixelCopy + blur off the UI thread (the capsule isn't drawn yet, so it isn't in the copy).
        val act = context as? Activity
        val gen = ++backdropGen
        if (act == null) { setBackdrop(Snapshot.region(this, Rect(300, 0, 660, 96), 18f, 1.9f, 1.15f)); return }
        Snapshot.regionAsync(act, Rect(300, 0, 660, 96), 18f, 1.9f, 1.15f) { bd ->
            if (gen == backdropGen && bd != null) setBackdrop(bd) else bd?.bitmap?.recycle()
        }
    }

    private var backdropGen = 0

    private fun setBackdrop(bd: app.vesper.core.design.Backdrop?) {
        val old = glass.backdrop
        glass.backdrop = bd
        if (old !== bd) old?.bitmap?.recycle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Tween.now()
        var running = false
        for (t in arrayOf(y, scale, opacity, width, level)) running = t.step(now) or running
        running = bg.step(now) or running

        drawBadges(canvas)

        val h = shown
        if (h != null && opacity.value > 0.004f) {
            val w = width.value
            val left = 480f - w / 2f
            val top = y.value
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale.value, scale.value, w / 2f, 32f)
            drawCapsule(canvas, h, w, opacity.value, left, top)
            canvas.restore()
        } else if (h != null && !running && opacity.target == 0f) {
            shown = null
            glass.backdrop?.bitmap?.recycle(); glass.backdrop = null
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun drawCapsule(c: Canvas, h: Hud, w: Float, a: Float, wx: Float, wy: Float) {
        val night = state.nightActive
        glass.alpha = a
        glass.fillOverride = if (night) null else bg.value
        glass.draw(c, 0f, 0f, w, 64f, 32f, wx, wy)
        val ink = if (night) withAlpha(Night.COLOR, a) else withAlpha(Color.WHITE, a)
        val icon: Icon = when (h) {
            is Hud.Level -> when (h.kind) { LevelKind.VOLUME -> if (h.muted || h.value <= 0f) Icons.volumeMuted else Icons.volume; LevelKind.BRIGHTNESS -> Icons.sun }
            is Hud.Message -> when (h.kind) {
                MessageKind.MIC_OFF, MessageKind.MIC_CAMERA_OFF -> Icons.micOff
                MessageKind.MIC_ON -> Icons.mic
                MessageKind.CAMERA_OFF, MessageKind.CAMERA_OFF_SHUTTER -> Icons.camOff
            }
        }
        // padding 0 22 0 18, gap 14, icon 28
        icon.draw(c, 18f, 18f, 28f, ink, paint)
        when (h) {
            is Hud.Level -> {
                val barL = 18f + 28f + 14f
                val barR = w - 22f - 40f - 14f
                rect.set(barL, 27f, barR, 37f)
                paint.style = Paint.Style.FILL
                paint.color = if (night) withAlpha(Night.COLOR, 0.3f * a) else withAlpha(Color.WHITE, 0.22f * a)
                c.drawRoundRect(rect, 5f, 5f, paint)
                val fw = (barR - barL) * level.value.coerceIn(0f, 1.05f).coerceAtMost(1f)
                if (fw > 0.5f) {
                    rect.set(barL, 27f, barL + fw.coerceAtLeast(10f).coerceAtMost(barR - barL), 37f)
                    paint.color = ink
                    c.drawRoundRect(rect, 5f, 5f, paint)
                }
                val v = (h.value * 100).roundToInt().coerceIn(0, 100).toString()
                pct.drawRight(c, v, w - 22f, pct.baselineCentered(32f), ink)
            }
            is Hud.Message -> {
                // flex column, line-height 1.15, centred
                val lh1 = 18f * 1.15f; val lh2 = 13f * 1.15f
                val top = 32f - (lh1 + lh2) / 2f
                val x = 18f + 28f + 14f
                title.draw(c, h.kind.title, x, title.baselineIn(top, lh1), ink)
                val subInk = if (night) withAlpha(Night.COLOR, 0.8f * a) else withAlpha(Color.WHITE, 0.8f * a)
                sub.draw(c, h.kind.sub, x, sub.baselineIn(top + lh1, lh2), subInk)
            }
        }
    }

    /** The camera overlay (`:feature:camera`, z 250) owns the camera-in-use indicator; B draws only the red privacy badges. */
    private data class Badges(val mic: Boolean, val cam: Boolean)

    private fun badges(s: SystemState) = Badges(
        mic = s.micMuted || s.micMutedHardware,
        cam = s.cameraDisabled || s.shutterClosed,
    )

    /** right 22, top 18, 34 px circles, gap 8, ring 0 0 0 3px rgba(0,0,0,.5). */
    private var showBadges = true

    private fun readSettings() {
        queue.levelMs = SystemSettings.hudLevelMs(ui.services.settings)
        showBadges = ui.services.settings.getBoolean(SystemSettings.HUD_BADGES, true)
    }

    private fun drawBadges(c: Canvas) {
        if (!showBadges) return
        val b = badges(state)
        val list = buildList {
            if (b.mic && !queue.showing(MessageKind.Subject.MIC)) add(Icons.micOff to VesperRed)
            if (b.cam && !queue.showing(MessageKind.Subject.CAMERA)) add(Icons.camOff to VesperRed)
        }
        if (list.isEmpty()) return
        val night = state.nightActive
        var x = 960f - 22f - 34f * list.size - 8f * (list.size - 1)
        for ((icon, color) in list) {
            val cx = x + 17f; val cy = 18f + 17f
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(Color.BLACK, 0.5f)
            c.drawCircle(cx, cy, 20f, paint)
            paint.color = if (night) Night.COLOR else color
            c.drawCircle(cx, cy, 17f, paint)
            icon.drawCentered(c, cx, cy, 18f, if (night) Color.BLACK else Color.WHITE, paint, strokeOverride = 2.4f)
            x += 34f + 8f
        }
    }

    private fun describe(h: Hud) = when (h) {
        is Hud.Level -> "${if (h.kind == LevelKind.VOLUME) "Volume" else "Brightness"} ${(h.value * 100).roundToInt()} percent"
        is Hud.Message -> "${h.kind.title}. ${h.kind.sub}"
    }

    companion object {
        val DEBUG_KEYS = arrayOf("island", "look", "cc", "banner", "hud")
        const val HIDDEN_Y = -90f
        const val SHOWN_Y = 16f
        val DROP = bezier(0.3f, 1.45f, 0.5f, 1f)
        val WIDTH_SPRING = bezier(0.3f, 1.3f, 0.5f, 1f)
        /** rgba(255,255,255,0.12) */
        val LEVEL_BG = withAlpha(Color.WHITE, 0.12f)
        val VesperRed = 0xFFFF453A.toInt()
        val VesperGreen = 0xFF30D158.toInt()
        @Suppress("unused") private fun l(a: Float, b: Float, t: Float) = lerp(a, b, t)
    }
}
