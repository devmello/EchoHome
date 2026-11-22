package com.echobedside.feature.home.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.feature.home.ui.GlassTile
import com.echobedside.feature.home.ui.INK_DARK
import com.echobedside.feature.home.ui.Icons
import com.echobedside.feature.home.ui.cssLinear
import com.echobedside.feature.home.ui.drawCentered
import com.echobedside.feature.home.ui.ellipsize
import com.echobedside.feature.home.ui.icon
import com.echobedside.feature.home.ui.lerpColor
import com.echobedside.feature.home.ui.wrap
import kotlin.math.roundToInt

/** Shared text styles (Home.dc.html sizes, px = device px). */
internal object HomeType {
    val tileName = SpecText(16f, 700)
    val tileState = SpecText(13f, 600)
    val lampName = SpecText(17f, 700)
    val lampPct = SpecText(30f, 700, -0.03f)
    val sceneName = SpecText(15f, 700)
    val chip = SpecText(13f, 700)
    val target = SpecText(66f, 700, -0.04f)
    val now = SpecText(14f, 600)
    val room = SpecText(14f, 700)
}

private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

/** Room switcher: one glass capsule; the selected segment is a white fill (not glass on glass). */
@SuppressLint("ViewConstructor")
class RoomSwitcher(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 22f, backdrop) {
    override val springs = false
    var rooms: List<Room> = emptyList(); private set
    var selected: String? = null; private set
    var onPick: ((Room) -> Unit)? = null
    private val segs = ArrayList<Float>() // x of each segment start, plus the end

    fun bind(rooms: List<Room>, selected: String?) {
        this.rooms = rooms; this.selected = selected
        segs.clear()
        var x = 4f
        for (r in rooms) { segs += x; x += HomeType.room.width(r.label) + 32f + 2f }
        segs += x - 2f
        invalidate()
    }

    /** Capsule width for these rooms (padding 4, gap 2, buttons padded 16). */
    fun widthFor(rooms: List<Room>) = 8f + rooms.sumOf { (HomeType.room.width(it.label) + 32f).toDouble() }.toFloat() + 2f * (rooms.size - 1).coerceAtLeast(0)

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        for ((i, r) in rooms.withIndex()) {
            val x0 = segs[i]; val x1 = segs[i + 1] - 2f
            val sel = r.id == selected
            if (sel) {
                fill.color = if (night) withAlpha(Night.COLOR, 0.35f) else withAlpha(Color.WHITE, 0.9f)
                rect.set(x0, 4f, x1, 40f); canvas.drawRoundRect(rect, 18f, 18f, fill)
            }
            val c = if (night) Night.COLOR else if (sel) INK_DARK else Color.WHITE
            HomeType.room.drawCentered(canvas, r.label, (x0 + x1) / 2f, HomeType.room.baselineIn(4f, 36f), c)
        }
    }

    init {
        onTap = { x, _ ->
            val i = (0 until rooms.size).firstOrNull { x >= segs[it] && x < segs[it + 1] }
            if (i != null) onPick?.invoke(rooms[i])
        }
    }
}

