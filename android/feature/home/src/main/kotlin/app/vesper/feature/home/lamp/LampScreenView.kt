package app.vesper.feature.home.lamp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.HomeAssistant
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.home.home.HomeWallpaper
import app.vesper.feature.home.ui.GlassTile
import app.vesper.feature.home.ui.INK_DARK
import app.vesper.feature.home.ui.Icons
import app.vesper.feature.home.ui.SvgPath
import app.vesper.feature.home.ui.cssLinear
import app.vesper.feature.home.ui.drawCentered
import app.vesper.feature.home.ui.drawRight
import app.vesper.feature.home.ui.ellipsize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Lamp screen (design/Lamp.dc.html), id [SCREEN_ID], not in the pager: one light in detail over
 * the Home wallpaper. A tall brightness slider with the light's own colour as its fill, on/off, warm ↔
 * cool whites and colour swatches (what the light supports, from `supported_color_modes`), three
 * presets, and the room's other lights with their own switches.
 *
 * Opened by a long press on the Home screen's lamp tile (or a tap on its bulb), with the `entity`
 * argument. Debug: `--es screen lamp --es entity light.bedside_lamp`. Back (system or the chevron)
 * returns to Home.
 *
 * Perf (docs/status/home.md): glass shadows are baked into the wallpaper once; each tile redraws only
 * when its data changes; the slider and the white track preview locally while dragging and send one
 * call on release; strings, paths and shaders are built on change, never per frame.
 */
