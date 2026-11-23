package app.vesper.feature.system.island

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.IslandAction
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.MediaSession
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.system.SystemUi
import app.vesper.feature.system.ui.EASE
import app.vesper.feature.system.ui.GlassSpec
import app.vesper.feature.system.ui.Icon
import app.vesper.feature.system.ui.Icons
import app.vesper.feature.system.hud.Hud
import app.vesper.feature.system.hud.HudQueue
import app.vesper.feature.system.hud.LevelKind
import app.vesper.feature.system.hud.MessageKind
import kotlin.math.roundToInt
import app.vesper.feature.system.ui.Snapshot
import app.vesper.feature.system.ui.SysGlassPainter
import app.vesper.feature.system.ui.Tween
import app.vesper.feature.system.ui.baselineCentered
import app.vesper.feature.system.ui.drawCentered
import app.vesper.feature.system.ui.drawRight
import app.vesper.feature.system.ui.ellipsize
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Echo Island renderer (Island.dc.html, IslandSheet.dc.html, NOTES §5.4), overlay z 300.
 *
 * Renders `services.island.visible(navigator.current)`: hidden / compact (44 px) / split (bead
 * 8 px right) / expanded. One spring (Motion.ISLAND, 0.6 s) drives width, height and radius
 * together; compact content fades 0.2 s, expanded content fades in 0.25 s after 0.15 s.
 * Looks: "glass" (default, a pre-blurred snapshot of what's behind it, re-taken only when the
 * screen changes) or "black" (setting `system.island.look`). Night: red monochrome, no glass.
 *
 * Touch: only the island and bead take touches; while expanded, the whole screen does
 * (tap outside collapses, with a 25 % black scrim as in the design).
 */
@SuppressLint("ViewConstructor")
internal class IslandOverlay(context: Context, private val ui: SystemUi) : FrameLayout(context) {
    private val s = ui.services
    private val model = IslandModel()
    private var layout = model.layout
    private val handler = Handler(Looper.getMainLooper())
    private val demo by lazy { IslandDemo(s.island) }

    // geometry: one spring for bounds + radius
    private val w = Tween(44f)
    private val h = Tween(44f)
    private val r = Tween(22f)
    private val beadX = Tween(480f + 22f + 8f)
    private val appear = Tween(0f)
    private val compactA = Tween(1f)
    private val expandedA = Tween(0f)
    private val scrim = Tween(0f)
    private val beadA = Tween(0f)
    private val beadScale = Tween(0.4f)
    private val bannerFade = Tween(1f)
    /** A HUD inside the island: 1 while it shows a level bar or a message instead of its content. */
    private val volA = Tween(0f)
    private val volLevel = Tween(0f)
    private var volMuted = false
    private var volActive = false
    /** What the takeover shows: a level (volume / brightness) or a message (mic, camera). */
    private var takeKind: LevelKind = LevelKind.VOLUME
    private var takeMsg: MessageKind? = null
    private var takeW = VOL_W
    private var takeH = 44f
    private val endVolume = Runnable {
        volActive = false
        volA.to(0f, 220, EASE)
        val b = layout.box
        w.to(b.width, Motion.ISLAND_MS, Motion.ISLAND); h.to(b.height, Motion.ISLAND_MS, Motion.ISLAND); r.to(b.radius, Motion.ISLAND_MS, Motion.ISLAND)
        invalidate()
    }
    private val takeHud: (Hud) -> Boolean = { h -> showHud(h) }

    private var compactAct: LiveActivity? = null
    private var expandedAct: LiveActivity? = null
    private var beadAct: LiveActivity? = null

    private var night = false
    private var black = false
    private var media: MediaSession? = null
    private val glass = SysGlassPainter(GlassSpec.ISLAND)
    private val beadGlass = SysGlassPainter(GlassSpec.ISLAND)
    private var backdropTakenFor: String? = null

    private val art = IslandArt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val boxRect = RectF()
    private val volRect = RectF()
    private val beadRect = RectF()

    private val tTimer = SpecText(19f, 700)
    private val tLabel = SpecText(15f, 700)
    private val tMusicTitle = SpecText(24f, 800, -0.02f)
    private val tMusicSub = SpecText(16f, 600)
    private val tTimerLabel = SpecText(16f, 700)
    private val tTimerBig = SpecText(64f, 600, -0.04f)
    private val tDoorLabel = SpecText(14f, 700)
    private val tDoorTitle = SpecText(26f, 800, -0.02f)
    private val tButton = SpecText(16f, 700)
    private val tLive = SpecText(11f, 800)
    private val tCallSub = SpecText(14f, 700)
    private val tCallName = SpecText(30f, 800, -0.02f)
    private val tInitial = SpecText(32f, 800)
    private val tLeaveTitle = SpecText(26f, 800, -0.02f)
    private val tMsgTitle = SpecText(16f, 700)
    private val tMsgSub = SpecText(13f, 600)

    private class Btn(val rect: RectF, val run: () -> Unit)
    private val buttons = ArrayList<Btn>()
    private var downInside = false
    private var downOnBead = false
    private var downExpanded = false
    private var demoMusicPlaying = true
    /** ms until the next visible change of a countdown (so digits flip on time without 60 fps). */
    private var tickIn = Long.MAX_VALUE

    private val recompute = Runnable { recompute() }
    private val back: () -> Boolean = { if (layout.mode == IslandMode.EXPANDED) { setLayout(model.collapse()); true } else false }

    /** The music bars, animated on their own so a frame repaints 30×24 px, not the window. */
    private val eq = IslandEqualizer(context)
    private var eqShown = false
    private var boxLeft = 0f
    private var boxTop = 0f

