package com.echobedside.feature.home.commute

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.home.HomeSettings
import com.echobedside.feature.home.glance.clock12
import com.echobedside.feature.home.ui.Icons
import com.echobedside.feature.home.ui.SvgPath
import com.echobedside.feature.home.ui.ellipsize
import com.echobedside.feature.home.ui.icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Commute.dc.html: "Leave in N min", drive time + arrival, chips, a drawn route map (no tiles) and
 * alternatives. Content, not glass (Liquid Glass rule 1). Redraws once a minute while shown.
 */
@SuppressLint("ViewConstructor")
class CommuteView(context: Context, private val services: EchoServices) : View(context), EchoScreen {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = CommuteController(services.settings, services.calendar, services.weather, scope, services.config.demoData, services.location)
    private var state: CommuteState? = null
    private var night = false

    private val header = SpecText(16f, 700, 0.04f)
    private val big = SpecText(64f, 800, -0.035f)
    private val sub = SpecText(22f, 600)
    private val chip = SpecText(14f, 700)
    private val altLabel = SpecText(13f, 700)
    private val altValue = SpecText(24f, 700, -0.02f)
    private val emptyTitle = SpecText(40f, 800, -0.02f)
    private val emptyBody = SpecText(20f, 600)
    private val note = SpecText(12f, 600)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()