/** 300×380 thermostat: arc dial, target, mode chip, − / + (Home.dc.html). */
@SuppressLint("ViewConstructor")
class ThermostatView(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 34f, backdrop) {
    override val springs = false
    var tile: ThermostatTile? = null
        set(v) { field = v; invalidate() }
    var onNudge: ((Int) -> Unit)? = null
    var onMode: (() -> Unit)? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; strokeCap = Paint.Cap.ROUND }
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * 22f / 24f; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val title = SpecText(16f, 700)
    private val rowH = title.normalLineHeight + HomeType.tileState.normalLineHeight
    private val dialTop = 20f + rowH + 14f
    private val btnTop = dialTop + 210f + 4f
    private var heatShader: Shader? = null
    private var pressed = 0 // -1 minus, +1 plus

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val t = tile
        val white = if (night) Night.COLOR else Color.WHITE
        val dim = Night.map(withAlpha(Color.WHITE, 0.75f), night)
        title.draw(canvas, "Thermostat", 20f, title.baselineIn(20f), white)
        val heatColor = if (t?.heating == true) 0xFFFFB340.toInt() else if (t?.cooling == true) 0xFF64D2FF.toInt() else withAlpha(Color.WHITE, 0.75f)
        HomeType.tileState.draw(canvas, t?.line ?: "No thermostat", 20f, HomeType.tileState.baselineIn(20f + title.normalLineHeight), Night.map(heatColor, night))

        // mode chip, right-aligned, vertically centred on the row
        val label = t?.modeLabel ?: "—"
        val cw = HomeType.chip.width(label) + 24f
        val ct = 20f + (rowH - 30f) / 2f
        fill.color = Night.map(withAlpha(Color.WHITE, 0.16f), night)
        rect.set(w - 20f - cw, ct, w - 20f, ct + 30f); canvas.drawRoundRect(rect, 15f, 15f, fill)
        HomeType.chip.draw(canvas, label, w - 20f - cw + 12f, HomeType.chip.baselineIn(ct, 30f), white)

        // dial: 270° track from 135°, fill = fraction (SVG dasharray 415 of 553)
        val cx = w / 2f; val cy = dialTop + 105f
        arc.set(cx - 88f, cy - 88f, cx + 88f, cy + 88f)
        stroke.shader = null
        stroke.color = Night.map(withAlpha(Color.WHITE, 0.16f), night)
        canvas.drawArc(arc, 135f, 270f, false, stroke)
        val frac = t?.fraction ?: 0f
        if (t?.target != null && frac > 0f) {
            if (night) { stroke.color = Night.COLOR } else {
                // The SVG gradient (#FF9F0A → #FF453A, bottom-left to top-right) is rotated with the
                // circle by 135°, so on screen it runs top (orange) to bottom (red).
                if (heatShader == null) heatShader = LinearGradient(0f, cy - 88f, 0f, cy + 88f, 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), Shader.TileMode.CLAMP)
                stroke.shader = heatShader
            }
            canvas.drawArc(arc, 135f, 270f * frac, false, stroke)
            stroke.shader = null
        }

        // centre: target + "Now 66°"
        val total = 66f + 4f + HomeType.now.normalLineHeight
        val top = dialTop + (210f - total) / 2f
        val tgt = t?.target?.let { fmtTemp(it) + "°" } ?: "Off"
        HomeType.target.drawCentered(canvas, tgt, cx, HomeType.target.baselineIn(top, 66f), white)
        t?.current?.let { HomeType.now.drawCentered(canvas, "Now ${fmtTemp(it)}°", cx, HomeType.now.baselineIn(top + 70f), dim) }

        // − / + (56 px circles, gap 28)
        for ((i, d) in listOf(Icons.MINUS, Icons.PLUS).withIndex()) {
            val bx = 80f + i * 84f
            fill.color = Night.map(withAlpha(Color.WHITE, if (pressed == (if (i == 0) -1 else 1)) 0.3f else 0.16f), night)
            canvas.drawCircle(bx + 28f, btnTop + 28f, 28f, fill)
            iconStroke.color = white
            canvas.icon(d, bx + 17f, btnTop + 17f, 22f, iconStroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val hit = when {
            e.y in btnTop..btnTop + 56f && e.x in 80f..136f -> -1
            e.y in btnTop..btnTop + 56f && e.x in 164f..220f -> 1
            else -> 0
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (pressed != 0 && hit == pressed) onNudge?.invoke(pressed)
                else if (pressed == 0 && e.y < 20f + rowH + 6f && e.x > width - 140f) onMode?.invoke()
                pressed = 0; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = 0; invalidate() }
        }
        return true
    }
}

