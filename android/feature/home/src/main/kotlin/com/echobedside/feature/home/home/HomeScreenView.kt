package com.echobedside.feature.home.home

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
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.Night
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.HomeAssistant
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.home.HomeSettings
import com.echobedside.feature.home.ui.Icons
import com.echobedside.feature.home.ui.cssLinear
import com.echobedside.feature.home.ui.drawCentered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers

/**
 * Home.dc.html: Control-Center-style glass tiles for the selected room. Optional (Home Assistant
 * only): registered when HA is configured, or with [DemoHomeAssistant] in demo mode, labelled "Demo".
 */
@SuppressLint("ViewConstructor")
open class HomeScreenView(
    context: Context,
    private val services: EchoServices,
    private val ha: HomeAssistant,
    private val demo: Boolean,
    defaults: (String, Slot) -> List<String>? = { _, _ -> null },
) : FrameLayout(context), EchoScreen {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = HomeController(ha, services.settings, scope, defaults = defaults)
    private val plain: Bitmap = HomeWallpaper.render(960, 480)
    private val backdrop: Backdrop = PreBlur.backdrop(plain, 960f, 480f, blurPx = 20f)
    /** [plain] with the tiles' shadows baked in (redone only when the room switcher's width changes). */
    private var wallpaper: Bitmap = plain
    private var shadowsFor = -1
    private var night = false
    private var ui: HomeUi? = null
    private var shown = false

    private val bd = { if (night) null else backdrop }
    private val switcher = RoomSwitcher(context, bd)
    private val thermostat = ThermostatView(context, bd)
    private val lamp = LampView(context, bd)
    private val toggles = List(2) { SmallTile(context, bd) }
    private val scenes = List(4) { SceneView(context, bd) }
    private val secure = List(2) { SmallTile(context, bd) }

    private val titleText = SpecText(28f, 800, -0.025f)
    private val lineText = SpecText(14f, 600)
    private val noteText = SpecText(12f, 600)
    private val emptyTitle = SpecText(28f, 800, -0.025f)
    private val emptyBody = SpecText(17f, 600)

    init {
        setWillNotDraw(false)
        clipChildren = false
        add(thermostat, 24, 76, 300, 380)
        add(lamp, 340, 76, 268, 182)
        toggles.forEachIndexed { i, t -> add(t, 340 + i * 142, 274, 126, 182) }
        scenes.forEachIndexed { i, s -> add(s, 624 + (i % 2) * 164, 76 + (i / 2) * 99, 148, 83) }
        secure.forEachIndexed { i, s -> add(s, 624 + i * 164, 274, 148, 182) }
        addView(switcher, LayoutParams(10, 44).apply { topMargin = 18 })

        switcher.onPick = { controller.selectRoom(it.id) }
        thermostat.onNudge = { d -> ui?.thermostat?.let { controller.nudgeTarget(it.entityId, d) } }
        thermostat.onMode = { ui?.thermostat?.let { controller.cycleMode(it.entityId) } }
        lamp.onPreview = { p -> ui?.lamp?.let { controller.previewBrightness(it.entityId, p) } }
        lamp.onSet = { p -> ui?.lamp?.let { controller.setBrightness(it.entityId, p) } }
        toggles.forEachIndexed { i, t -> t.onTap = { _, _ -> ui?.toggles?.getOrNull(i)?.let { controller.toggle(it.entityId) } } }
        scenes.forEachIndexed { i, s -> s.onTap = { _, _ -> ui?.scenes?.getOrNull(i)?.let { controller.activateScene(it.entityId) } } }
        secure.forEachIndexed { i, s -> s.onTap = { _, _ -> ui?.secure?.getOrNull(i)?.let { controller.toggleSecure(it.entityId) } } }

        observeWhileAttached(controller.ui) { bind(it) }
        observeWhileAttached(services.system.state) { st ->
            if (st.nightActive != night) {
                night = st.nightActive
                for (i in 0 until childCount) (getChildAt(i) as? com.echobedside.feature.home.ui.GlassTile)?.night = night
                invalidate()
            }
        }
    }

    private fun add(v: android.view.View, x: Int, y: Int, w: Int, h: Int) =
        addView(v, LayoutParams(w, h).apply { leftMargin = x; topMargin = y })

    override fun onScreenShown(args: Bundle?) {
        args?.getString("home.set")?.let { HomeSettings.applyAssignments(services.settings, it) }
        if (!shown) { shown = true; controller.start() }
    }

    override fun onScreenHidden() {
        // HA pushes state; nothing to poll. Keep following state only while visible.
        shown = false
        controller.stop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        controller.stop()
    }

    private fun bind(u: HomeUi?) {
        val prev = ui
        ui = u
        val animate = prev != null && prev.selected?.id == u?.selected?.id
        val has = u?.selected != null
        for (i in 0 until childCount) getChildAt(i).visibility = if (has) VISIBLE else INVISIBLE
        if (u == null || !has) { invalidate(); return }

        val rooms = u.rooms.take(4)
        val w = switcher.widthFor(rooms)
        (switcher.layoutParams as LayoutParams).let { lp ->
            val sw = kotlin.math.ceil(w).toInt()
            if (lp.width != sw) { lp.width = sw; lp.leftMargin = (960 - 24 - lp.width); switcher.layoutParams = lp }
        }
        switcher.bind(rooms, u.selected?.id)

        thermostat.visibility = if (u.thermostat != null) VISIBLE else INVISIBLE
        thermostat.tile = u.thermostat
        lamp.visibility = if (u.lamp != null) VISIBLE else INVISIBLE
        lamp.tile = u.lamp
        toggles.forEachIndexed { i, v ->
            val t = u.toggles.getOrNull(i)
            v.visibility = if (t != null) VISIBLE else INVISIBLE
            if (t != null) v.bind(t.name, t.stateText, Icons.of(t.icon), t.accent, t.on, animate)
        }
        scenes.forEachIndexed { i, v ->
            val t = u.scenes.getOrNull(i)
            v.visibility = if (t != null) VISIBLE else INVISIBLE
            if (t != null) v.bind(t, animate)
        }
        secure.forEachIndexed { i, v ->
            val t = u.secure.getOrNull(i)
            v.visibility = if (t != null) VISIBLE else INVISIBLE
            if (t != null) v.bind(t.name, t.stateText, Icons.of(t.icon), 0xFFFF9F0A.toInt(), t.on, animate)
        }
        bakeShadows()
        invalidate()
    }

    /**
     * The tiles' drop shadows, drawn once into the wallpaper (see [com.echobedside.feature.home.ui.GlassTile]).
     * Redone only when which tiles show, or the switcher's width, changes (a room with fewer slots).
     */
    private fun bakeShadows() {
        val list = ArrayList<HomeWallpaper.Shadow>()
        var key = 17
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility != VISIBLE) continue
            val lp = v.layoutParams as LayoutParams
            val x = (if (v === switcher) 960 - 24 - lp.width else lp.leftMargin).toFloat()
            key = key * 31 + i * 7919 + lp.width
            list += when (v) {
                switcher -> HomeWallpaper.Shadow(x, lp.topMargin.toFloat(), lp.width.toFloat(), lp.height.toFloat(), 22f, 8f, 24f, 0.30f)
                is SceneView -> HomeWallpaper.Shadow(x, lp.topMargin.toFloat(), lp.width.toFloat(), lp.height.toFloat(), 24f, 12f, 30f, 0.30f)
                is ThermostatView -> HomeWallpaper.Shadow(x, lp.topMargin.toFloat(), lp.width.toFloat(), lp.height.toFloat(), 34f, 16f, 40f, 0.35f)
                else -> HomeWallpaper.Shadow(x, lp.topMargin.toFloat(), lp.width.toFloat(), lp.height.toFloat(), 30f, 16f, 40f, 0.35f)
            }
        }
        if (key == shadowsFor) return
        shadowsFor = key
        wallpaper = HomeWallpaper.shadows(plain, list) // the old one is left to the GC: a display list may still hold it
    }

    override fun onDraw(canvas: Canvas) {
        if (night) canvas.drawColor(Color.BLACK) else canvas.drawBitmap(wallpaper, 0f, 0f, null)
        val white = if (night) Night.COLOR else Color.WHITE
        val u = ui
        if (u?.selected == null) {
            val (t, b) = when {
                u != null -> "No rooms yet" to "Give your lights areas in Home Assistant"
                else -> "Connecting to Home Assistant" to " "
            }
            emptyTitle.drawCentered(canvas, t, 480f, 230f, white)
            emptyBody.drawCentered(canvas, b, 480f, 262f, Night.map(withAlpha(Color.WHITE, 0.7f), night))
            return
        }
        titleText.draw(canvas, u.title, 28f, titleText.baselineIn(18f, 28f * 1.1f), white)
        val line = u.error ?: u.line
        val lineColor = if (u.error != null) 0xFFFFB340.toInt() else withAlpha(Color.WHITE, 0.78f)
        lineText.draw(canvas, line, 28f, lineText.baselineIn(18f + 28f * 1.1f + 2f), Night.map(lineColor, night))
        if (demo) noteText.drawCentered(canvas, "Demo data · connect Home Assistant for your home", 480f, 473f, Night.map(withAlpha(Color.WHITE, 0.45f), night))
    }
}