    init {
        isClickable = false
        setWillNotDraw(false)
        eq.visibility = INVISIBLE
        addView(eq, LayoutParams(IslandEqualizer.W_PX, IslandEqualizer.H_PX))
        observeWhileAttached(s.island.activities) { recompute() }
        // screens that hide the island while current (IslandManager.hideOnScreen, e.g. the alarm)
        observeWhileAttached(s.island.hiddenOnScreens) { recompute() }
        observeWhileAttached(s.navigator.current) {
            recompute()
            // the screen underneath changed: re-take the glass snapshot once the pager settles
            // (380 ms), and once more after screens that fill in late (artwork, a painted wallpaper)
            handler.removeCallbacks(retakeBackdrop); handler.removeCallbacks(retakeBackdropLate)
            handler.postDelayed(retakeBackdrop, 450); handler.postDelayed(retakeBackdropLate, 1_600)
        }
        observeWhileAttached(s.system.state) { st -> if (st.nightActive != night) { night = st.nightActive; glass.night = night; beadGlass.night = night; invalidate() } }
        observeWhileAttached(s.settings.changes) { k -> if (k == LOOK_KEY) readLook() }
        observeWhileAttached(s.media.current) { m -> media = m; if (layout.mode != IslandMode.HIDDEN) invalidate() }
        observeWhileAttached(ui.bannerShowing) { b -> bannerFade.to(if (b) 0f else 1f, 300, EASE); invalidate() }
        observeWhileAttached(ui.debug) { b ->
            b?.getString("island")?.let { scene ->
                demo.play(scene)
                if (scene == "nowplaying" || scene == "music") post { setLayout(model.tapMain()) }
            }
            b?.getString("night")?.let { v -> s.system.update { it.copy(nightActive = v == "on") } } // debug only, in memory
            b?.getString("look")?.let { s.settings.putString(LOOK_KEY, it) }
        }
        readLook()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.backHandlers += back
        ui.islandHud = takeHud
    }