@SuppressLint("ViewConstructor")
class LampScreenView(
    context: Context,
    private val services: VesperServices,
    ha: HomeAssistant,
    private val demo: Boolean,
) : FrameLayout(context), VesperScreen {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = LampController(ha, services.settings, scope)
    private val backdrop: Backdrop
    private val wallpaper: Bitmap
    private var night = false
    private var ui: LampUi? = null
    private var shown = false

    private val bd = { if (night) null else backdrop }
    private val back = BackButton(context, bd)
    private val power = PowerPill(context, bd)
    private val slider = BrightnessSlider(context, bd)
    private val colour = ColourTile(context, bd)
    private val presets = List(3) { PresetView(context, bd) }
    private val room = RoomLightsTile(context, bd)

    private val titleText = SpecText(28f, 800, -0.025f)
    private val lineText = SpecText(14f, 600)
    private val noteText = SpecText(12f, 600)
    private val emptyTitle = SpecText(28f, 800, -0.025f)
    private var title = ""

    init {
        setWillNotDraw(false)
        clipChildren = false
        add(back, 24, 18, 44, 44)
        add(power, 960 - 24 - PowerPill.WIDTH, 18, PowerPill.WIDTH, 44)
        add(slider, 24, 76, 156, 380)
        add(colour, 196, 76, 420, 224)
        presets.forEachIndexed { i, p -> add(p, 196 + i * 145, 316, 130, 140) }
        add(room, 632, 76, 304, 380)

        val plain = HomeWallpaper.render(960, 480)
        backdrop = PreBlur.backdrop(plain, 960f, 480f, blurPx = 20f)
        // The layout never changes, so the shadows are baked once (CSS box-shadow of each tile).
        wallpaper = HomeWallpaper.shadows(plain, listOf(
            HomeWallpaper.Shadow(24f, 18f, 44f, 44f, 22f, 8f, 24f, 0.35f),
            HomeWallpaper.Shadow(960f - 24f - PowerPill.WIDTH, 18f, PowerPill.WIDTH.toFloat(), 44f, 22f, 8f, 24f, 0.30f),
            HomeWallpaper.Shadow(24f, 76f, 156f, 380f, 44f, 16f, 40f, 0.35f),
            HomeWallpaper.Shadow(196f, 76f, 420f, 224f, 32f, 16f, 40f, 0.35f),
            HomeWallpaper.Shadow(196f, 316f, 130f, 140f, 30f, 12f, 30f, 0.30f),
            HomeWallpaper.Shadow(341f, 316f, 130f, 140f, 30f, 12f, 30f, 0.30f),
            HomeWallpaper.Shadow(486f, 316f, 130f, 140f, 30f, 12f, 30f, 0.30f),
            HomeWallpaper.Shadow(632f, 76f, 304f, 380f, 32f, 16f, 40f, 0.35f),
        ))
        plain.recycle()

        back.onTap = { _, _ -> services.navigator.show(HOME_SCREEN) }
        power.onTap = { _, _ -> controller.togglePower() }
        slider.onSet = { controller.setBrightness(it) }
        slider.onToggle = { controller.togglePower() }
        colour.onPreviewKelvin = { k -> slider.previewColor = if (k > 0) LightColors.kelvinDisplay(k) else null }
        colour.onKelvin = { slider.previewColor = null; controller.setKelvin(it) }
        colour.onColor = { controller.setColor(it) }
        colour.onStep = { controller.setBrightness(it) }
        presets.forEachIndexed { i, p -> p.onTap = { _, _ -> if (p.usable) controller.applyPreset(i) } }
        room.onToggle = { controller.toggleOther(it) }
        room.onPick = { controller.show(it) }

        observeWhileAttached(controller.ui) { bind(it) }
        observeWhileAttached(services.system.state) { st ->
            if (st.nightActive != night) {
                night = st.nightActive
                for (i in 0 until childCount) (getChildAt(i) as? GlassTile)?.night = night
                invalidate()
            }
        }
    }

    private fun add(v: android.view.View, x: Int, y: Int, w: Int, h: Int) =
        addView(v, LayoutParams(w, h).apply { leftMargin = x; topMargin = y })

    override fun onScreenShown(args: Bundle?) {
        controller.show(args?.getString(ARG_ENTITY))
        if (!shown) { shown = true; controller.start() }
    }

    override fun onScreenHidden() {
        shown = false
        controller.stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller.stop()
        shown = false
    }

    private fun bind(u: LampUi?) {
        val prev = ui
        ui = u
        val l = u?.light
        val has = l != null
        for (i in 0 until childCount) getChildAt(i).visibility = if (has || getChildAt(i) === back) VISIBLE else INVISIBLE
        if (u == null || l == null) { invalidate(); return }
        val animate = prev?.light?.entityId == l.entityId
        val newTitle = titleText.ellipsize(l.name, 960f - 24f - PowerPill.WIDTH - 16f - 80f)
        if (newTitle != title || prev?.line != u.line || prev?.error != u.error) { title = newTitle; invalidate() }
        power.bind(l.on, l.available, animate)
        slider.bind(l)
        colour.bind(l)
        presets.forEachIndexed { i, p -> p.bind(LampPreset.ALL[i], l, i == u.activePreset, animate) }
        room.bind(u.roomName, u.othersLine, u.others)
    }

    override fun onDraw(canvas: Canvas) {
        if (night) canvas.drawColor(Color.BLACK) else canvas.drawBitmap(wallpaper, 0f, 0f, null)
        val white = if (night) Night.COLOR else Color.WHITE
        val u = ui
        if (u?.light == null) {
            emptyTitle.drawCentered(canvas, if (u == null) "Connecting to Home Assistant" else u.line, 480f, 246f, white)
            return
        }
        titleText.draw(canvas, title, 80f, titleText.baselineIn(18f, 28f * 1.1f), white)
        val line = u.error ?: u.line
        val lineColor = if (u.error != null) 0xFFFFB340.toInt() else withAlpha(Color.WHITE, 0.78f)
        lineText.draw(canvas, line, 80f, lineText.baselineIn(18f + 28f * 1.1f + 2f), Night.map(lineColor, night))
        if (demo) noteText.drawCentered(canvas, DEMO_NOTE, 480f, 473f, Night.map(withAlpha(Color.WHITE, 0.45f), night))
    }

    companion object {
        const val SCREEN_ID = "lamp"
        const val ARG_ENTITY = "entity"
        private const val HOME_SCREEN = "home"
        private const val DEMO_NOTE = "Demo data · connect Home Assistant for your home"
    }
}

