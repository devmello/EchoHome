package app.vesper.feature.system.cc

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperTimer
import app.vesper.core.model.MediaSession
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.NightMode
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.SystemState
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.system.SystemUi
import app.vesper.feature.system.device.RadioState
import app.vesper.feature.system.island.IslandArt
import app.vesper.feature.system.ui.GlassSpec
import app.vesper.feature.system.ui.Icon
import app.vesper.feature.system.ui.Icons
import app.vesper.feature.system.ui.SysGlassPainter
import app.vesper.feature.system.ui.Tween
import app.vesper.feature.system.ui.baselineCentered
import app.vesper.feature.system.ui.bezier
import app.vesper.feature.system.ui.drawCentered
import app.vesper.feature.system.ui.ellipsize
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * Control Center's content (ControlCenter.dc.html), one View drawn at the design's absolute
 * positions. Redraws only when something it shows changes (state, a slider drag, the minute).
 */
@SuppressLint("ViewConstructor")
internal class ControlCenterPanel(context: Context, private val ui: SystemUi, private val requestClose: () -> Unit) : View(context) {
    private val s = ui.services
    var onDragClose: ((dy: Float, done: Boolean, fling: Boolean) -> Unit)? = null

    private var state = SystemState()
    private var radios = RadioState()
    private var btDevice: String? = null
    private var media: MediaSession? = null
    private var timers: List<VesperTimer> = emptyList()
    private var profiles: List<Profile> = emptyList()
    private var active: Profile = Profile.SHARED
    private var autoByFace = true
    private var nextAlarm: NextAlarm? = null
    private var shown = false

    private val tile = SysGlassPainter(GlassSpec.CC_TILE)
    private val roundBtn = SysGlassPainter(GlassSpec.CC_BUTTON)
    private val chip = SysGlassPainter(GlassSpec.CC_BUTTON.copy(fill = Color.argb(128, 255, 69, 58), borderAlpha = 0.28f, shadowAlpha = 0f, specularAlphas = floatArrayOf(0f, 0f, 0f)))
    private val art = IslandArt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val handler = Handler(Looper.getMainLooper())

    private val tClock = SpecText(30f, 700, -0.03f)
    private val tDate = SpecText(16f, 600)
    private val tChip = SpecText(14f, 700)
    private val tRadio = SpecText(12f, 600)
    private val tMediaTitle = SpecText(16f, 700)
    private val tMediaSub = SpecText(13f, 600)
    private val tWideName = SpecText(15f, 700)
    private val tWideState = SpecText(12f, 600)
    private val tSqName = SpecText(14f, 700)
    private val tSqState = SpecText(12f, 600)
    private val tShowing = SpecText(14f, 700)
    private val tAuto = SpecText(12f, 700)
    private val tInitial = SpecText(18f, 800)

    // slider fill animation (0.3 s spring when set by a tap)
    private val brightFill = Tween(0.6f)
    private val volFill = Tween(0.5f)

    private enum class Hit { WIFI, BT, AIRPLAY, SPOTIFY, PREV, PLAY, NEXT, MEDIA, BRIGHT, VOL, DND, NIGHT, MIC, CAM, TIMER, AUTO, P0, P1, P2, P3, SETTINGS, GRABBER }
    private val hits = LinkedHashMap<Hit, RectF>()
    private var pressed: Hit? = null
    private var dragSlider: Hit? = null
    private var dragStartValue = 0f
    private var downX = 0f
    private var downY = 0f
    private var closing = false
    private var vt: VelocityTracker? = null
    private var lastSet = 0L