    override fun onDetachedFromWindow() {
        if (ui.islandHud === takeHud) ui.islandHud = null
        ui.islandBox = null
        ui.backHandlers -= back
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun readLook() {
        black = s.settings.getString(LOOK_KEY, "glass") == "black"
        val spec = if (black) GlassSpec.ISLAND_BLACK else GlassSpec.ISLAND
        glass.spec = spec; beadGlass.spec = spec
        backdropTakenFor = null
        if (isAttachedToWindow) retakeBackdrop.run() // (readLook also runs from init, before retakeBackdrop exists)
        invalidate()
    }

    private fun recompute() {
        handler.removeCallbacks(recompute)
        val now = SystemClock.elapsedRealtime()
        setLayout(model.update(s.island.visible(s.navigator.current.value, now)))
        // re-check when the next activity goes stale
        s.island.activities.value.mapNotNull { it.staleAfterElapsed }.filter { it > now }.minOrNull()?.let { handler.postDelayed(recompute, it - now + 16) }
    }

    /** A reminder (leave now) opens to say so, then folds back to its pill so it doesn't sit over the clock. */
    private val foldReminder = Runnable {
        if (layout.mode == IslandMode.EXPANDED && layout.main?.kind == ActivityKind.LEAVE_NOW) setLayout(model.collapse())
    }

    private fun setLayout(l: IslandLayout) {
        val prev = layout
        layout = l
        if (l.mode == IslandMode.EXPANDED && l.main?.kind == ActivityKind.LEAVE_NOW && prev.main?.id != l.main?.id) {
            handler.removeCallbacks(foldReminder); handler.postDelayed(foldReminder, REMINDER_OPEN_MS)
        }
        val spring = Motion.ISLAND
        val dur = Motion.ISLAND_MS
        if (l.mode == IslandMode.HIDDEN) {
            appear.to(0f, 300, EASE)
            w.to(44f, dur, spring); h.to(44f, dur, spring); r.to(22f, dur, spring)
            beadA.to(0f, 300, EASE); beadScale.to(0.4f, 500, Motion.SPRING_BOUNCY)
            scrim.to(0f, 300, EASE)
        } else {
            if (prev.mode == IslandMode.HIDDEN && appear.value < 0.05f) {
                // grow out of a 44 px dot
                w.snap(44f); h.snap(44f); r.snap(22f); beadX.snap(480f + 22f + 8f)
                if (backdropTakenFor == null) retakeBackdrop.run()
            }
            appear.to(1f, 300, EASE)
            w.to(l.box.width, dur, spring); h.to(l.box.height, dur, spring); r.to(l.box.radius, dur, spring)
            beadX.to(l.beadX, dur, spring)
            if (l.mode == IslandMode.EXPANDED) {
                if (expandedAct?.id != l.main?.id || prev.mode != IslandMode.EXPANDED) expandedA.snap(0f)
                expandedAct = l.main
                compactA.to(0f, 200, EASE)
                expandedA.to(1f, 250, EASE, Motion.ISLAND_CONTENT_DELAY_MS)
                scrim.to(1f, 300, EASE)
            } else {
                compactAct = l.main
                expandedA.snap(0f)
                compactA.to(1f, 200, EASE)
                scrim.to(0f, 300, EASE)
            }
            if (l.bead != null) { beadAct = l.bead; beadA.to(1f, 300, EASE); beadScale.to(1f, 500, Motion.SPRING_BOUNCY) }
            else { beadA.to(0f, 300, EASE); beadScale.to(0.4f, 500, Motion.SPRING_BOUNCY) }
        }
        if (volActive) { w.to(takeW, dur, spring); h.to(takeH, dur, spring); r.to(takeH / 2f, dur, spring) }
        updateHitAreas()
        invalidate()
    }

    /**
     * A HUD while the island is on screen: the island morphs into the indicator and back.
     *  - Level (volume, brightness): a 280×44 pill, icon + a bar that slides to the new value.
     *  - Message (mic / camera on or off): a 64 tall pill, tinted icon disc + title and subtitle.
     * It holds [LEVEL_HOLD_MS] after the last level change, [HudQueue.MESSAGE_MS] for a message.
     * False when the island isn't showing (the HUD capsule shows it instead).
     */
    private fun showHud(hud: Hud): Boolean {
        if (layout.mode == IslandMode.HIDDEN || appear.target < 1f || bannerFade.target < 1f || !isAttachedToWindow) return false
        val hold: Long
        when (hud) {
            is Hud.Level -> {
                // a message in progress isn't cut short by a level (like the capsule's queue)
                if (volActive && takeMsg != null) return true
                val fresh = !volActive || takeKind != hud.kind
                if (fresh && volA.value < 0.05f) volLevel.snap(currentLevel(hud.kind))
                takeKind = hud.kind; takeMsg = null
                takeW = VOL_W; takeH = 44f
                volMuted = hud.kind == LevelKind.VOLUME && (hud.muted || hud.value <= 0f)
                volLevel.to(hud.value.coerceIn(0f, 1f), 260, EASE)
                hold = LEVEL_HOLD_MS
                announceForAccessibility("${if (hud.kind == LevelKind.VOLUME) "Volume" else "Brightness"} ${(hud.value * 100).roundToInt()} percent")
            }
            is Hud.Message -> {
                takeMsg = hud.kind
                takeW = maxOf(hud.kind.widthPx.toFloat(), 64f + 14f + maxOf(tMsgTitle.width(hud.kind.title), tMsgSub.width(hud.kind.sub)) + 24f)
                takeH = 64f
                hold = HudQueue.MESSAGE_MS
                announceForAccessibility("${hud.kind.title}. ${hud.kind.sub}")
            }
        }
        volActive = true
        volA.to(1f, 200, EASE)
        w.to(takeW, Motion.ISLAND_MS, Motion.ISLAND); h.to(takeH, Motion.ISLAND_MS, Motion.ISLAND); r.to(takeH / 2f, Motion.ISLAND_MS, Motion.ISLAND)
        handler.removeCallbacks(endVolume); handler.postDelayed(endVolume, hold)
        invalidate()
        return true
    }

    private fun currentLevel(k: LevelKind): Float = (if (k == LevelKind.VOLUME) s.system.state.value.volume else s.system.state.value.brightness).coerceIn(0f, 1f)

    private fun drawTakeover(c: Canvas, bw: Float, bh: Float, a: Float) {
        val cy = bh / 2f
        val msg = takeMsg
        if (msg != null) {
            // tinted disc + icon, then title over subtitle
            paint.style = Paint.Style.FILL
            paint.color = ink(msg.tint or 0xFF000000.toInt(), a)
            c.drawCircle(12f + 20f, cy, 20f, paint)
            val icon = when (msg) {
                MessageKind.MIC_OFF, MessageKind.MIC_CAMERA_OFF -> Icons.micOff
                MessageKind.MIC_ON -> Icons.mic
                MessageKind.CAMERA_OFF, MessageKind.CAMERA_OFF_SHUTTER -> Icons.camOff
            }
            icon.drawCentered(c, 32f, cy, 20f, if (night) withAlpha(Color.BLACK, a) else withAlpha(Color.WHITE, a), paint)
            val tx = 12f + 40f + 14f
            val lh1 = tMsgTitle.normalLineHeight; val lh2 = tMsgSub.normalLineHeight
            val top = cy - (lh1 + lh2) / 2f
            tMsgTitle.draw(c, msg.title, tx, tMsgTitle.baselineIn(top, lh1), ink(Color.WHITE, a))
            tMsgSub.draw(c, msg.sub, tx, tMsgSub.baselineIn(top + lh1, lh2), ink(withAlpha(Color.WHITE, 0.7f), a))
            return
        }
        val icon = if (takeKind == LevelKind.BRIGHTNESS) Icons.sun else if (volMuted) Icons.volumeMuted else Icons.volume
        icon.drawCentered(c, 26f, cy, 20f, ink(Color.WHITE, a), paint)
        val l = 48f; val rt = bw - 22f
        if (rt - l < 20f) return
        paint.style = Paint.Style.FILL
        paint.color = ink(withAlpha(Color.WHITE, 0.22f), a)
        volRect.set(l, cy - 3f, rt, cy + 3f); c.drawRoundRect(volRect, 3f, 3f, paint)
        paint.color = ink(Color.WHITE, a)
        volRect.set(l, cy - 3f, l + (rt - l) * volLevel.value, cy + 3f); c.drawRoundRect(volRect, 3f, 3f, paint)
    }

    private fun updateHitAreas() {
        ui.islandHitAreas.clear()
        ui.islandBox = if (layout.mode == IslandMode.HIDDEN) null
            else RectF(480f - layout.box.width / 2f, 12f, 480f + layout.box.width / 2f, 12f + layout.box.height)
        ui.islandRadius = layout.box.radius
        if (layout.mode == IslandMode.HIDDEN) return
        val bw = layout.box.width; val bh = layout.box.height
        ui.islandHitAreas += RectF(480f - bw / 2f - 6f, 0f, 480f + bw / 2f + 6f, 12f + bh + 6f)
        if (layout.bead != null) ui.islandHitAreas += RectF(layout.beadX - 6f, 0f, layout.beadX + 50f, 62f)
    }

    private val retakeBackdrop = Runnable {
        // glass needs something behind it to refract; black look and Night use none
        if (black || night || layout.mode == IslandMode.HIDDEN || width == 0) {
            glass.backdrop?.bitmap?.recycle(); glass.backdrop = null; beadGlass.backdrop = null
            backdropTakenFor = null
            return@Runnable
        }
        val old = glass.backdrop
        // covers the biggest expanded form (doorbell 640×196) and the bead
        val bd = Snapshot.region(this, Rect(150, 0, 810, 232), 20f, 1.9f, 0.9f)
        glass.backdrop = bd; beadGlass.backdrop = bd
        old?.bitmap?.recycle()
        backdropTakenFor = s.navigator.current.value
        invalidate()
    }

    private val retakeBackdropLate = Runnable { retakeBackdrop.run() }

    // --- drawing ---

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        drawIsland(canvas)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= 8 && ui.debugBuild) Log.d("VesperPerf", "island onDraw ${ms} ms mode=${layout.mode}")
    }

    private fun drawIsland(canvas: Canvas) {
        val now = Tween.now()
        var running = false
        for (t in arrayOf(w, h, r, beadX, appear, compactA, expandedA, scrim, beadA, beadScale, bannerFade, volA, volLevel)) running = t.step(now) or running
        val notVol = 1f - volA.value
        val vis = appear.value * bannerFade.value
        buttons.clear()
        var ambient = false
        var ticking = false
        tickIn = Long.MAX_VALUE
        eqShown = false

        if (scrim.value > 0.004f) canvas.drawColor(withAlpha(Color.BLACK, 0.25f * scrim.value * bannerFade.value))

        if (vis > 0.004f) {
            val bw = w.value; val bh = h.value; val br = r.value
            val bx = 480f - bw / 2f; val by = 12f
            boxRect.set(bx, by, bx + bw, by + bh)
            glass.alpha = vis
            glass.draw(canvas, bx, by, bx + bw, by + bh, br)
            canvas.save()
            clip.reset(); clip.addRoundRect(boxRect, br, br, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.translate(bx, by)
            boxLeft = bx; boxTop = by
            val ca = compactA.value * vis * notVol
            compactAct?.let { if (ca > 0.004f) { val (amb, tick) = drawCompact(canvas, it, bw, ca); ambient = ambient or amb; ticking = ticking or tick } }
            val ea = expandedA.value * vis * notVol
            expandedAct?.let { if (ea > 0.004f || (layout.mode == IslandMode.EXPANDED && notVol > 0.996f)) { ticking = drawExpanded(canvas, it, bw, bh, ea, bx, by) or ticking } }
            if (volA.value > 0.004f) drawTakeover(canvas, bw, bh, volA.value * vis)
            canvas.restore()
        }

        val ba = beadA.value * vis * notVol
        if (ba > 0.004f) beadAct?.let { act ->
            val sc = beadScale.value
            val size = 44f * sc
            val cx = beadX.value + 22f; val cy = 12f + 22f
            beadRect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            beadGlass.alpha = ba
            beadGlass.draw(canvas, beadRect.left, beadRect.top, beadRect.right, beadRect.bottom, -1f)
            canvas.save(); canvas.scale(sc, sc, cx, cy)
            ticking = drawBeadContent(canvas, act, cx, cy, ba) or ticking
            canvas.restore()
        }

        if (!running && appear.value <= 0f && beadA.value <= 0f) {
            compactAct = null; expandedAct = null; beadAct = null
            if (layout.mode == IslandMode.HIDDEN && glass.backdrop != null) { glass.backdrop?.bitmap?.recycle(); glass.backdrop = null; beadGlass.backdrop = null; backdropTakenFor = null }
        }

        if (!eqShown && (eq.visibility == VISIBLE || eq.playing)) { eq.playing = false; eq.visibility = INVISIBLE }

        when {
            running -> postInvalidateOnAnimation()
            ambient -> postInvalidateDelayed(AMBIENT_FRAME_MS)
            ticking -> postInvalidateDelayed(if (tickIn == Long.MAX_VALUE) 1000L else tickIn)
        }
    }

    /** Positions the bars (overlay coordinates) and sets their look; only changed values touch the view. */
    private fun placeEq(x: Float, y: Float, a: Float, color: Int, playing: Boolean) {
        eqShown = true
        if (eq.translationX != x) eq.translationX = x
        if (eq.translationY != y) eq.translationY = y
        if (eq.alpha != a) eq.alpha = a
        eq.color = color
        eq.playing = playing
        if (eq.visibility != VISIBLE) eq.visibility = VISIBLE
    }

    private fun ink(c: Int, a: Float) = if (night) withAlpha(Night.COLOR, Color.alpha(c) / 255f * a) else withAlpha(c, Color.alpha(c) / 255f * a)

    /** Returns (ambient animation running, needs 1 s ticks). Contents sit in the top 44 px strip. */
    private fun drawCompact(c: Canvas, a: LiveActivity, bw: Float, alpha: Float): Pair<Boolean, Boolean> {
        val cy = 22f
        val right = bw - 14f
        val t = SystemClock.uptimeMillis() / 1000f
        when (val ct = a.content) {
            is LiveContent.Timer -> {
                val rem = ct.remainingMs()
                art.ring(c, 9f + 14f, cy, 28f, if (ct.durationMs > 0) rem.toFloat() / ct.durationMs else 0f, ink(ORANGE, 1f), alpha)
                tTimer.drawRight(c, mmss(rem), right, tTimer.baselineCentered(cy), ink(ORANGE, alpha))
                return false to (ct.pausedRemainingMs == null)
            }
            is LiveContent.Music -> {
                art.artwork(c, ct.artwork, 9f, cy - 14f, 28f, 8f, alpha, night)
                // The bars are a child view (IslandEqualizer) that animates itself at ~15 fps while
                // playing; static when paused or in Night. Nothing here asks for overlay frames.
                placeEq(boxLeft + right - IslandEqualizer.W, boxTop + cy - IslandEqualizer.H / 2f, alpha, ink(PEACH, 1f), isPlaying(ct) && !night)
                return false to false
            }
            is LiveContent.Assistant -> {
                art.orb(c, 9f + 13f, cy, 26f, (t * 140f) % 360f, alpha, night)
                var x = right - (6 * 3.5f + 5 * 3f)
                val lv = ct.level
                for (i in 0 until 6) {
                    val k = abs(sin(t * (5.3f + i * 1.1f) + i * 2))
                    val bh = 5f + 17f * k * (if (lv > 0f) 0.3f + 0.7f * lv else 1f)
                    paint.color = ink(ASSIST_BARS[i], alpha)
                    rect.set(x, cy - bh / 2, x + 3.5f, cy + bh / 2); c.drawRoundRect(rect, 2f, 2f, paint)
                    x += 3.5f + 3f
                }
                return true to false
            }
            is LiveContent.TimerDone -> {
                art.ring(c, 23f, cy, 28f, 1f, ink(ORANGE, 1f), alpha)
                tTimer.drawRight(c, "Done", right, tTimer.baselineCentered(cy), ink(ORANGE, alpha))
            }
            is LiveContent.AlarmRinging -> {
                leadingCircle(c, 23f, cy, 28f, ORANGE, Icons.alarm, alpha, iconInk = 0xFF2A1200.toInt())
                tTimer.drawRight(c, ct.timeText, right, tTimer.baselineCentered(cy), ink(ORANGE, alpha))
            }
            is LiveContent.Doorbell -> {
                leadingCircle(c, 23f, cy, 28f, ORANGE, Icons.bell, alpha, iconInk = Color.WHITE)
                val label = tLabel.ellipsize(ct.camera, bw - 14f - 46f)
                tLabel.drawRight(c, label, right, tLabel.baselineCentered(cy), ink(Color.WHITE, alpha))
            }
            is LiveContent.Call -> {
                leadingCircle(c, 23f, cy, 28f, GREEN, if (ct.video) Icons.video else Icons.phone, alpha, iconInk = Color.WHITE)
                tLabel.drawRight(c, tLabel.ellipsize(ct.caller, bw - 60f), right, tLabel.baselineCentered(cy), ink(Color.WHITE, alpha))
            }
            is LiveContent.AirPlayConnected -> {
                Icons.airplay.drawCentered(c, 23f, cy, 22f, ink(Color.WHITE, alpha), paint)
                tLabel.drawRight(c, tLabel.ellipsize(ct.deviceName, bw - 60f), right, tLabel.baselineCentered(cy), ink(withAlpha(Color.WHITE, 0.85f), alpha))
            }
            is LiveContent.BluetoothConnected -> {
                // like "AirPlay connected": glyph + device name
                Icons.bluetooth.drawCentered(c, 23f, cy, 22f, ink(BLUE, alpha), paint)
                tLabel.drawRight(c, tLabel.ellipsize(ct.deviceName, bw - 60f), right, tLabel.baselineCentered(cy), ink(withAlpha(Color.WHITE, 0.85f), alpha))
            }
            is LiveContent.LeaveNow -> {
                val rem = ct.leaveAtMillis - System.currentTimeMillis()
                Icons.car.drawCentered(c, 23f, cy, 24f, ink(ORANGE, alpha), paint)
                tLabel.drawRight(c, tLabel.ellipsize(leaveText(rem), bw - 60f), right, tLabel.baselineCentered(cy), ink(ORANGE, alpha))
                // a minute countdown: wake at the next minute boundary, not every second
                if (rem > 0) { tickIn = minOf(tickIn, rem % 60_000L + 8L); return false to true }
            }
            is LiveContent.RainSoon -> {
                Icons.rain.drawCentered(c, 23f, cy, 24f, ink(RAIN, alpha), paint)
                tLabel.drawRight(c, tLabel.ellipsize(rainText(ct), bw - 60f), right, tLabel.baselineCentered(cy), ink(RAIN, alpha))
            }
            is LiveContent.PhoneBattery -> {
                val pct = ct.percent.coerceIn(0, 100)
                val col = batteryColor(pct, ct.charging == true)
                Icons.handset.drawCentered(c, 23f, cy, 24f, ink(Color.WHITE, alpha), paint)
                batteryGlyph(c, 48f, cy, pct, ct.charging == true, col, alpha)
                tLabel.drawRight(c, "$pct%", right, tLabel.baselineCentered(cy), ink(if (pct <= LOW_BATTERY && ct.charging != true) col else Color.WHITE, alpha))
            }
        }
        return false to false
    }

    private fun leadingCircle(c: Canvas, cx: Float, cy: Float, size: Float, bg: Int, icon: Icon, alpha: Float, iconInk: Int) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = ink(bg, alpha)
        c.drawCircle(cx, cy, size / 2, paint)
        icon.drawCentered(c, cx, cy, size * 0.6f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(iconInk, alpha), Icon.shared)
    }

    private fun drawBeadContent(c: Canvas, a: LiveActivity, cx: Float, cy: Float, alpha: Float): Boolean {
        when (val ct = a.content) {
            is LiveContent.Timer -> { art.ring(c, cx, cy, 26f, if (ct.durationMs > 0) ct.remainingMs().toFloat() / ct.durationMs else 0f, ink(ORANGE, 1f), alpha); return ct.pausedRemainingMs == null }
            is LiveContent.TimerDone -> art.ring(c, cx, cy, 26f, 1f, ink(ORANGE, 1f), alpha)
            is LiveContent.Music -> art.artwork(c, ct.artwork, cx - 14f, cy - 14f, 28f, 8f, alpha, night)
            is LiveContent.Assistant -> art.orb(c, cx, cy, 22f, (SystemClock.uptimeMillis() / 1000f * 140f) % 360f, alpha, night)
            is LiveContent.AlarmRinging -> Icons.alarm.drawCentered(c, cx, cy, 22f, ink(ORANGE, alpha), paint)
            is LiveContent.Doorbell -> Icons.bell.drawCentered(c, cx, cy, 22f, ink(ORANGE, alpha), paint)
            is LiveContent.Call -> Icons.phone.drawCentered(c, cx, cy, 22f, ink(GREEN, alpha), paint)
            is LiveContent.AirPlayConnected -> Icons.airplay.drawCentered(c, cx, cy, 22f, ink(Color.WHITE, alpha), paint)
            is LiveContent.BluetoothConnected -> Icons.bluetooth.drawCentered(c, cx, cy, 22f, ink(BLUE, alpha), paint)
            is LiveContent.LeaveNow -> Icons.car.drawCentered(c, cx, cy, 22f, ink(ORANGE, alpha), paint)
            is LiveContent.RainSoon -> Icons.rain.drawCentered(c, cx, cy, 22f, ink(RAIN, alpha), paint)
            is LiveContent.PhoneBattery -> Icons.handset.drawCentered(c, cx, cy, 22f, ink(batteryColor(ct.percent, ct.charging == true), alpha), paint)
        }
        return false
    }

    private fun leaveText(remMs: Long): String {
        if (remMs <= 0) return "Leave now"
        val min = ceil(remMs / 60_000.0).toInt()
        return if (min >= 60) "Leave in ${min / 60} h ${min % 60} min".replace(" 0 min", "") else "Leave in $min min"
    }

    private fun rainText(ct: LiveContent.RainSoon): String {
        val what = if (ct.heavy) "Heavy rain" else "Rain"
        return when {
            ct.minutes <= 0 -> "$what now"
            ct.minutes >= 60 -> "$what soon"
            else -> "$what in ${ct.minutes} min"
        }
    }

    private fun batteryColor(pct: Int, charging: Boolean) = when {
        charging -> GREEN
        pct <= LOW_BATTERY -> RED
        else -> Color.WHITE
    }

    /** iOS-style battery: 28x14 outline + tip, level fill, a bolt on it while charging. Left edge [x], centred on [cy]. */
    private fun batteryGlyph(c: Canvas, x: Float, cy: Float, pct: Int, charging: Boolean, col: Int, alpha: Float) {
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f
        paint.color = ink(withAlpha(Color.WHITE, 0.5f), alpha)
        rect.set(x, cy - 7f, x + 28f, cy + 7f); c.drawRoundRect(rect, 4.2f, 4.2f, paint)
        paint.style = Paint.Style.FILL
        rect.set(x + 29.5f, cy - 2.6f, x + 31.5f, cy + 2.6f); c.drawRoundRect(rect, 1f, 1f, paint)
        paint.color = ink(col, alpha)
        val fw = 21f * (pct.coerceIn(0, 100) / 100f)
        if (fw > 0.5f) { rect.set(x + 3.5f, cy - 3.5f, x + 3.5f + maxOf(fw, 3f), cy + 3.5f); c.drawRoundRect(rect, 2f, 2f, paint) }
        if (charging) Icons.bolt.drawCentered(c, x + 14f, cy, 11f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(0xFF08140C.toInt(), alpha), paint)
    }

    /** Expanded layouts, re-flowed at the current (animating) size like the CSS flex boxes. Returns needs-1s-ticks. */
    private fun drawExpanded(c: Canvas, a: LiveActivity, bw: Float, bh: Float, alpha: Float, ox: Float, oy: Float): Boolean {
        val cy = bh / 2f
        when (val ct = a.content) {
            is LiveContent.Music -> {
                // padding 22 26, gap 18: art 116 r22 · text · prev 52 / play 60 / next 52 (gap 4)
                art.artwork(c, ct.artwork, 26f, cy - 58f, 116f, 22f, alpha, night)
                val groupL = bw - 26f - (52f + 4f + 60f + 4f + 52f)
                val textL = 26f + 116f + 18f
                val textW = (groupL - 18f - textL).coerceAtLeast(0f)
                val lh1 = tMusicTitle.normalLineHeight; val lh2 = tMusicSub.normalLineHeight
                val top = cy - (lh1 + lh2 + 16f + 6f) / 2f
                tMusicTitle.draw(c, tMusicTitle.ellipsize(ct.title, textW), textL, tMusicTitle.baselineIn(top, lh1), ink(Color.WHITE, alpha))
                val srcLabel = media?.state?.takeIf { it.title == ct.title }?.sourceLabel?.takeIf { it.isNotBlank() }
                val sub = ct.artist + " · " + when (ct.source) {
                    app.vesper.core.model.MediaSourceKind.AIRPLAY -> if (srcLabel != null) "AirPlay from $srcLabel" else "AirPlay from iPhone"
                    app.vesper.core.model.MediaSourceKind.BLUETOOTH -> if (srcLabel != null) "Bluetooth from $srcLabel" else "Bluetooth"
                    app.vesper.core.model.MediaSourceKind.SPOTIFY -> "Spotify"
                    app.vesper.core.model.MediaSourceKind.OTHER_APP -> srcLabel ?: "Playing"
                }
                tMusicSub.draw(c, tMusicSub.ellipsize(sub, textW), textL, tMusicSub.baselineIn(top + lh1, lh2), ink(withAlpha(Color.WHITE, 0.7f), alpha))
                val barT = top + lh1 + lh2 + 16f
                rect.set(textL, barT, textL + textW, barT + 6f)
                paint.shader = null; paint.style = Paint.Style.FILL
                paint.color = ink(withAlpha(Color.WHITE, 0.22f), alpha); c.drawRoundRect(rect, 3f, 3f, paint)
                rect.right = textL + textW * musicProgress(ct)
                paint.color = ink(Color.WHITE, alpha); c.drawRoundRect(rect, 3f, 3f, paint)
                val playing = isPlaying(ct)
                val pX = groupL + 26f; val plX = groupL + 52f + 4f + 30f; val nX = groupL + 52f + 4f + 60f + 4f + 26f
                Icons.prev.drawCentered(c, pX, cy, 28f, ink(Color.WHITE, alpha), paint)
                (if (playing) Icons.pause32 else Icons.play32).drawCentered(c, plX, cy, 36f, ink(Color.WHITE, alpha), paint)
                Icons.next.drawCentered(c, nX, cy, 28f, ink(Color.WHITE, alpha), paint)
                button(ox + pX - 26f, oy + cy - 26f, 52f, 52f) { mediaAction("previous") }
                button(ox + plX - 30f, oy + cy - 30f, 60f, 60f) { mediaAction(if (isPlaying(ct)) "pause" else "play") }
                button(ox + nX - 26f, oy + cy - 26f, 52f, 52f) { mediaAction("next") }
                button(ox + 26f, oy, groupL - 26f, bh) { setLayout(model.collapse()); s.navigator.show(a.ownerScreen ?: "nowplaying") }
                return playing
            }
            is LiveContent.Timer, is LiveContent.TimerDone, is LiveContent.AlarmRinging -> {
                // padding 18 24: label 16/700 #FFB340 over 64/600 time; 64 px round buttons, gap 12
                val (label, time) = when (ct) {
                    is LiveContent.Timer -> ct.label.ifBlank { "Timer" } to mmss(ct.remainingMs())
                    is LiveContent.TimerDone -> ct.label.ifBlank { "Timer" } to "Done"
                    is LiveContent.AlarmRinging -> ct.label to ct.timeText
                    else -> "" to ""
                }
                val lh1 = tTimerLabel.normalLineHeight; val lh2 = 64f
                val top = cy - (lh1 + lh2) / 2f
                tTimerLabel.draw(c, label, 24f, tTimerLabel.baselineIn(top, lh1), ink(0xFFFFB340.toInt(), alpha))
                tTimerBig.draw(c, time, 24f, tTimerBig.baselineIn(top + lh1, lh2), ink(ORANGE, alpha))
                val b2 = bw - 24f - 64f; val b1 = b2 - 12f - 64f
                val paused = ct is LiveContent.Timer && ct.pausedRemainingMs != null
                val primaryIcon = when (ct) { is LiveContent.Timer -> if (paused) Icons.play24 else Icons.pause24; else -> Icons.alarm }
                if (ct is LiveContent.Timer || a.actions.size >= 2 || ct is LiveContent.AlarmRinging) {
                    circleButton(c, b1, cy - 32f, 64f, ORANGE, alpha)
                    primaryIcon.drawCentered(c, b1 + 32f, cy, 24f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(0xFF2A1200.toInt(), alpha), paint)
                    button(ox + b1, oy + cy - 32f, 64f, 64f) { primaryAction(a) }
                }
                circleButton(c, b2, cy - 32f, 64f, withAlpha(Color.WHITE, 0.18f), alpha)
                Icons.close.drawCentered(c, b2 + 32f, cy, 22f, ink(Color.WHITE, alpha), paint)
                button(ox + b2, oy + cy - 32f, 64f, 64f) { cancelAction(a) }
                return ct is LiveContent.Timer && !paused
            }
            is LiveContent.Doorbell -> {
                art.porch(c, ct.snapshot, 18f, 18f, 230f, bh - 36f, 26f, alpha, night)
                // LIVE chip: left 10 top 10, h 22, padding 0 8, gap 5, 6 px red dot, 11/800
                val chipW = 8f + 6f + 5f + tLive.width("LIVE") + 8f
                rect.set(28f, 28f, 28f + chipW, 50f)
                paint.shader = null; paint.color = withAlpha(Color.BLACK, 0.55f * alpha); c.drawRoundRect(rect, 11f, 11f, paint)
                paint.color = ink(0xFFFF453A.toInt(), alpha); c.drawCircle(28f + 8f + 3f, 39f, 3f, paint)
                tLive.draw(c, "LIVE", 28f + 8f + 6f + 5f, tLive.baselineCentered(39f), ink(Color.WHITE, alpha))
                val colL = 18f + 230f + 18f
                tDoorLabel.draw(c, "${ct.camera} · now", colL, tDoorLabel.baselineIn(22f), ink(0xFFFFB340.toInt(), alpha))
                val titleW = bw - 18f - 4f - colL
                tDoorTitle.draw(c, tDoorTitle.ellipsize("Someone’s at the door", titleW), colL, tDoorTitle.baselineIn(22f + tDoorLabel.normalLineHeight + 2f), ink(Color.WHITE, alpha))
                val acts = a.actions.ifEmpty { listOf(IslandAction("dismiss", "Dismiss") {}) }
                var x = colL
                val btnT = bh - 18f - 2f - 48f
                for (act in acts) {
                    val padX = if (act.style == IslandAction.Style.ACCEPT) 20f else 18f
                    val bwid = padX * 2 + tButton.width(act.label)
                    val (bg, fg) = when (act.style) {
                        IslandAction.Style.ACCEPT -> GREEN to 0xFF04210E.toInt()
                        IslandAction.Style.PRIMARY -> 0xFF0A84FF.toInt() to Color.WHITE
                        IslandAction.Style.DESTRUCTIVE -> 0xFFFF453A.toInt() to Color.WHITE
                        IslandAction.Style.NORMAL -> withAlpha(Color.WHITE, 0.18f) to Color.WHITE
                    }
                    rect.set(x, btnT, x + bwid, btnT + 48f)
                    paint.color = ink(bg, alpha); c.drawRoundRect(rect, 24f, 24f, paint)
                    tButton.draw(c, act.label, x + padX, tButton.baselineCentered(btnT + 24f), if (night) withAlpha(Color.BLACK, alpha) else withAlpha(fg, alpha))
                    button(ox + x, oy + btnT, bwid, 48f) { invoke(act, collapseAfter = true) }
                    x += bwid + 10f
                }
            }
            is LiveContent.Call -> {
                // padding 18 24, gap 16: avatar 76 · text · Decline 68 red · Accept 68 green
                paint.shader = if (night) null else LinearGradient(24f, cy - 38f, 24f + 76f * 0.34f, cy + 38f, 0xFFFF6A88.toInt(), 0xFFFF375F.toInt(), Shader.TileMode.CLAMP)
                paint.color = if (night) withAlpha(Night.COLOR, alpha) else withAlpha(Color.WHITE, alpha)
                c.drawCircle(24f + 38f, cy, 38f, paint); paint.shader = null
                tInitial.drawCentered(c, ct.caller.take(1).uppercase(), 24f + 38f, tInitial.baselineCentered(cy), if (night) withAlpha(Color.BLACK, alpha) else withAlpha(Color.WHITE, alpha))
                val decline = a.actions.firstOrNull { it.style == IslandAction.Style.DESTRUCTIVE }
                val accept = a.actions.firstOrNull { it.style == IslandAction.Style.ACCEPT }
                // From the iPhone (ANCS) calls are answered on the phone: no Accept → Decline only, at the end
                val textL = 24f + 76f + 16f
                val acc = bw - 24f - 68f
                // an iPhone call whose notification has no negative action: no buttons at all (answer on the phone)
                val anyButton = decline != null || accept != null
                val dec = if (accept != null) acc - 16f - 68f else if (anyButton) acc else bw - 24f + 16f
                val lh1 = tCallSub.normalLineHeight; val lh2 = tCallName.normalLineHeight
                val top = cy - (lh1 + lh2) / 2f
                tCallSub.draw(c, tCallSub.ellipsize(ct.subtitle, dec - 16f - textL), textL, tCallSub.baselineIn(top, lh1), ink(withAlpha(Color.WHITE, 0.65f), alpha))
                tCallName.draw(c, tCallName.ellipsize(ct.caller, dec - 16f - textL), textL, tCallName.baselineIn(top + lh1, lh2), ink(Color.WHITE, alpha))
                if (anyButton) {
                    circleButton(c, dec, cy - 34f, 68f, 0xFFFF453A.toInt(), alpha)
                    c.save(); c.rotate(135f, dec + 34f, cy)
                    Icons.phone.drawCentered(c, dec + 34f, cy, 30f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(Color.WHITE, alpha), paint)
                    c.restore()
                    button(ox + dec, oy + cy - 34f, 68f, 68f) { if (decline != null) invoke(decline, true) else setLayout(model.collapse()) }
                }
                if (accept != null) {
                    circleButton(c, acc, cy - 34f, 68f, GREEN, alpha)
                    (if (ct.video) Icons.video else Icons.phone).drawCentered(c, acc + 34f, cy, 30f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(Color.WHITE, alpha), paint)
                    button(ox + acc, oy + cy - 34f, 68f, 68f) { invoke(accept, true) }
                }
            }
            is LiveContent.LeaveNow -> {
                // padding 24, gap 16: orange disc 64 + car, title 26/800 over "Leave by 8:35 . 25 min drive", [extra] + Dismiss pills
                circleButton(c, 24f, cy - 32f, 64f, ORANGE, alpha)
                Icons.car.drawCentered(c, 24f + 32f, cy, 34f, if (night) withAlpha(Color.BLACK, alpha) else withAlpha(0xFF2A1200.toInt(), alpha), paint)
                val extra = a.actions.firstOrNull { it.id != "dismiss" }
                val pills = ArrayList<Pair<String, IslandAction?>>()
                if (extra != null) pills += extra.label to extra
                pills += "Dismiss" to null
                var x = bw - 24f + 10f
                for ((label, act) in pills.reversed()) {
                    val pw = 22f * 2 + tButton.width(label)
                    x -= 10f + pw
                    rect.set(x, cy - 24f, x + pw, cy + 24f)
                    paint.shader = null; paint.style = Paint.Style.FILL
                    paint.color = ink(if (act != null) ORANGE else withAlpha(Color.WHITE, 0.18f), alpha)
                    c.drawRoundRect(rect, 24f, 24f, paint)
                    val fg = if (act != null) (if (night) Color.BLACK else 0xFF2A1200.toInt()) else Color.WHITE
                    tButton.draw(c, label, x + 22f, tButton.baselineCentered(cy), if (night && act != null) withAlpha(fg, alpha) else ink(fg, alpha))
                    button(ox + x, oy + cy - 24f, pw, 48f) { if (act != null) invoke(act, true) else dismissLeave(a) }
                }
                val textL = 24f + 64f + 16f
                val textW = (x - 16f - textL).coerceAtLeast(0f)
                val lh1 = tLeaveTitle.normalLineHeight; val lh2 = tTimerLabel.normalLineHeight
                val top = cy - (lh1 + lh2 + 2f) / 2f
                tLeaveTitle.draw(c, tLeaveTitle.ellipsize(ct.title, textW), textL, tLeaveTitle.baselineIn(top, lh1), ink(Color.WHITE, alpha))
                val sub = "Leave by ${clockText(ct.leaveAtMillis)} · ${ct.travelMinutes} min drive"
                tTimerLabel.draw(c, tTimerLabel.ellipsize(sub, textW), textL, tTimerLabel.baselineIn(top + lh1 + 2f, lh2), ink(0xFFFFB340.toInt(), alpha))
            }
            is LiveContent.Assistant, is LiveContent.AirPlayConnected, is LiveContent.BluetoothConnected,
            is LiveContent.RainSoon, is LiveContent.PhoneBattery -> {}
        }
        return false
    }

    private fun dismissLeave(a: LiveActivity) {
        a.actions.firstOrNull { it.id == "dismiss" }?.let { try { it.onInvoke() } catch (e: Exception) { android.util.Log.w("VesperSystem", "leave dismiss", e) } }
        s.island.end(a.id) // ends it even when the producer gave no dismiss action
        if (layout.mode == IslandMode.EXPANDED) setLayout(model.collapse())
    }

    private fun clockText(ms: Long): String =
        java.text.SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private fun circleButton(c: Canvas, x: Float, y: Float, size: Float, color: Int, alpha: Float) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = ink(color, alpha)
        c.drawCircle(x + size / 2, y + size / 2, size / 2, paint)
    }

    private fun button(x: Float, y: Float, w: Float, h: Float, run: () -> Unit) { buttons += Btn(RectF(x, y, x + w, y + h), run) }

    private fun invoke(a: IslandAction, collapseAfter: Boolean) {
        try { a.onInvoke() } catch (e: Exception) { android.util.Log.w("VesperSystem", "island action ${a.id}", e) }
        if (collapseAfter && layout.mode == IslandMode.EXPANDED) setLayout(model.collapse())
    }

    private fun timerId(a: LiveActivity) = if (a.id.startsWith("timer:")) a.id.removePrefix("timer:") else null

    private fun primaryAction(a: LiveActivity) {
        a.actions.firstOrNull { it.style == IslandAction.Style.PRIMARY || it.id in setOf("pause", "resume", "snooze") }?.let { invoke(it, false); return }
        when (val ct = a.content) {
            is LiveContent.Timer -> {
                val id = timerId(a)
                if (id != null) { if (ct.pausedRemainingMs != null) s.timers.resume(id) else s.timers.pause(id) }
                else if (a.id.startsWith(IslandDemo.PREFIX)) s.island.update(a.id) { act ->
                    val t = act.content as LiveContent.Timer
                    val pr = t.pausedRemainingMs
                    act.copy(content = if (pr != null) t.copy(endsAtElapsed = SystemClock.elapsedRealtime() + pr, pausedRemainingMs = null)
                        else t.copy(pausedRemainingMs = t.remainingMs()))
                }
            }
            is LiveContent.AlarmRinging -> s.alarms.snooze()
            else -> {}
        }
    }

    private fun cancelAction(a: LiveActivity) {
        a.actions.firstOrNull { it.id in setOf("cancel", "stop", "dismiss") }?.let { invoke(it, true); return }
        when (a.content) {
            is LiveContent.Timer -> timerId(a)?.let { s.timers.cancel(it) } ?: s.island.end(a.id)
            is LiveContent.TimerDone -> timerId(a)?.let { s.timers.dismiss(it) } ?: s.island.end(a.id)
            is LiveContent.AlarmRinging -> s.alarms.stop()
            else -> {}
        }
        if (layout.mode == IslandMode.EXPANDED) setLayout(model.collapse())
    }

    private fun mediaAction(what: String) {
        val m = media
        if (m == null) { if (what == "pause" || what == "play") { demoMusicPlaying = what == "play"; invalidate() }; return }
        when (what) { "play" -> m.controls.play(); "pause" -> m.controls.pause(); "next" -> m.controls.next(); "previous" -> m.controls.previous() }
    }

    private fun isPlaying(ct: LiveContent.Music): Boolean = media?.state?.takeIf { it.title == ct.title }?.playing ?: (ct.playing && demoMusicPlaying)

    private fun musicProgress(ct: LiveContent.Music): Float {
        val st = media?.state?.takeIf { it.title == ct.title }
        if (st != null && st.durationMs > 0) return (st.positionNow().toFloat() / st.durationMs).coerceIn(0f, 1f)
        // design stand-in: 36 % + slow progress
        return ((36f + (SystemClock.uptimeMillis() / 1000f) / 2.27f % 64f) / 100f)
    }

    private fun mmss(ms: Long): String {
        val frac = ms % 1000
        tickIn = minOf(tickIn, (if (frac == 0L) 1000L else frac) + 8L)
        val total = ceil(ms / 1000.0).toLong().coerceAtLeast(0)
        val hrs = total / 3600; val m = (total / 60) % 60; val sec = total % 60
        return if (hrs > 0) "%d:%02d:%02d".format(hrs, m, sec) else "%d:%02d".format(m, sec)
    }

    // --- touch ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val visible = appear.target > 0f && bannerFade.target > 0f
                downExpanded = layout.mode == IslandMode.EXPANDED && visible
                downInside = visible && layout.mode != IslandMode.HIDDEN && hitBox(x, y)
                downOnBead = visible && layout.bead != null && beadRect.contains(x, y)
                return downExpanded || downInside || downOnBead
            }
            MotionEvent.ACTION_UP -> {
                if (downExpanded) {
                    buttons.firstOrNull { it.rect.contains(x, y) }?.let { it.run(); return true }
                    if (!hitBox(x, y)) setLayout(model.collapse())
                } else if (downInside && hitBox(x, y)) {
                    val m = layout.main
                    if (m != null && !IslandModel.canExpand(m)) s.navigator.show(m.ownerScreen ?: if (m.kind == ActivityKind.ASSISTANT) "assistant" else "nowplaying")
                    else setLayout(model.tapMain())
                } else if (downOnBead && beadRect.contains(x, y)) {
                    setLayout(model.tapBead())
                }
                return true
            }
        }
        return downExpanded || downInside || downOnBead
    }

    private fun hitBox(x: Float, y: Float): Boolean {
        val bw = w.value; val bh = h.value
        return x >= 480f - bw / 2f - 6f && x <= 480f + bw / 2f + 6f && y <= 12f + bh + 6f
    }

    companion object {
        const val REMINDER_OPEN_MS = 10_000L
        const val VOL_W = 280f
        const val LEVEL_HOLD_MS = 1_600L
        const val LOOK_KEY = "system.island.look"
        /** Equaliser / orb animation rate: 30 fps is smooth for 22 px bars and halves the cost. */
        const val AMBIENT_FRAME_MS = 33L
        val ORANGE = 0xFFFF9F0A.toInt()
        val GREEN = 0xFF30D158.toInt()
        val BLUE = 0xFF0A84FF.toInt()
        val RAIN = 0xFF5EB0FF.toInt()
        val RED = 0xFFFF453A.toInt()
        const val LOW_BATTERY = 20
        val PEACH = 0xFFF4B98A.toInt()
        val ASSIST_BARS = intArrayOf(0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFF64D2FF.toInt(), 0xFFBF5AF2.toInt())
    }
}