// ---- shared bits ----

private val PCT = Array(101) { "$it%" }
private val kLabels = HashMap<Int, String>()
/** "2700 K", cached: the white track asks for it on every move. */
private fun kLabel(k: Int) = kLabels.getOrPut(k) { "$k K" }
private const val INK_WARM = 0xFF3A1A00.toInt()

private object LampType {
    val pct = SpecText(34f, 700, -0.03f)
    val tileTitle = SpecText(16f, 700)
    val tileSub = SpecText(13f, 600)
    val small = SpecText(12f, 600)
    val chip = SpecText(15f, 700)
    val pill = SpecText(15f, 700)
    val rowName = SpecText(15f, 700)
}

private object LampIcons {
    const val CHEVRON = "M15 5l-7 7 7 7"
    const val BOOK = "M3 5.5A1.5 1.5 0 0 1 4.5 4H9a3 3 0 0 1 2 .8V20a3 3 0 0 0-2-.8H4.5A1.5 1.5 0 0 1 3 17.7zM21 5.5A1.5 1.5 0 0 0 19.5 4H15a3 3 0 0 0-2 .8V20a3 3 0 0 1 2-.8h4.5a1.5 1.5 0 0 0 1.5-1.5z"
    const val SUNSET = "M12 9a6 6 0 0 1 6 6H6a6 6 0 0 1 6-6zM11 3h2v3h-2zM3.4 8.8l1.4-1.4 2.1 2.1-1.4 1.4zM17.1 9.5l2.1-2.1 1.4 1.4-2.1 2.1zM2 17h20v2H2z"
}

private fun Canvas.drawAt(p: Path, x: Float, y: Float, paint: Paint) { save(); translate(x, y); drawPath(p, paint); restore() }

// ---- header controls ----

/** 44×44 glass circle with a chevron (ControlCenter.dc.html's settings button recipe). */
@SuppressLint("ViewConstructor")
private class BackButton(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 22f, backdrop) {
    private val chevron = SvgPath.path(LampIcons.CHEVRON, 22f / 24f)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f * 22f / 24f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        stroke.color = if (night) Night.COLOR else Color.WHITE
        canvas.drawAt(chevron, 11f, 11f, stroke)
    }
}