    private val minuteTick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50) }
    }

    init {
        observeWhileAttached(s.system.state) { st ->
            state = st
            tile.night = st.nightActive; roundBtn.night = st.nightActive; chip.night = st.nightActive
            if (dragSlider != Hit.BRIGHT) brightFill.to(st.brightness, 300, FILL_SPRING)
            if (dragSlider != Hit.VOL) volFill.to(st.volume, 300, FILL_SPRING)
            if (shown) invalidate()
        }
        observeWhileAttached(ui.connectivity.state) { radios = it; if (shown) invalidate() }
        observeWhileAttached(s.media.current) { media = it; if (shown) invalidate() }
        observeWhileAttached(s.timers.timers) { timers = it; if (shown) invalidate() }
        observeWhileAttached(s.profiles.profiles) { profiles = it; if (shown) invalidate() }
        observeWhileAttached(s.profiles.active) { active = it; if (shown) invalidate() }
        observeWhileAttached(s.profiles.autoByFace) { autoByFace = it; if (shown) invalidate() }
        observeWhileAttached(s.alarms.next) { nextAlarm = it; if (shown) invalidate() }
        observeWhileAttached(s.island.activities) { list ->
            val d = list.firstNotNullOfOrNull { (it.content as? app.vesper.core.model.LiveContent.BluetoothConnected)?.deviceName }
            if (d != btDevice) { btDevice = d; if (shown) invalidate() }
        }
        buildHits()
        contentDescription = "Control Center"
    }

    fun setBackdrop(b: Backdrop) {
        val old = tile.backdrop
        tile.backdrop = b; roundBtn.backdrop = b
        if (old != null && old.bitmap !== b.bitmap) old.bitmap.recycle()
        invalidate()
    }

    fun onOpening() {
        shown = true
        ui.connectivity.refreshAsync()
        brightFill.snap(state.brightness); volFill.snap(state.volume)
        handler.removeCallbacks(minuteTick); handler.post(minuteTick)
        invalidate()
    }

    fun onClosed() {
        shown = false
        handler.removeCallbacks(minuteTick)
        tile.backdrop?.bitmap?.recycle(); tile.backdrop = null; roundBtn.backdrop = null
        pressed = null; dragSlider = null
    }

    private fun buildHits() {
        hits.clear()
        // connectivity grid: (24,76) 300×180, padding 14, gap 4 → 134×74 cells
        val cellW = (300f - 28f - 4f) / 2f; val cellH = (180f - 28f - 4f) / 2f
        listOf(Hit.WIFI, Hit.BT, Hit.AIRPLAY, Hit.SPOTIFY).forEachIndexed { i, h ->
            val x = 24f + 14f + (i % 2) * (cellW + 4f); val y = 76f + 14f + (i / 2) * (cellH + 4f)
            hits[h] = RectF(x, y, x + cellW, y + cellH)
        }
        // media transport: bottom row of (24,272) 300×184, space-around 48/56/48
        val rowY = 272f + 184f - 16f - 56f
        val free = 268f - 152f; val gap = free / 3f
        val c1 = 24f + 16f + gap / 2 + 24f; val c2 = 24f + 150f; val c3 = 24f + 300f - (16f + gap / 2 + 24f)
        hits[Hit.PREV] = RectF(c1 - 24f, rowY + 4f, c1 + 24f, rowY + 52f)
        hits[Hit.PLAY] = RectF(c2 - 28f, rowY, c2 + 28f, rowY + 56f)
        hits[Hit.NEXT] = RectF(c3 - 24f, rowY + 4f, c3 + 24f, rowY + 52f)
        hits[Hit.MEDIA] = RectF(24f + 16f, 272f + 16f, 24f + 300f - 16f, 272f + 16f + 60f)
        hits[Hit.BRIGHT] = RectF(340f, 76f, 432f, 456f)
        hits[Hit.VOL] = RectF(448f, 76f, 540f, 456f)
        hits[Hit.DND] = RectF(556f, 76f, 738f, 186f)
        hits[Hit.NIGHT] = RectF(754f, 76f, 936f, 186f)
        hits[Hit.MIC] = RectF(556f, 202f, 672f, 318f)
        hits[Hit.CAM] = RectF(688f, 202f, 804f, 318f)
        hits[Hit.TIMER] = RectF(820f, 202f, 936f, 318f)
        hits[Hit.SETTINGS] = RectF(892f, 14f, 936f, 58f)
        hits[Hit.GRABBER] = RectF(430f, 458f, 530f, 480f)
        // AUTO and avatars depend on text widths: set in draw
    }

    // --- drawing ---

    private val night get() = state.nightActive
    private fun ink(c: Int) = if (night) Night.map(c, true) else c

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        drawPanel(canvas)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (ms >= 8 && ui.debugBuild) android.util.Log.d("VesperPerf", "cc panel onDraw ${ms} ms: $lastBreakdown")
    }

    private var lastBreakdown = ""

    // --- one RenderNode per tile ---
    // Each glass tile is two nodes (its glass, then what's on it), recorded only when their key
    // changes: a new backdrop re-records the glass alone, a new value only that tile's content.
    // Opening/closing replays them (the slide itself is the panel's translationY).
    private class Rec(name: String) {
        val node = RenderNode(name).apply { setClipToBounds(false); setPosition(0, 0, 960, 480) }
        var key: Any? = Unit
    }
    private val recs = HashMap<String, Rec>()
    private var records = 0
    private val warmNode = RenderNode("cc-warm")

    /** Draws [body] through the node [name], re-recording it only when [key] changed. */
    private inline fun rec(c: Canvas, name: String, key: Any?, body: (Canvas) -> Unit) {
        if (!c.isHardwareAccelerated) { body(c); return }
        val r = recs.getOrPut(name) { Rec(name) }
        if (r.key != key || !r.node.hasDisplayList()) {
            r.key = key
            val rc = r.node.beginRecording(960, 480)
            try { body(rc) } finally { r.node.endRecording() }
            records++
        }
        c.drawRenderNode(r.node)
    }

    /**
     * Records the tiles while Control Center is closed (at the edge pull's touch-down and after
     * attach), so the first frames of the reveal only replay nodes.
     */
    fun prewarm() {
        if (shown) return
        val t0 = System.nanoTime()
        records = 0
        val rc = warmNode.beginRecording(960, 480)
        try { drawPanel(rc) } finally { warmNode.endRecording(); warmNode.discardDisplayList() }
        if (ui.debugBuild) android.util.Log.d("VesperPerf", "cc prewarm ${(System.nanoTime() - t0) / 100_000 / 10f} ms records=$records")
    }

    private fun drawPanel(canvas: Canvas) {
        val now = Tween.now()
        val running = brightFill.step(now) or volFill.step(now)
        records = 0
        var t = System.nanoTime(); val sb = if (ui.debugBuild) StringBuilder() else null
        fun lap(n: String) { if (sb != null) { val x = System.nanoTime(); sb.append(n).append('=').append((x - t) / 100_000 / 10f).append(' '); t = x } }
        drawHeader(canvas); lap("header")
        drawConnectivity(canvas); lap("radios")
        drawMedia(canvas); lap("media")
        drawSlider(canvas, Hit.BRIGHT, 340f, brightFill.value, Icons.sun)
        drawSlider(canvas, Hit.VOL, 448f, volFill.value, if (volFill.value <= 0.001f) Icons.volumeMuted else Icons.volume); lap("sliders")
        drawWides(canvas); lap("wides")
        drawSquares(canvas); lap("squares")
        drawPeople(canvas); lap("people")
        lastBreakdown = (sb?.toString() ?: "") + "records=$records"
        // grabber: 40×5 at top 4 of a 100×22 button at the bottom centre
        paint.color = ink(withAlpha(Color.WHITE, 0.6f)); paint.style = Paint.Style.FILL; paint.shader = null
        r.set(460f, 462f, 500f, 467f); canvas.drawRoundRect(r, 3f, 3f, paint)
        if (running) postInvalidateOnAnimation()
    }

    private fun pressScale(c: Canvas, h: Hit, rect: RectF) {
        if (pressed == h && dragSlider == null) c.scale(0.96f, 0.96f, rect.centerX(), rect.centerY())
    }

    private fun drawHeader(c: Canvas) {
        val now = LocalDateTime.now()
        val hour = now.hour % 12; val clock = "${if (hour == 0) 12 else hour}:%02d".format(now.minute)
        val date = now.format(DATE_FMT)
        val n = night
        rec(c, "clock", listOf(clock, date, n)) { rc ->
            val base = tClock.baselineIn(16f)
            val x = tClock.draw(rc, clock, 28f, base, ink(Color.WHITE))
            tDate.draw(rc, date, x + 12f, base, ink(withAlpha(Color.WHITE, 0.75f)))
        }
        // settings 44 round glass button (right 24, top 14)
        val sr = hits[Hit.SETTINGS]!!
        c.save(); pressScale(c, Hit.SETTINGS, sr)
        glass(c, roundBtn, "settings", sr.left, sr.top, sr.right, sr.bottom, -1f)
        rec(c, "settings", n) { rc -> Icons.gear.drawCentered(rc, sr.centerX(), sr.centerY(), 22f, ink(Color.WHITE), paint) }
        c.restore()
        if (state.micMuted) rec(c, "chip", n) { rc ->
            val tw = tChip.width("Mic off")
            val w = 14f + 8f + 7f + tw + 14f
            val l = sr.left - 10f - w
            chip.draw(rc, l, 14f, l + w, 58f, 22f)
            paint.color = ink(Color.WHITE); paint.style = Paint.Style.FILL
            rc.drawCircle(l + 14f + 4f, 36f, 4f, paint)
            tChip.draw(rc, "Mic off", l + 14f + 8f + 7f, tChip.baselineCentered(36f), ink(Color.WHITE))
        }
    }

    /** A glass tile: three nodes in paint order (shadow, backdrop, surface), so a new backdrop re-records one draw. */
    private fun glassTile(c: Canvas, name: String, l: Float, t: Float, r: Float, b: Float, radius: Float, fill: Int? = null) =
        glass(c, tile, name, l, t, r, b, radius, fill)

    private fun glass(c: Canvas, p: SysGlassPainter, name: String, l: Float, t: Float, r: Float, b: Float, radius: Float, fill: Int? = null) {
        val n = night
        rec(c, "$name-shadow", n) { rc -> p.draw(rc, l, t, r, b, radius, parts = SysGlassPainter.SHADOW) }
        rec(c, "$name-backdrop", listOf(n, p.backdrop)) { rc -> p.draw(rc, l, t, r, b, radius, parts = SysGlassPainter.BACKDROP) }
        rec(c, "$name-surface", listOf(n, fill)) { rc ->
            p.fillOverride = fill
            p.draw(rc, l, t, r, b, radius, parts = SysGlassPainter.SURFACE)
            p.fillOverride = null
        }
    }

    /** [pressed] when it's one of [these] (sub-buttons that scale inside a tile's content). */
    private fun pressedIn(vararg these: Hit): Hit? = pressed?.takeIf { dragSlider == null && it in these }

    private fun drawConnectivity(c: Canvas) {
        glassTile(c, "radios-glass", 24f, 76f, 324f, 256f, 32f)
        val wifiLabel = when { !radios.wifiOn -> "Wi-Fi"; radios.wifiName != null -> radios.wifiName!!; radios.wifiConnected -> "Connected"; else -> "Wi-Fi" }
        val airplayOn = s.settings.getBoolean(KEY_AIRPLAY, true)
        val spotifyOn = s.settings.getBoolean(KEY_SPOTIFY, false)
        val pr = pressedIn(Hit.WIFI, Hit.BT, Hit.AIRPLAY, Hit.SPOTIFY)
        rec(c, "radios", listOf(night, radios, btDevice, wifiLabel, airplayOn, spotifyOn, pr)) { rc ->
            radio(rc, Hit.WIFI, wifiLabel, Icons.wifi, radios.wifiOn, BLUE)
            // a connected phone (`:feature:phone` publishes BLUETOOTH_CONNECTED) names the cell
            radio(rc, Hit.BT, btDevice ?: "Bluetooth", Icons.bluetooth, radios.bluetoothOn || btDevice != null, BLUE, enabled = radios.bluetoothAvailable || btDevice != null)
            radio(rc, Hit.AIRPLAY, "AirPlay", Icons.airplay, airplayOn, GREEN)
            radio(rc, Hit.SPOTIFY, "Spotify", Icons.spotify, spotifyOn, GREEN)
        }
    }

    private fun radio(c: Canvas, h: Hit, label: String, icon: Icon, on: Boolean, color: Int, enabled: Boolean = true) {
        val cell = hits[h]!!
        c.save(); pressScale(c, h, cell)
        // column: 52 circle, gap 4, 12/600 label, centred in the cell
        val lh = tRadio.normalLineHeight
        val top = cell.centerY() - (52f + 4f + lh) / 2f
        val cx = cell.centerX()
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = ink(if (on) color else withAlpha(Color.WHITE, 0.2f))
        if (!enabled) paint.color = withAlpha(paint.color, 0.4f)
        c.drawCircle(cx, top + 26f, 26f, paint)
        icon.drawCentered(c, cx, top + 26f, 24f, if (night && on) Color.BLACK else ink(Color.WHITE), paint)
        val text = tRadio.ellipsize(label, cell.width() - 6f)
        tRadio.drawCentered(c, text, cx, tRadio.baselineIn(top + 56f, lh), ink(withAlpha(Color.WHITE, 0.85f)))
        c.restore()
    }

    private fun drawMedia(c: Canvas) {
        glassTile(c, "media-glass", 24f, 272f, 324f, 456f, 32f)
        rec(c, "media", listOf(night, media?.state, pressedIn(Hit.PREV, Hit.PLAY, Hit.NEXT))) { rc -> drawMediaContent(rc) }
    }

    private fun drawMediaContent(c: Canvas) {
        val m = media?.state
        val title = m?.title?.ifBlank { null } ?: if (ui.demo) "Low Tide Radio" else "Not playing"
        val sub = if (m != null) listOf(m.artist, when (m.source) {
            app.vesper.core.model.MediaSourceKind.AIRPLAY -> "AirPlay"
            app.vesper.core.model.MediaSourceKind.BLUETOOTH -> m.sourceLabel.ifBlank { "Bluetooth" }
            app.vesper.core.model.MediaSourceKind.SPOTIFY -> "Spotify"
            app.vesper.core.model.MediaSourceKind.OTHER_APP -> m.sourceLabel.ifBlank { "App" }
        }).filter { it.isNotBlank() }.joinToString(" · ") else if (ui.demo) "Marisol Vey · AirPlay" else "AirPlay · Spotify"
        art.artwork(c, m?.artwork, 40f, 288f, 60f, 12f, if (m == null && !ui.demo) 0.35f else 1f, night)
        val tx = 40f + 60f + 12f
        val tw = 24f + 300f - 16f - tx
        val lh1 = tMediaTitle.normalLineHeight; val lh2 = tMediaSub.normalLineHeight
        val top = 318f - (lh1 + lh2) / 2f
        tMediaTitle.draw(c, tMediaTitle.ellipsize(title, tw), tx, tMediaTitle.baselineIn(top, lh1), ink(Color.WHITE))
        tMediaSub.draw(c, tMediaSub.ellipsize(sub, tw), tx, tMediaSub.baselineIn(top + lh1, lh2), ink(withAlpha(Color.WHITE, 0.7f)))
        val playing = m?.playing ?: ui.demo
        val dim = if (m == null && !ui.demo) 0.4f else 1f
        for ((h, icon, size) in listOf(Triple(Hit.PREV, Icons.prev, 26f), Triple(Hit.PLAY, if (playing) Icons.pause32 else Icons.play32, 34f), Triple(Hit.NEXT, Icons.next, 26f))) {
            val rr = hits[h]!!
            c.save(); pressScale(c, h, rr)
            icon.drawCentered(c, rr.centerX(), rr.centerY(), size, ink(withAlpha(Color.WHITE, dim)), paint)
            c.restore()
        }
    }

    private fun drawSlider(c: Canvas, h: Hit, x: Float, value: Float, icon: Icon) {
        val rr = hits[h]!!
        glassTile(c, "glass-$h", x, 76f, x + 92f, 456f, 46f)
        // the fill and icon move with every drag frame: drawn live over the recorded glass (two draws)
        // white fill rising from the bottom (plain fill on glass), clipped to the capsule
        val v = value.coerceIn(0f, 1f)
        if (v > 0f) {
            c.save()
            sliderClip.rewind(); sliderClip.addRoundRect(rr, 46f, 46f, android.graphics.Path.Direction.CW)
            c.clipPath(sliderClip)
            paint.style = Paint.Style.FILL; paint.shader = null
            paint.color = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.94f)
            c.drawRect(x, 456f - 380f * v, x + 92f, 456f, paint)
            c.restore()
        }
        val iconInk = if (night) (if (v > 0.15f) Color.BLACK else Night.COLOR) else if (v > 0.15f) 0xFF1C1C1E.toInt() else Color.WHITE
        icon.drawCentered(c, x + 46f, 456f - 26f - 15f, 30f, iconInk, paint)
    }

    private val sliderClip = android.graphics.Path()

    private fun drawWides(c: Canvas) {
        val dnd = state.doNotDisturb
        val until = nextAlarm?.let { "On until " + Instant.ofEpochMilli(it.triggerAtMillis).atZone(ZoneId.systemDefault()).format(UNTIL_FMT) }
        wide(c, Hit.DND, "Do Not Disturb", if (dnd) until ?: "On" else "Off", Icons.moon, dnd, 0xFF5E5CE6.toInt())
        val nm = state.nightMode
        wide(c, Hit.NIGHT, "Night mode", when (nm) { NightMode.AUTO -> "Auto · in the dark"; NightMode.ON -> "On"; NightMode.OFF -> "Off" }, Icons.night, nm != NightMode.OFF, Night.COLOR)
    }

    private fun wide(c: Canvas, h: Hit, name: String, st: String, icon: Icon, on: Boolean, color: Int) {
        val rr = hits[h]!!
        c.save(); pressScale(c, h, rr)
        glassTile(c, "glass-$h", rr.left, rr.top, rr.right, rr.bottom, 30f)
        rec(c, "content-$h", listOf(night, name, st, on)) { rc -> wideContent(rc, rr, name, st, icon, on, color) }
        c.restore()
    }

    private fun wideContent(c: Canvas, rr: RectF, name: String, st: String, icon: Icon, on: Boolean, color: Int) {
        val cy = rr.centerY()
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = ink(if (on) color else withAlpha(Color.WHITE, 0.2f))
        c.drawCircle(rr.left + 16f + 24f, cy, 24f, paint)
        icon.drawCentered(c, rr.left + 40f, cy, 24f, if (night && on) Color.BLACK else ink(Color.WHITE), paint)
        val tx = rr.left + 16f + 48f + 12f
        // the design's spans wrap (no nowrap): "Do Not / Disturb", "On until / 7:00 AM"
        val tw = rr.right - 16f - tx
        val nameLines = wrapWords(tWideName, name, tw, 2)
        val stLines = wrapWords(tWideState, st, tw, 2)
        val lh1 = 15f * 1.15f; val lh2 = tWideState.normalLineHeight
        var top = cy - (lh1 * nameLines.size + 2f + lh2 * stLines.size) / 2f
        for (l in nameLines) { tWideName.draw(c, l, tx, tWideName.baselineIn(top, lh1), ink(Color.WHITE)); top += lh1 }
        top += 2f
        for (l in stLines) { tWideState.draw(c, l, tx, tWideState.baselineIn(top, lh2), ink(withAlpha(Color.WHITE, 0.72f))); top += lh2 }
    }

    private fun wrapWords(t: SpecText, text: String, maxW: Float, maxLines: Int): List<String> {
        if (t.width(text) <= maxW) return listOf(text)
        val out = ArrayList<String>()
        var line = ""
        val words = text.split(' ')
        for ((i, w) in words.withIndex()) {
            val cand = if (line.isEmpty()) w else "$line $w"
            if (t.width(cand) <= maxW || line.isEmpty()) line = cand
            else if (out.size == maxLines - 1) { line = line + " " + words.subList(i, words.size).joinToString(" "); break }
            else { out += line; line = w }
        }
        out += t.ellipsize(line, maxW)
        return out
    }

    private fun drawSquares(c: Canvas) {
        val mic = state.micMuted || state.micMutedHardware
        square(c, Hit.MIC, "Microphone", when { state.micMutedHardware -> "Off · button"; mic -> "Off"; else -> "On" }, if (mic) Icons.micOff else Icons.mic, mic, RED_DOT)
        val camOff = state.cameraDisabled || state.shutterClosed // the button is a mic button: it doesn't block the camera
        square(c, Hit.CAM, "Camera", when { state.cameraDisabled -> "Off"; state.shutterClosed -> "Shutter closed"; state.cameraInUse -> "In use"; else -> "On" },
            if (camOff) Icons.camOff else Icons.cam, camOff, RED_DOT)
        val t = timers.firstOrNull { it.state != VesperTimer.State.DONE } ?: timers.firstOrNull()
        val tState = if (t == null) "Start" else (t.label.ifBlank { "Timer" } + " · " + mmss(t.remainingMs()))
        square(c, Hit.TIMER, "Timer", tState, Icons.timer, false, ORANGE, iconBgAlways = ORANGE)
        if (shown && t != null && t.state == VesperTimer.State.RUNNING) postInvalidateDelayed(1000 - (t.remainingMs() % 1000) + 10)
    }

    private fun square(c: Canvas, h: Hit, name: String, st: String, icon: Icon, off: Boolean, color: Int, iconBgAlways: Int? = null) {
        val rr = hits[h]!!
        c.save(); pressScale(c, h, rr)
        glassTile(c, "glass-$h", rr.left, rr.top, rr.right, rr.bottom, 30f, if (off && !night) Color.argb(71, 255, 69, 58) else null)
        rec(c, "content-$h", listOf(night, name, st, icon, off)) { rc -> squareContent(rc, rr, name, st, icon, off, color, iconBgAlways) }
        c.restore()
    }

    private fun squareContent(c: Canvas, rr: RectF, name: String, st: String, icon: Icon, off: Boolean, color: Int, iconBgAlways: Int?) {
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = ink(iconBgAlways ?: if (off) color else withAlpha(Color.WHITE, 0.2f))
        val ccx = rr.left + 14f + 21f; val ccy = rr.top + 14f + 21f
        c.drawCircle(ccx, ccy, 21f, paint)
        icon.drawCentered(c, ccx, ccy, 22f, if (night && (off || iconBgAlways != null)) Color.BLACK else ink(Color.WHITE), paint)
        val lh1 = tSqName.normalLineHeight; val lh2 = tSqState.normalLineHeight
        val top = rr.bottom - 14f - lh1 - lh2
        val tw = rr.width() - 28f
        tSqName.draw(c, tSqName.ellipsize(name, tw), rr.left + 14f, tSqName.baselineIn(top, lh1), ink(Color.WHITE))
        tSqState.draw(c, tSqState.ellipsize(st, tw), rr.left + 14f, tSqState.baselineIn(top + lh1, lh2), ink(withAlpha(Color.WHITE, 0.72f)))
    }

    private fun drawPeople(c: Canvas) {
        glassTile(c, "people-glass", 556f, 334f, 936f, 456f, 30f)
        rec(c, "people", listOf(night, active, profiles, autoByFace, pressedIn(Hit.AUTO))) { rc -> peopleContent(rc) }
    }

    private fun peopleContent(c: Canvas) {
        val l = 556f; val t = 334f
        val name = if (active.id == Profile.SHARED_ID) "everyone’s stuff" else "${active.name}’s stuff"
        // top row: centred on the 30 px Auto button
        val rowCy = t + 14f + 15f
        tShowing.draw(c, tShowing.ellipsize("Showing $name", 200f), l + 16f, tShowing.baselineCentered(rowCy), ink(Color.WHITE))
        val autoW = 12f + 14f + 6f + tAuto.width("Auto by face") + 12f
        val ar = RectF(l + 380f - 16f - autoW, t + 14f, l + 380f - 16f, t + 44f)
        hits[Hit.AUTO] = ar
        c.save(); pressScale(c, Hit.AUTO, ar)
        paint.style = Paint.Style.FILL; paint.shader = null
        paint.color = ink(if (autoByFace) withAlpha(Color.WHITE, 0.9f) else withAlpha(Color.WHITE, 0.14f))
        c.drawRoundRect(ar, 15f, 15f, paint)
        val autoInk = if (autoByFace) (if (night) Color.BLACK else 0xFF111114.toInt()) else ink(Color.WHITE)
        Icons.autoFace.draw(c, ar.left + 12f, rowCy - 7f, 14f, autoInk, paint)
        tAuto.draw(c, "Auto by face", ar.left + 12f + 14f + 6f, tAuto.baselineCentered(rowCy), autoInk)
        c.restore()
        // avatars: 52 px, gap 12, at the bottom
        val people = profiles.take(4)
        val ay = t + 122f - 14f - 52f
        listOf(Hit.P0, Hit.P1, Hit.P2, Hit.P3).forEach { hits.remove(it) }
        people.forEachIndexed { i, p ->
            val ax = l + 16f + i * (52f + 12f)
            val cx = ax + 26f; val cy = ay + 26f
            val hit = listOf(Hit.P0, Hit.P1, Hit.P2, Hit.P3)[i]
            hits[hit] = RectF(ax, ay, ax + 52f, ay + 52f)
            if (p.id == active.id) {
                paint.color = ink(Color.WHITE); c.drawCircle(cx, cy, 31f, paint)
                paint.color = withAlpha(Color.BLACK, 0.6f); c.drawCircle(cx, cy, 29f, paint)
            }
            val col = if (p.id == Profile.SHARED_ID || p.color == Color.WHITE) 0xFF8E8E93.toInt() else p.color
            paint.color = ink(col or 0xFF000000.toInt())
            c.drawCircle(cx, cy, 26f, paint)
            val initial = if (p.id == Profile.SHARED_ID) "E" else p.name.take(1).uppercase()
            tInitial.drawCentered(c, initial, cx, tInitial.baselineCentered(cy), if (night) Color.BLACK else Color.WHITE)
        }
    }

    private fun mmss(ms: Long): String {
        val t = ceil(ms / 1000.0).toLong().coerceAtLeast(0)
        return "%d:%02d".format(t / 60, t % 60)
    }

    // --- touch ---

    private fun hitAt(x: Float, y: Float): Hit? = hits.entries.firstOrNull { it.value.contains(x, y) }?.key

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; closing = false
                vt = VelocityTracker.obtain().also { it.addMovement(e) }
                val h = hitAt(x, y)
                pressed = h
                if (h == Hit.BRIGHT || h == Hit.VOL) {
                    dragSlider = h
                    dragStartValue = if (h == Hit.BRIGHT) brightFill.target else volFill.target
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dy = y - downY
                val sl = dragSlider
                if (sl != null) {
                    // iOS-style relative drag over the 380 px track
                    val v = (dragStartValue - dy / 380f).coerceIn(0f, 1f)
                    if (sl == Hit.BRIGHT) brightFill.snap(v) else volFill.snap(v)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSet > 40) { lastSet = now; applySlider(sl, v) }
                    invalidate()
                } else if (closing || dy < -14f) {
                    if (!closing) { closing = true; pressed = null; invalidate() }
                    onDragClose?.invoke(dy, false, false)
                } else if (pressed != null && (kotlin.math.abs(dy) > 12f || kotlin.math.abs(x - downX) > 12f)) {
                    pressed = null; invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                vt?.recycle(); vt = null
                val sl = dragSlider
                val h = pressed
                dragSlider = null; pressed = null
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) { invalidate(); return true }
                when {
                    sl != null -> {
                        val moved = kotlin.math.abs(y - downY) > 6f
                        if (moved) applySlider(sl, if (sl == Hit.BRIGHT) brightFill.value else volFill.value)
                        else {
                            // a tap sets the level where you touched (like the design's onClick)
                            val rr = hits[sl]!!
                            val v = ((rr.bottom - y) / rr.height()).coerceIn(0f, 1f)
                            if (sl == Hit.BRIGHT) brightFill.to(v, 300, FILL_SPRING) else volFill.to(v, 300, FILL_SPRING)
                            applySlider(sl, v)
                        }
                    }
                    closing -> onDragClose?.invoke(y - downY, true, vy < -800f)
                    h != null -> tap(h)
                    kotlin.math.abs(y - downY) < 12f -> requestClose() // tap on empty space closes
                }
                invalidate()
            }
        }
        return true
    }

    private fun applySlider(h: Hit, v: Float) {
        if (h == Hit.BRIGHT) ui.controls.setBrightness(v, showHud = false) else ui.controls.setVolume(v, showHud = false)
    }

    private fun tap(h: Hit) {
        val c = ui.controls
        when (h) {
            Hit.WIFI -> if (!c.setWifiEnabled(!radios.wifiOn)) { requestClose(); c.openWifiPanel() }
            Hit.BT -> c.setBluetoothEnabled(!radios.bluetoothOn)
            Hit.AIRPLAY -> s.settings.putBoolean(KEY_AIRPLAY, !s.settings.getBoolean(KEY_AIRPLAY, true))
            Hit.SPOTIFY -> s.settings.putBoolean(KEY_SPOTIFY, !s.settings.getBoolean(KEY_SPOTIFY, false))
            Hit.PREV -> media?.controls?.previous()
            Hit.PLAY -> media?.let { if (it.state.playing) it.controls.pause() else it.controls.play() }
            Hit.NEXT -> media?.controls?.next()
            Hit.MEDIA -> { requestClose(); s.navigator.show("nowplaying") }
            Hit.DND -> c.setDoNotDisturb(!state.doNotDisturb)
            Hit.NIGHT -> c.setNightMode(if (state.nightMode == NightMode.OFF) NightMode.AUTO else NightMode.OFF)
            Hit.MIC -> c.setMicMuted(!state.micMutedSoftware)
            Hit.CAM -> c.setCameraDisabled(!state.cameraDisabled)
            Hit.TIMER -> { requestClose(); s.navigator.show("timers") }
            Hit.AUTO -> s.profiles.setAutoByFace(!autoByFace)
            Hit.P0, Hit.P1, Hit.P2, Hit.P3 -> profiles.getOrNull(h.ordinal - Hit.P0.ordinal)?.let { s.profiles.switchTo(it.id, SwitchReason.MANUAL) }
            Hit.SETTINGS -> {
                // :feature:settings' pairing screen (URL + QR + code); Android Settings until that screen exists
                requestClose()
                s.navigator.show(SETTINGS_SCREEN)
                postDelayed({
                    if (s.navigator.current.value != SETTINGS_SCREEN)
                        try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                }, 200)
            }
            Hit.GRABBER -> requestClose()
            Hit.BRIGHT, Hit.VOL -> {}
        }
        invalidate()
    }

    override fun onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow() }

    companion object {
        const val KEY_AIRPLAY = "system.cc.airplay"
        const val KEY_SPOTIFY = "system.cc.spotify"
        const val SETTINGS_SCREEN = "settings-pair"
        val BLUE = 0xFF0A84FF.toInt()
        val GREEN = 0xFF30D158.toInt()
        val ORANGE = 0xFFFF9F0A.toInt()
        val RED_DOT = 0xFFFF453A.toInt()
        /** cubic-bezier(0.3, 1.2, 0.5, 1) */
        val FILL_SPRING = bezier(0.3f, 1.2f, 0.5f, 1f)
        // ofPattern parses the pattern each call (~2 ms on the Echo): build them once
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
        private val UNTIL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    }
}