/** 268×182 lamp: the whole tile is a brightness slider (tap or drag; 5 % steps on tap). */
@SuppressLint("ViewConstructor")
class LampView(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 30f, backdrop) {
    override val springs = false
    var tile: LampTile? = null
        set(v) { field = v; if (!dragging) shown = v?.pct ?: 0; invalidate() }
    /** Live while dragging; [onSet] on release. */
    var onPreview: ((Int) -> Unit)? = null
    var onSet: ((Int) -> Unit)? = null
    private var shown = 0
    private var dragging = false
    private var downX = 0f
    private var amber: Shader? = null
    private var spec: Shader? = null
    private val path = Path()
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        val pct = shown
        if (pct > 0) {
            val fw = w * pct / 100f
            path.reset(); rect.set(0f, 0f, w, h); path.addRoundRect(rect, 30f, 30f, Path.Direction.CW)
            canvas.save(); canvas.clipPath(path)
            if (night) fill.shader = null.also { fill.color = withAlpha(Night.COLOR, 0.45f) }
            else fill.shader = LinearGradient(0f, 0f, fw, 0f, 0xEBFFAA50.toInt(), 0xF2FFCE8C.toInt(), Shader.TileMode.CLAMP).also { amber = it }
            canvas.drawRect(0f, 0f, fw, h, fill)
            fill.shader = null
            if (!night) { // the specular span sits above the fill
                if (spec == null) spec = cssLinear(160f, w, h, intArrayOf(withAlpha(Color.WHITE, 0.24f), withAlpha(Color.WHITE, 0.03f), 0x00FFFFFF), floatArrayOf(0f, 0.38f, 0.62f))
                fill.shader = spec; canvas.drawRect(0f, 0f, fw, h, fill); fill.shader = null
            }
            canvas.restore()
        }
        val inkC = if (night) Night.COLOR else if (pct > 55) 0xFF3A1A00.toInt() else Color.WHITE
        box.color = Night.map(withAlpha(Color.WHITE, 0.22f), night)
        rect.set(20f, 18f, 60f, 58f); canvas.drawRoundRect(rect, 13f, 13f, box)
        fill.color = if (night) Night.COLOR else Color.WHITE
        canvas.icon(Icons.LAMP_BULB, 29f, 27f, 22f, fill)
        val block = HomeType.lampName.normalLineHeight + 2f + HomeType.lampPct.normalLineHeight
        val top = h - 18f - block
        HomeType.lampName.draw(canvas, HomeType.lampName.ellipsize(tile?.name ?: "Lamp", w - 40f), 20f, HomeType.lampName.baselineIn(top), inkC)
        HomeType.lampPct.draw(canvas, "$pct%", 20f, HomeType.lampPct.baselineIn(top + HomeType.lampName.normalLineHeight + 2f), inkC)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pct = ((e.x / width).coerceIn(0f, 1f) * 100).roundToInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; dragging = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if (dragging || kotlin.math.abs(e.x - downX) > 12f) {
                dragging = true; shown = pct; onPreview?.invoke(pct); invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val v = if (dragging) pct else ((e.x / width).coerceIn(0f, 1f) * 20).roundToInt() * 5
                dragging = false; shown = v; invalidate(); onSet?.invoke(v)
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; shown = tile?.pct ?: 0; invalidate() }
        }
        return true
    }
}

/** 126×182 toggle or 148×182 lock / garage tile: icon top-left, name + state at the bottom. */
@SuppressLint("ViewConstructor")
class SmallTile(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 30f, backdrop) {
    var name = ""; var state = ""; var iconD = Icons.BULB; var accent = 0xFFFF9F0A.toInt()
    fun bind(name: String, state: String, icon: String, accent: Int, on: Boolean, animate: Boolean) {
        this.name = name; this.state = state; this.iconD = icon; this.accent = accent
        setOn(on, animate); invalidate()
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        fill.color = if (night) withAlpha(Night.COLOR, 0.3f) else lerpColor(withAlpha(Color.WHITE, 0.2f), accent, onT)
        rect.set(18f, 18f, 58f, 58f); canvas.drawRoundRect(rect, 13f, 13f, fill)
        fill.color = if (night) Night.COLOR else Color.WHITE
        canvas.icon(iconD, 27f, 27f, 22f, fill)
        val block = HomeType.tileName.normalLineHeight + 2f + HomeType.tileState.normalLineHeight
        val top = h - 18f - block
        HomeType.tileName.draw(canvas, HomeType.tileName.ellipsize(name, w - 36f), 18f, HomeType.tileName.baselineIn(top), ink())
        HomeType.tileState.draw(canvas, HomeType.tileState.ellipsize(state, w - 36f), 18f,
            HomeType.tileState.baselineIn(top + HomeType.tileName.normalLineHeight + 2f), ink(0.75f))
    }
}

/** 148×83 scene button: coloured dot with icon, name wrapping to two lines. */
@SuppressLint("ViewConstructor")
class SceneView(context: Context, backdrop: () -> Backdrop?) : GlassTile(context, 24f, backdrop) {
    private var name = ""; private var iconD = Icons.SPARKLE; private var dot = 0
    private var lines: List<String> = emptyList()
    fun bind(t: SceneTile, animate: Boolean) {
        name = t.name; iconD = Icons.of(t.icon); dot = t.dot
        lines = HomeType.sceneName.wrap(name, 148f - 14f - 36f - 10f - 14f, 2)
        setOn(t.active, animate); invalidate()
    }

    override fun drawContent(canvas: Canvas, w: Float, h: Float) {
        fill.color = if (night) withAlpha(Night.COLOR, 0.3f) else dot
        canvas.drawCircle(32f, h / 2f, 18f, fill)
        fill.color = if (night) Night.COLOR else Color.WHITE
        canvas.icon(iconD, 32f - 9.5f, h / 2f - 9.5f, 19f, fill)
        val lh = 15f * 1.15f
        val top = (h - lh * lines.size) / 2f
        for ((i, l) in lines.withIndex()) HomeType.sceneName.draw(canvas, l, 60f, HomeType.sceneName.baselineIn(top + i * lh, lh), ink())
    }
}