/** On / off: a glass capsule that turns white when the light is on (Control Center's "on" fill). */
@SuppressLint("ViewConstructor")
private class PowerPill(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 22f, backdrop) {
    private val icon = SvgPath.path(Icons.POWER, 20f / 24f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var on = false
    private var usable = true

    fun bind(on: Boolean, usable: Boolean, animate: Boolean) {
        if (on != this.on || usable != this.usable) { this.on = on; this.usable = usable; alpha = if (usable) 1f else 0.4f; invalidate() }
        setOn(on, animate)
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        paint.color = ink()
        canvas.drawAt(icon, 14f, 12f, paint)
        LampType.pill.draw(canvas, if (on) "On" else "Off", 42f, LampType.pill.baselineIn(0f, h), ink())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = if (usable) super.onTouchEvent(e) else true
    /** Dimmed with View alpha: no offscreen layer for it. */
    override fun hasOverlappingRendering() = false

    companion object {
        /** Padding 14 + icon 20 + gap 8 + the wider label + padding 18. */
        val WIDTH: Int by lazy { (14f + 20f + 8f + maxOf(LampType.pill.width("On"), LampType.pill.width("Off")) + 18f).roundToInt() }
    }
}

// ---- brightness ----

/**
 * 156×380 vertical slider (ControlCenter.dc.html's tall sliders, larger). The fill rises from the
 * bottom in the light's own colour: its white, its colour, or warm 2700 K for lights that only dim.
 * Drag moves it relative to where it was (as in iOS); a tap sets it in 5 % steps. Sent on release.
 */
@SuppressLint("ViewConstructor")
private class BrightnessSlider(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, RADIUS, backdrop) {
    override val springs = false
    var onSet: ((Int) -> Unit)? = null
    var onToggle: (() -> Unit)? = null
    /** While the white track is dragged: its colour, shown in the fill before HA has it. */
    var previewColor: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private var light: LightDetail? = null
    private var shown = 0
    private var dragging = false
    private var downY = 0f
    private var startPct = 0
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bulb = SvgPath.path(Icons.LAMP_BULB, 30f / 24f)
    private val shape = Path()
    private val bar = Path()
    private val fillPath = Path()
    private var fillFor = -1
    private var fillH = -1f
    private var shaderColor = -1
    private var shader: Shader? = null
    private var spec: Shader? = null

    fun bind(l: LightDetail) {
        val changed = l != light
        light = l
        if (!dragging) shown = l.pct
        if (changed) invalidate()
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val l = light ?: return
        val pct = shown
        val c = previewColor ?: LightColors.fill(l)
        if (pct > 0) {
            val top = h * (1f - pct / 100f)
            if (fillFor != pct || fillH != h) {
                fillFor = pct; fillH = h
                shape.reset(); rect.set(0f, 0f, w, h); shape.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW)
                bar.reset(); rect.set(0f, top, w, h); bar.addRect(rect, Path.Direction.CW)
                fillPath.op(shape, bar, Path.Op.INTERSECT)
            }
            if (night) { fillPaint.shader = null; fillPaint.color = withAlpha(Night.COLOR, 0.45f) }
            else {
                if (shader == null || shaderColor != c) {
                    shaderColor = c
                    // linear-gradient(0deg, c .94, mix(c, white, .35) .96) over the whole track
                    val topC = withAlpha(LightColors.lerpRgb(c, 0xFFFFFF, 0.35f), 0.96f)
                    shader = LinearGradient(0f, 0f, 0f, h, topC, withAlpha(c, 0.94f), Shader.TileMode.CLAMP)
                }
                fillPaint.color = Color.BLACK // opaque: the paint's alpha also scales the shader
                fillPaint.shader = shader
            }
            canvas.drawPath(fillPath, fillPaint)
            fillPaint.shader = null
            if (!night) {
                if (spec == null) spec = cssLinear(170f, w, h, intArrayOf(withAlpha(Color.WHITE, 0.22f), 0x00FFFFFF), floatArrayOf(0f, 0.4f))
                specPaint.shader = spec
                canvas.drawPath(fillPath, specPaint)
            }
        }
        val dark = if (LightColors.luminance(c) > 0.45f) INK_WARM else Color.WHITE
        val label = if (!l.available) "—" else if (pct == 0) "Off" else PCT[pct]
        val labelOnFill = pct >= 84
        val topInk = if (night) Night.COLOR else if (labelOnFill) dark else Color.WHITE
        LampType.pct.drawCentered(canvas, label, w / 2f, LampType.pct.baselineIn(24f), topInk)
        iconPaint.color = if (night) Night.COLOR else if (pct >= 15) dark else Color.WHITE
        canvas.drawAt(bulb, (w - 30f) / 2f, h - 26f - 30f, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = light ?: return true
        if (!l.available) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; startPct = shown; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (l.dims && (dragging || abs(e.y - downY) > 10f)) {
                dragging = true
                val p = (startPct + (downY - e.y) / height * 100f).roundToInt().coerceIn(0, 100)
                if (p != shown) { shown = p; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (!l.dims) { if (!dragging) onToggle?.invoke(); return true }
                val v = if (dragging) shown else ((1f - e.y / height).coerceIn(0f, 1f) * 20).roundToInt() * 5
                dragging = false; shown = v; invalidate(); onSet?.invoke(v)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; shown = l.pct; invalidate() }
        }
        return true
    }

    companion object { const val RADIUS = 44f }
}

// ---- whites and colours ----

/**
 * 420×224: what the light is showing ("Warm white · 2700 K"), the warm ↔ cool track over the light's
 * own range, then swatches: seven colours (colour lights) or five whites (white-only lights). Lights
 * that only dim get brightness steps instead; on/off lights just a note.
 */
@SuppressLint("ViewConstructor")
private class ColourTile(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 32f, backdrop) {
    override val springs = false
    var onPreviewKelvin: ((Int) -> Unit)? = null
    var onKelvin: ((Int) -> Unit)? = null
    var onColor: ((Int) -> Unit)? = null
    var onStep: ((Int) -> Unit)? = null

    private var light: LightDetail? = null
    private var swatches: List<LightColors.Swatch> = emptyList()
    private var selected = -1
    private var title = ""
    private var sub = ""
    /** Kelvin under the finger while dragging the track, else 0. */
    private var dragK = 0
    private var downSwatch = -1
    private var downStep = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = withAlpha(Color.BLACK, 0.25f) }
    private val track = RectF(TX, TY, TX + TW, TY + TH)
    private var trackShader: Shader? = null
    private var trackFor = 0L
    private val swatchColors = IntArray(7)

    fun bind(l: LightDetail) {
        if (l == light) return
        light = l
        swatches = when (l.kind) { LightKind.COLOR -> LightColors.COLORS; LightKind.TEMP -> LightColors.WHITES; else -> emptyList() }
        swatches.forEachIndexed { i, s -> swatchColors[i] = LightColors.swatchColor(s) }
        selected = LightColors.selectedSwatch(l, swatches)
        updateText(l, dragK)
        val key = (l.minKelvin.toLong() shl 20) or l.maxKelvin.toLong()
        if (key != trackFor) { trackFor = key; trackShader = null }
        invalidate()
    }

    private fun updateText(l: LightDetail, previewK: Int) {
        if (previewK > 0) { title = LightColors.whiteName(previewK); sub = kLabel(previewK); return }
        title = when (l.kind) { LightKind.ONOFF -> "On / off"; LightKind.DIM -> "Brightness"; else -> LightColors.name(l) }
        sub = when {
            l.kind == LightKind.ONOFF -> "This light doesn't dim"
            l.kind == LightKind.DIM -> "No colour or white tones"
            l.kelvin != null -> kLabel(l.kelvin)
            l.rgb != null -> "Colour"
            else -> if (l.colors) "Pick a white or a colour" else "Pick a white"
        }
    }

    private fun kelvinAt(x: Float, l: LightDetail): Int {
        val f = ((x - TX - TH / 2f) / (TW - TH)).coerceIn(0f, 1f)
        return ((l.minKelvin + f * (l.maxKelvin - l.minKelvin)) / 50f).roundToInt() * 50
    }

    private fun swatchX(i: Int): Float {
        val n = swatches.size
        val gap = if (n > 1) (TW - n * SW) / (n - 1) else 0f
        return TX + i * (SW + gap)
    }

    private fun stepX(i: Int) = TX + i * (STEP_W + STEP_GAP)

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val l = light ?: return
        val white = if (night) Night.COLOR else Color.WHITE
        LampType.tileTitle.draw(canvas, title, 20f, LampType.tileTitle.baselineIn(18f), white)
        LampType.tileSub.draw(canvas, sub, 20f, LampType.tileSub.baselineIn(18f + LampType.tileTitle.normalLineHeight), Night.map(withAlpha(Color.WHITE, 0.75f), night))
        when (l.kind) {
            LightKind.COLOR, LightKind.TEMP -> drawWhites(canvas, l)
            LightKind.DIM -> drawSteps(canvas, l)
            LightKind.ONOFF -> {}
        }
    }