/** The Home.dc.html background, rendered once (then pre-blurred for the glass). */
object HomeWallpaper {
    /** CSS `box-shadow: 0 [dy] [blur] rgba(0,0,0,[alpha])` under a rounded rect. */
    data class Shadow(val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float, val dy: Float, val blur: Float, val alpha: Float)

    /** A copy of [base] with [shadows] drawn outside their shapes (the glass is see-through). */
    fun shadows(base: Bitmap, shadows: List<Shadow>): Bitmap {
        val b = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val r = RectF(); val path = Path()
        for (s in shadows) {
            // CSS blur B is a Gaussian with σ = B/2; setShadowLayer(radius) uses σ ≈ 0.577·radius + 0.5.
            val radius = ((s.blur / 2f) - 0.5f) / 0.57735f
            p.setShadowLayer(radius, 0f, s.dy, withAlpha(Color.BLACK, s.alpha))
            r.set(s.x, s.y, s.x + s.w, s.y + s.h)
            path.reset(); path.addRoundRect(r, s.radius, s.radius, Path.Direction.CW)
            c.save(); c.clipOutPath(path); c.drawPath(path, p); c.restore()
        }
        return b
    }

    fun render(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        val fw = w.toFloat(); val fh = h.toFloat()
        // Bottom layer first (CSS lists the top layer first).
        p.shader = LinearGradient(0f, 0f, 0f, fh, 0xFF2A1442.toInt(), 0xFF0C0818.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, p)
        radial(c, p, 0.70f * fw, fh, 0.65f * fw, 0.70f * fh, 0xCC285ADC.toInt(), 0.72f)       // rgba(40,90,220,.8)
        radial(c, p, 0.88f * fw, 0.16f * fh, 0.50f * fw, 0.65f * fh, 0xBF7850FF.toInt(), 0.70f) // rgba(120,80,255,.75)
        radial(c, p, 0.12f * fw, 0.20f * fh, 0.55f * fw, 0.70f * fh, 0xBFFF6E5A.toInt(), 0.70f) // rgba(255,110,90,.75)
        band(c, p, fw, fh, 0x66965AFF, 0.54f, 0.64f, 0.74f)  // rgba(150,90,255,.4)
        band(c, p, fw, fh, 0x6BFF785A, 0.24f, 0.36f, 0.48f)  // rgba(255,120,90,.42)
        return b
    }

    /** radial-gradient(ellipse rx ry at cx cy, color 0%, transparent stop). */
    private fun radial(c: Canvas, p: Paint, cx: Float, cy: Float, rx: Float, ry: Float, color: Int, stop: Float) {
        val g = RadialGradient(0f, 0f, rx, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, stop), Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(1f, ry / rx); postTranslate(cx, cy) })
        p.shader = g
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
    }

    /** linear-gradient(118deg, transparent a, color b, transparent c). */
    private fun band(c: Canvas, p: Paint, w: Float, h: Float, color: Int, a: Float, b: Float, e: Float) {
        val t = color and 0x00FFFFFF
        p.shader = cssLinear(118f, w, h, intArrayOf(t, t, color, t, t), floatArrayOf(0f, a, b, e, 1f))
        c.drawRect(0f, 0f, w, h, p)
    }
}