    // The design's stylised map, in map coordinates (map origin at x = 400).
    private val coast = SvgPath.path("M0 420 C 120 400, 200 440, 300 430 S 470 400, 560 410 L 560 480 L 0 480 Z")
    private val streets = SvgPath.path("M40 0 L 90 480M140 0 L 170 480M240 0 L 250 480M330 0 L 320 480M430 0 L 400 480M520 0 L 480 480" +
        "M0 60 L 560 40M0 140 L 560 125M0 220 L 560 210M0 300 L 560 295M0 370 L 560 372")
    private val designRoute = SvgPath.path("M60 400 C 140 330, 170 250, 250 210 S 400 120, 470 70")
    private val designAlt = SvgPath.path("M110 380 C 180 340, 230 300, 300 290 S 420 220, 470 70")
    private val designSlow = SvgPath.path("M196 262 C 215 240, 228 228, 250 210")
    private val routeShader = LinearGradient(60f, 400f, 470f, 70f, 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), Shader.TileMode.CLAMP)
    private val fade = LinearGradient(0f, 0f, 560f * 0.35f, 0f, 0xFF05070C.toInt(), 0x0005070C, Shader.TileMode.CLAMP)
    private val dash = DashPathEffect(floatArrayOf(2f, 10f), 0f)

    init {
        observeWhileAttached(controller.state) { state = it; invalidate() }
        observeWhileAttached(services.system.state) { st -> if (st.nightActive != night) { night = st.nightActive; invalidate() } }
    }

    override fun onScreenShown(args: Bundle?) {
        args?.getString("home.set")?.let { HomeSettings.applyAssignments(services.settings, it) }
        controller.start()
    }

    override fun onScreenHidden() = controller.stop()
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); controller.stop() }

    private fun c(color: Int) = Night.map(color, night)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (night) Color.BLACK else 0xFF05070C.toInt())
        when (val st = state) {
            is CommuteState.Ready -> drawPlan(canvas, st.ui)
            is CommuteState.Empty -> {
                drawMap(canvas, null)
                emptyTitle.draw(canvas, emptyTitle.ellipsize(st.title, 420f), 48f, 150f, c(Color.WHITE))
                emptyBody.draw(canvas, emptyBody.ellipsize(st.detail, 840f), 48f, 190f, c(withAlpha(Color.WHITE, 0.7f)))
            }
            null -> {}
        }
    }

    private fun drawMap(canvas: Canvas, ui: CommuteUi?) {
        canvas.save()
        canvas.translate(400f, 0f)
        p.shader = null; p.color = c(0xFF0B1A2C.toInt()); if (!night) canvas.drawPath(coast, p)
        s.pathEffect = null; s.shader = null; s.strokeWidth = 2f; s.color = c(withAlpha(Color.WHITE, 0.08f)); canvas.drawPath(streets, s)
        if (ui != null) {
            // Alternative first: fit() records the end markers of the last path it fits (the main route).
            val alt = if (ui.route == null) (if (ui.alternatives.isNotEmpty()) designAlt else null) else ui.altRoute?.let(::fit)
            val route = ui.route?.let(::fit) ?: designRoute
            s.strokeWidth = 10f; s.color = c(withAlpha(Color.WHITE, 0.18f)); canvas.drawPath(route, s)
            if (alt != null) { s.strokeWidth = 6f; s.color = c(withAlpha(Color.WHITE, 0.16f)); s.pathEffect = dash; canvas.drawPath(alt, s); s.pathEffect = null }
            s.strokeWidth = 7f
            if (night) s.color = Night.COLOR else s.shader = routeShader
            canvas.drawPath(route, s); s.shader = null
            if (ui.trafficChip != null && ui.route == null) { s.color = c(0xFFFF9F0A.toInt()); canvas.drawPath(designSlow, s) }
            val (sx, sy) = ends.first; val (ex, ey) = ends.second
            val a = if (ui.route == null) floatArrayOf(60f, 400f, 470f, 70f) else floatArrayOf(sx, sy, ex, ey)
            p.color = c(Color.WHITE); canvas.drawCircle(a[0], a[1], 11f, p)
            p.color = c(0xFF0A84FF.toInt()); canvas.drawCircle(a[0], a[1], 6f, p)
            p.color = c(0xFFFF453A.toInt()); canvas.drawCircle(a[2], a[3], 13f, p)
            p.color = if (night) Color.BLACK else Color.WHITE; canvas.drawCircle(a[2], a[3], 5f, p)
        }
        if (!night) { p.shader = fade; canvas.drawRect(0f, 0f, 560f, 480f, p); p.shader = null }
        canvas.restore()
    }

    private var ends = (0f to 0f) to (0f to 0f)

    /** Unit-square route → the design's route box (x 60–470, y 70–400 in map space), shape kept. */
    private fun fit(pts: List<Pair<Float, Float>>): Path {
        val scale = 330f; val ox = 60f + (410f - scale) / 2f; val oy = 70f
        val path = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(ox + x * scale, oy + y * scale) else path.lineTo(ox + x * scale, oy + y * scale) }
        if (pts.isNotEmpty()) ends = (ox + pts.first().first * scale to oy + pts.first().second * scale) to (ox + pts.last().first * scale to oy + pts.last().second * scale)
        return path
    }

    private fun drawPlan(canvas: Canvas, u: CommuteUi) {
        drawMap(canvas, u)
        val dim = c(withAlpha(Color.WHITE, 0.6f))
        // "TO WORK" row: car icon + label, line box 19.4 px at y 40
        val rowH = header.normalLineHeight
        s.strokeWidth = 2.4f * 16f / 24f; s.color = dim; s.pathEffect = null
        canvas.icon(Icons.CAR, 48f, 40f + (rowH - 16f) / 2f, 16f, s)
        header.draw(canvas, header.ellipsize(u.header, 420f - 24f), 72f, header.baselineIn(40f), dim)
        // "Leave in / 18 min": 64/800, line-height 1.02
        val (l1, l2) = u.headline()
        val lh = 64f * 1.02f
        val top = 40f + rowH + 10f
        big.draw(canvas, l1, 48f, big.baselineIn(top, lh), c(Color.WHITE))
        big.draw(canvas, big.ellipsize(l2, 700f), 48f, big.baselineIn(top + lh, lh), c(Color.WHITE))
        val subTop = top + 2 * lh + 12f
        val via = u.via?.let { " via $it" } ?: ""
        sub.draw(canvas, sub.ellipsize("${u.driveMin} min$via · arrive ${clock12(u.arriveMillis)}", 420f), 48f, sub.baselineIn(subTop), c(withAlpha(Color.WHITE, 0.8f)))
        // chips
        var x = 48f
        val chipTop = subTop + sub.normalLineHeight + 18f
        u.trafficChip?.let { x = chip(canvas, x, chipTop, it, 0xFFFFB340.toInt(), withAlpha(0xFFFF9F0A.toInt(), 0.16f), 0xFFFF9F0A.toInt()) + 8f }
        u.rainChip?.let { chip(canvas, x, chipTop, it, 0xFF9FDCFF.toInt(), withAlpha(0xFF64D2FF.toInt(), 0.14f), null) }
        // alternatives (content cards, plain fill)
        u.alternatives.forEachIndexed { i, a ->
            val ax = 48f + i * 208f; val ay = 480f - 32f - 86f
            p.color = c(withAlpha(Color.WHITE, 0.08f)); rect.set(ax, ay, ax + 196f, ay + 86f); canvas.drawRoundRect(rect, 22f, 22f, p)
            altLabel.draw(canvas, altLabel.ellipsize(a.label, 164f), ax + 16f, altLabel.baselineIn(ay + 14f), dim)
            altValue.draw(canvas, "${a.minutes} min", ax + 16f, altValue.baselineIn(ay + 14f + altLabel.normalLineHeight + 4f), c(Color.WHITE))
        }
        val credit = if (u.demo) "Demo data · set a home location for real routes" else if (u.route != null) "Routes © OpenStreetMap contributors · no live traffic" else null
        credit?.let { note.draw(canvas, it, 532f, 470f, c(withAlpha(Color.WHITE, 0.4f))) }
    }

    /** A 32 px pill; returns its right edge. */
    private fun chip(canvas: Canvas, x: Float, top: Float, text: String, ink: Int, bg: Int, dot: Int?): Float {
        val tw = chip.width(text)
        val w = 24f + tw + (if (dot != null) 14f else 0f)
        p.color = c(bg); rect.set(x, top, x + w, top + 32f); canvas.drawRoundRect(rect, 16f, 16f, p)
        var tx = x + 12f
        if (dot != null) { p.color = c(dot); canvas.drawCircle(tx + 4f, top + 16f, 4f, p); tx += 14f }
        chip.draw(canvas, text, tx, chip.baselineIn(top, 32f), c(ink))
        return x + w
    }
}