    private fun drawWhites(canvas: Canvas, l: LightDetail) {
        // track: the light's own range, warm (left) to cool (right)
        if (night) { paint.shader = null; paint.color = withAlpha(Night.COLOR, 0.3f) }
        else {
            if (trackShader == null) {
                val n = 5
                val colors = IntArray(n) { i -> withAlpha(LightColors.kelvinDisplay(l.minKelvin + (l.maxKelvin - l.minKelvin) * i / (n - 1)), 1f) }
                trackShader = LinearGradient(TX, 0f, TX + TW, 0f, colors, null, Shader.TileMode.CLAMP)
            }
            paint.color = Color.BLACK
            paint.shader = trackShader
        }
        canvas.drawRoundRect(track, TH / 2f, TH / 2f, paint)
        paint.shader = null
        if (!night) canvas.drawRoundRect(track, TH / 2f, TH / 2f, rim)
        val k = if (dragK > 0) dragK else if (l.on) l.kelvin else null
        if (k != null) {
            val f = ((k - l.minKelvin).toFloat() / (l.maxKelvin - l.minKelvin).coerceAtLeast(1)).coerceIn(0f, 1f)
            val cx = TX + TH / 2f + f * (TW - TH)
            val cy = TY + TH / 2f
            if (!night) { paint.color = withAlpha(Color.BLACK, 0.22f); canvas.drawCircle(cx, cy + 1.5f, 19.5f, paint) }
            paint.color = if (night) Night.COLOR else Color.WHITE
            canvas.drawCircle(cx, cy, 18f, paint)
            paint.color = if (night) Color.BLACK else withAlpha(LightColors.kelvinDisplay(k), 1f)
            canvas.drawCircle(cx, cy, 15f, paint)
        }
        val dim = Night.map(withAlpha(Color.WHITE, 0.6f), night)
        LampType.small.draw(canvas, "Warm", TX, LampType.small.baselineIn(TY + TH + 6f), dim)
        LampType.small.drawRight(canvas, "Cool", TX + TW, LampType.small.baselineIn(TY + TH + 6f), dim)

        // swatches
        val r = SW / 2f
        for (i in swatches.indices) {
            val cx = swatchX(i) + r; val cy = SY + r
            if (night) { ring.color = withAlpha(Night.COLOR, if (i == selected) 1f else 0.5f); ring.strokeWidth = 2f; canvas.drawCircle(cx, cy, r - 1f, ring) }
            else { paint.color = withAlpha(swatchColors[i], 1f); canvas.drawCircle(cx, cy, r, paint); canvas.drawCircle(cx, cy, r, rim) }
            if (i == selected) {
                ring.strokeWidth = 3f
                ring.color = if (night) Night.COLOR else Color.WHITE
                canvas.drawCircle(cx, cy, r + 4.5f, ring)
            }
        }
    }

