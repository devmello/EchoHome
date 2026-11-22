package app.vesper.feature.home.stocks

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
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.home.HomeSettings
import app.vesper.feature.home.glance.clock12
import app.vesper.feature.home.ui.drawCentered
import app.vesper.feature.home.ui.drawRight
import app.vesper.feature.home.ui.ellipsize
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Stocks.dc.html: featured chart with area fill + a 5-symbol watchlist. Content only, no glass. */
@SuppressLint("ViewConstructor")
class StocksView(context: Context, private val services: VesperServices) : View(context), VesperScreen {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = StocksController(if (services.config.demoData) null else YahooChartSource(), services.settings, scope)
    private var list: Watchlist? = null
    private var error: String? = null
    private var night = false

    private val label = SpecText(15f, 700, 0.05f)
    private val heroSym = SpecText(40f, 800, -0.02f)
    private val heroName = SpecText(18f, 600)
    private val heroPrice = SpecText(64f, 600, -0.035f)
    private val heroChange = SpecText(22f, 700)
    private val axis = SpecText(13f, 600)
    private val rowSym = SpecText(20f, 800)
    private val rowName = SpecText(12f, 600)
    private val rowPrice = SpecText(18f, 700)
    private val pill = SpecText(13f, 800)
    private val foot = SpecText(12f, 600)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f) }
    private val rect = RectF()
    private val path = Path()

    private val chartTop: Float
    private val rowsTop = 36f + label.normalLineHeight + 6f

    init {
        val row1 = maxOf(heroSym.ascent, heroName.ascent) + maxOf(heroSym.descent, heroName.descent)
        val row2 = maxOf(heroPrice.ascent, heroChange.ascent) + maxOf(heroPrice.descent, heroChange.descent)
        chartTop = 36f + label.normalLineHeight + 10f + row1 + 2f + row2 + 14f
        observeWhileAttached(controller.state) { list = it; invalidate() }
        observeWhileAttached(controller.error) { error = it; invalidate() }
        observeWhileAttached(services.system.state) { st -> if (st.nightActive != night) { night = st.nightActive; invalidate() } }
    }

    override fun onScreenShown(args: Bundle?) {
        args?.getString("home.set")?.let { HomeSettings.applyAssignments(services.settings, it) }
        controller.start()
    }
    override fun onScreenHidden() = controller.stop()
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); controller.stop() }

    private fun c(color: Int) = Night.map(color, night)
    private val up = 0xFF30D158.toInt()
    private val down = 0xFFFF453A.toInt()

    private fun sign(v: Double) = (if (v >= 0) "+" else "−") + "%.2f".format(abs(v))
    private fun pct(v: Double) = sign(v) + "%"

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val l = list ?: run {
            label.draw(canvas, "MARKETS", 44f, label.baselineIn(36f), c(withAlpha(Color.WHITE, 0.55f)))
            heroName.draw(canvas, error ?: "Loading quotes…", 44f, 120f, c(withAlpha(Color.WHITE, 0.6f)))
            return
        }
        val now = System.currentTimeMillis()
        val open = if (l.demo) "MARKETS · DELAYED 15 MIN" else "MARKETS OPEN · AS OF " + clock12(l.asOfMillis).uppercase()
        label.draw(canvas, MarketHours.headline(now, open), 44f, label.baselineIn(36f), c(withAlpha(Color.WHITE, 0.55f)))
        val sel = controller.featured.coerceIn(0, (l.quotes.size - 1).coerceAtLeast(0))
        l.quotes.getOrNull(sel)?.let { drawHero(canvas, it) }

        // Watchlist
        label.draw(canvas, "WATCHLIST", 520f, label.baselineIn(36f), c(withAlpha(Color.WHITE, 0.55f)))
        l.quotes.take(5).forEachIndexed { i, q ->
            val top = rowsTop + i * 74f
            if (i == sel) { p.color = c(withAlpha(Color.WHITE, 0.08f)); rect.set(520f, top, 920f, top + 74f); canvas.drawRoundRect(rect, 14f, 14f, p) }
            p.color = c(withAlpha(Color.WHITE, 0.14f)); canvas.drawRect(534f, top + 73.5f, 906f, top + 74f, p)
            val col = if (q.percent >= 0) up else down
            val symTop = top + (74f - rowSym.normalLineHeight - rowName.normalLineHeight) / 2f
            rowSym.draw(canvas, q.symbol, 530f, rowSym.baselineIn(symTop), c(Color.WHITE))
            rowName.draw(canvas, rowName.ellipsize(q.name, 96f), 530f, rowName.baselineIn(symTop + rowSym.normalLineHeight), c(withAlpha(Color.WHITE, 0.55f)))
            series(q.spark, q.spark.size, 640f, top + 19f, 110f, 36f, 3f, null)
            line.strokeWidth = 2f; line.color = c(col); line.shader = null; canvas.drawPath(path, line)
            val pillH = pill.normalLineHeight + 4f
            val blockTop = top + (74f - rowPrice.normalLineHeight - 3f - pillH) / 2f
            rowPrice.drawRight(canvas, "%.2f".format(q.price), 910f, rowPrice.baselineIn(blockTop), c(Color.WHITE))
            val text = pct(q.percent)
            val pw = maxOf(72f, pill.width(text) + 16f)
            val pt = blockTop + rowPrice.normalLineHeight + 3f
            p.color = c(col); rect.set(910f - pw, pt, 910f, pt + pillH); canvas.drawRoundRect(rect, 7f, 7f, p)
            pill.drawCentered(canvas, text, 910f - pw / 2f, pill.baselineIn(pt + 2f), if (night) Color.BLACK else Color.BLACK)
        }
        val footer = error ?: if (l.demo) "Demo data · sample prices" else "${l.source} · quotes may be delayed"
        foot.draw(canvas, footer, 520f, foot.baselineIn(rowsTop + 5 * 74f + 8f), c(withAlpha(Color.WHITE, 0.4f)))
    }

    private fun drawHero(canvas: Canvas, q: Quote) {
        val col = if (q.percent >= 0) up else down
        var y = 36f + label.normalLineHeight + 10f
        val a1 = maxOf(heroSym.ascent, heroName.ascent)
        val x = heroSym.draw(canvas, q.symbol, 44f, y + a1, c(Color.WHITE)) + 12f
        heroName.draw(canvas, heroName.ellipsize(q.name, 440f - (x - 44f)), x, y + a1, c(withAlpha(Color.WHITE, 0.6f)))
        y += a1 + maxOf(heroSym.descent, heroName.descent) + 2f
        val a2 = maxOf(heroPrice.ascent, heroChange.ascent)
        val px = heroPrice.draw(canvas, "$" + "%.2f".format(q.price), 44f, y + a2, c(Color.WHITE)) + 14f
        heroChange.draw(canvas, sign(q.change) + "  (" + pct(q.percent) + ")", px, y + a2, c(col))

        // Chart: 440×190, pad 10; dashed line at the previous close.
        val top = chartTop
        val pts = q.series
        val base = q.baseline
        series(pts, q.sessionBars, 44f, top, 440f, 190f, 10f, base)
        if (pts.size >= 2) {
            val lastX = 44f + (pts.size - 1) * 440f / (q.sessionBars - 1).coerceAtLeast(1)
            val area = Path(path).apply { lineTo(lastX, top + 190f); lineTo(44f, top + 190f); close() }
            p.color = Color.BLACK; p.shader = LinearGradient(0f, top, 0f, top + 190f, withAlpha(c(col), 0.35f), withAlpha(c(col), 0f), Shader.TileMode.CLAMP)
            canvas.drawPath(area, p); p.shader = null
        }
        if (base != null) {
            val by = yFor(base)
            dash.color = c(withAlpha(Color.WHITE, 0.25f)); canvas.drawLine(44f, by, 484f, by, dash)
        }
        line.strokeWidth = 3f; line.color = c(col); canvas.drawPath(path, line)
        val ay = axis.baselineIn(top + 190f + 6f)
        val ac = c(withAlpha(Color.WHITE, 0.45f))
        axis.draw(canvas, "9:30", 44f, ay, ac)
        axis.drawCentered(canvas, "12:00", 264f, ay, ac)
        axis.drawRight(canvas, if (q.sessionBars < 78) "1:00" else "4:00", 484f, ay, ac)
    }

    private var mn = 0.0; private var span = 1.0; private var yTop = 0f; private var yH = 0f; private var yPad = 0f

    /** Builds [path] for [v] the way the design's path() does: x spread over [slots], y min..max with [pad]. */
    private fun series(v: List<Double>, slots: Int, x0: Float, top: Float, w: Float, h: Float, pad: Float, include: Double?) {
        path.reset()
        if (v.isEmpty()) return
        mn = minOf(v.min(), include ?: v.min()); val mx = maxOf(v.max(), include ?: v.max())
        span = (mx - mn).takeIf { it > 0 } ?: 1.0
        yTop = top; yH = h; yPad = pad
        val dx = w / (slots - 1).coerceAtLeast(1)
        v.forEachIndexed { i, value ->
            val px = x0 + i * dx; val py = yFor(value)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
    }

    private fun yFor(value: Double): Float = (yTop + yPad + (yH - 2 * yPad) * (1 - (value - mn) / span)).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return e.x >= 520f
        if (e.actionMasked == MotionEvent.ACTION_UP && e.x >= 520f) {
            val i = ((e.y - rowsTop) / 74f).toInt()
            if (e.y >= rowsTop && i in 0 until (list?.quotes?.size ?: 0)) { controller.featured = i; invalidate() }
        }
        return true
    }
}