    private fun drawSteps(canvas: Canvas, l: LightDetail) {
        for ((i, p) in LightColors.STEPS.withIndex()) {
            val x = stepX(i)
            val sel = l.on && abs(l.pct - p) <= 2
            paint.color = if (night) withAlpha(Night.COLOR, if (sel) 0.35f else 0.15f) else withAlpha(Color.WHITE, if (sel) 0.9f else 0.16f)
            rect.set(x, TY, x + STEP_W, TY + TH); canvas.drawRoundRect(rect, TH / 2f, TH / 2f, paint)
            val ink = if (night) Night.COLOR else if (sel) INK_DARK else Color.WHITE
            LampType.chip.drawCentered(canvas, PCT[p], x + STEP_W / 2f, LampType.chip.baselineIn(TY, TH), ink)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = light ?: return true
        if (!l.available) return true
        when (l.kind) {
            LightKind.COLOR, LightKind.TEMP -> touchWhites(e, l)
            LightKind.DIM -> touchSteps(e)
            LightKind.ONOFF -> {}
        }
        return true
    }

    private fun touchWhites(e: MotionEvent, l: LightDetail) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downSwatch = -1
                if (e.y in TY - 10f..TY + TH + 10f && e.x in TX - 8f..TX + TW + 8f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    preview(kelvinAt(e.x, l), l)
                } else if (e.y in SY - 8f..SY + SW + 8f) {
                    downSwatch = swatches.indices.firstOrNull { e.x in swatchX(it) - 6f..swatchX(it) + SW + 6f } ?: -1
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragK > 0) preview(kelvinAt(e.x, l), l)
            MotionEvent.ACTION_UP -> {
                if (dragK > 0) { val k = dragK; dragK = 0; updateText(l, 0); invalidate(); onKelvin?.invoke(k) }
                else if (downSwatch >= 0 && e.y in SY - 8f..SY + SW + 8f && e.x in swatchX(downSwatch) - 6f..swatchX(downSwatch) + SW + 6f) {
                    val s = swatches[downSwatch]
                    if (s.kelvin > 0) onKelvin?.invoke(s.kelvin) else onColor?.invoke(s.rgb)
                }
                downSwatch = -1
            }
            MotionEvent.ACTION_CANCEL -> { if (dragK > 0) { dragK = 0; updateText(l, 0); invalidate(); onPreviewKelvin?.invoke(0) }; downSwatch = -1 }
        }
    }

    private fun preview(k: Int, l: LightDetail) {
        if (k == dragK) return
        dragK = k
        updateText(l, k)
        invalidate()
        onPreviewKelvin?.invoke(k)
    }

    private fun touchSteps(e: MotionEvent) {
        val hit = if (e.y in TY - 8f..TY + TH + 8f) LightColors.STEPS.indices.firstOrNull { e.x in stepX(it)..stepX(it) + STEP_W } ?: -1 else -1
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downStep = hit
            MotionEvent.ACTION_UP -> { if (hit >= 0 && hit == downStep) onStep?.invoke(LightColors.STEPS[hit]); downStep = -1 }
            MotionEvent.ACTION_CANCEL -> downStep = -1
        }
    }

    companion object {
        const val TX = 20f; const val TY = 70f; const val TW = 380f; const val TH = 44f
        const val SY = 154f; const val SW = 44f
        const val STEP_W = 68f; const val STEP_GAP = 10f
    }
}

// ---- presets ----

/** 130×140 preset button: coloured dot with icon, name and what it sets. White when it's the current look. */
@SuppressLint("ViewConstructor")
private class PresetView(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 30f, backdrop) {
    var usable = true; private set
    private var preset: LampPreset? = null
    private var detail = ""
    private var icon: Path? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun bind(p: LampPreset, l: LightDetail, active: Boolean, animate: Boolean) {
        val d = p.detail(l)
        val en = l.dims && l.available
        if (p != preset || d != detail || en != usable) {
            if (p != preset) icon = SvgPath.path(when (p.id) { "reading" -> LampIcons.BOOK; "relax" -> LampIcons.SUNSET; else -> Icons.MOON }, 19f / 24f)
            preset = p; detail = d; usable = en
            alpha = if (en) 1f else 0.4f
            invalidate()
        }
        setOn(active, animate)
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val p = preset ?: return
        paint.color = if (night) withAlpha(Night.COLOR, 0.3f) else p.dot
        canvas.drawCircle(16f + 18f, 16f + 18f, 18f, paint)
        paint.color = if (night) Night.COLOR else Color.WHITE
        icon?.let { canvas.drawAt(it, 16f + 18f - 9.5f, 16f + 18f - 9.5f, paint) }
        val block = LampType.chip.normalLineHeight + 2f + LampType.small.normalLineHeight
        val top = h - 16f - block
        LampType.chip.draw(canvas, p.name, 16f, LampType.chip.baselineIn(top), ink())
        LampType.small.draw(canvas, detail, 16f, LampType.small.baselineIn(top + LampType.chip.normalLineHeight + 2f), ink(0.72f))
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = if (usable) super.onTouchEvent(e) else true
    override fun hasOverlappingRendering() = false
}

// ---- the room's other lights ----

/**
 * 304×380 "In Bedroom": up to four of the room's other lights, each with a switch. Rows are plain
 * fills on the glass (no glass on glass). Tapping a switch toggles that light; tapping the row opens it.
 */
@SuppressLint("ViewConstructor")
private class RoomLightsTile(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 32f, backdrop) {
    override val springs = false
    var onToggle: ((String) -> Unit)? = null
    var onPick: ((String) -> Unit)? = null

    private var header = ""
    private var line = ""
    private var rows: List<RoomLight> = emptyList()
    private var names: List<String> = emptyList()
    private var downRow = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bulb = SvgPath.path(Icons.BULB, 22f / 24f)

    fun bind(roomName: String?, othersLine: String, others: List<RoomLight>) {
        val h = if (roomName != null) "In $roomName" else "Lights"
        if (h == header && othersLine == line && others == rows) return
        header = LampType.tileTitle.ellipsize(h, 264f); line = othersLine; rows = others
        names = others.map { LampType.rowName.ellipsize(it.name, SWITCH_X - 12f - 72f) }
        invalidate()
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val white = if (night) Night.COLOR else Color.WHITE
        LampType.tileTitle.draw(canvas, header, 20f, LampType.tileTitle.baselineIn(18f), white)
        LampType.tileSub.draw(canvas, line, 20f, LampType.tileSub.baselineIn(18f + LampType.tileTitle.normalLineHeight), Night.map(withAlpha(Color.WHITE, 0.75f), night))
        for ((i, r) in rows.withIndex()) {
            val y = ROW_Y + i * ROW_H
            // icon box: orange when on (Home's light accent)
            paint.color = if (night) withAlpha(Night.COLOR, if (r.on) 0.45f else 0.2f) else if (r.on) 0xFFFF9F0A.toInt() else withAlpha(Color.WHITE, 0.2f)
            rect.set(20f, y + 17f, 60f, y + 57f); canvas.drawRoundRect(rect, 13f, 13f, paint)
            paint.color = white
            canvas.drawAt(bulb, 29f, y + 26f, paint)
            val block = LampType.rowName.normalLineHeight + 1f + LampType.small.normalLineHeight
            val top = y + (ROW_H - block) / 2f
            val a = if (r.available) 1f else 0.5f
            LampType.rowName.draw(canvas, names[i], 72f, LampType.rowName.baselineIn(top), Night.map(withAlpha(Color.WHITE, a), night))
            LampType.small.draw(canvas, r.stateText, 72f, LampType.small.baselineIn(top + LampType.rowName.normalLineHeight + 1f), Night.map(withAlpha(Color.WHITE, 0.72f * a), night))
            // switch 51×31
            val sy = y + (ROW_H - 31f) / 2f
            paint.color = if (night) withAlpha(Night.COLOR, if (r.on) 0.6f else 0.2f) else if (r.on) 0xFF30D158.toInt() else withAlpha(Color.WHITE, 0.16f)
            rect.set(SWITCH_X, sy, SWITCH_X + 51f, sy + 31f); canvas.drawRoundRect(rect, 15.5f, 15.5f, paint)
            val kx = if (r.on) SWITCH_X + 51f - 2f - 13.5f else SWITCH_X + 2f + 13.5f
            if (!night) { paint.color = withAlpha(Color.BLACK, 0.18f); canvas.drawCircle(kx, sy + 15.5f + 1f, 13.5f, paint) }
            paint.color = if (night) Night.COLOR else Color.WHITE
            canvas.drawCircle(kx, sy + 15.5f, 13.5f, paint)
            if (i < rows.size - 1) {
                paint.color = Night.map(withAlpha(Color.WHITE, 0.12f), night)
                canvas.drawRect(72f, y + ROW_H - 0.5f, w - 20f, y + ROW_H + 0.5f, paint)
            }
        }
        if (rows.isEmpty()) LampType.tileSub.drawCentered(canvas, "Other lights in this room show here", w / 2f, h / 2f, Night.map(withAlpha(Color.WHITE, 0.6f), night))
    }

    private fun rowAt(y: Float): Int = ((y - ROW_Y) / ROW_H).toInt().takeIf { y >= ROW_Y && it < rows.size } ?: -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = rowAt(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> downRow = i
            MotionEvent.ACTION_UP -> {
                if (i >= 0 && i == downRow && rows[i].available) {
                    if (e.x >= SWITCH_X - 12f) onToggle?.invoke(rows[i].entityId) else onPick?.invoke(rows[i].entityId)
                }
                downRow = -1
            }
            MotionEvent.ACTION_CANCEL -> downRow = -1
        }
        return true
    }

    companion object {
        const val ROW_Y = 70f; const val ROW_H = 74f
        const val SWITCH_X = 304f - 20f - 51f
    }
}
