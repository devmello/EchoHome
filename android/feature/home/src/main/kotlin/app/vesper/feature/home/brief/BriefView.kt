package app.vesper.feature.home.brief

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
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
import app.vesper.core.model.WeatherCondition
import app.vesper.feature.home.HomeSettings
import app.vesper.feature.home.alerts.AlertSettings
import app.vesper.feature.home.alerts.LeaveRule
import app.vesper.feature.home.commute.CommuteSettings
import app.vesper.feature.home.glance.clock12
import app.vesper.feature.home.ui.drawRight
import app.vesper.feature.home.ui.ellipsize
import app.vesper.feature.home.ui.icon
import app.vesper.feature.home.ui.wrap
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The morning brief (id "brief", not in the pager): a greeting, the weather with rain / air / UV /
 * pollen, the first event with its leave time, and what came in overnight. Content cards on black
 * (plain fills, no glass: Liquid Glass rule 1), like Alarm.dc.html's Good morning. A tap closes it,
 * and so does 60 s. Draws only when its data changes and once a minute (the clock).
 *
 * Debug: `--es screen brief --es brief demo` shows the designs' sample data (`demo-day`: in the day palette even at night).
 */
@SuppressLint("ViewConstructor")
class BriefView(
    context: Context,
    private val services: VesperServices,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : View(context), VesperScreen {
    private var scope: CoroutineScope? = null
    private var data: BriefData? = null
    private var demo = false
    private var shown = false
    private var night = false
    /** Debug (`--es brief demo-day`): draw the day palette even when the room is dark. */
    private var forceDay = false
    /** The person the brief is for (`brief.profile`, after their alarm); null = the active profile. */
    private var forProfile: String? = null
    /** Travel to the first event, looked up once per event (null = unknown / none yet). */
    private var travel: Triple<String, Long, Int?>? = null
    private var travelJob: Job? = null

    private val date = SpecText(20f, 600, -0.01f)
    private val title = SpecText(60f, 700, -0.035f)
    private val clock = SpecText(64f, 600, -0.04f)
    private val label = SpecText(15f, 600)
    private val body = SpecText(15f, 500)
    private val temp = SpecText(48f, 600, -0.03f)
    private val evTitle = SpecText(24f, 700, -0.02f)
    private val row = SpecText(16f, 700)
    private val rowSub = SpecText(14f, 500)
    private val chipText = SpecText(14f, 600)
    private val pill = SpecText(14f, 700)
    private val hint = SpecText(14f, 600)
    private val empty = SpecText(18f, 600)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val sky = RadialGradient(480f, 0f, 520f, intArrayOf(withAlpha(0xFF283C78.toInt(), 0.5f), 0x00283C78), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)

    private val dismiss = Runnable { close() }
    private val tick = object : Runnable {
        override fun run() { if (shown) { invalidate(); postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 50) } }
    }

    override fun onScreenShown(args: Bundle?) {
        shown = true
        args?.getString("home.set")?.let { HomeSettings.applyAssignments(services.settings, it) }
        // The pager may call this again without args once it settles: keep the demo until hidden.
        when (args?.getString("brief")) { "demo" -> demo = true; "demo-day" -> { demo = true; forceDay = true } }
        // After a person's alarm: their greeting and calendar, whoever the camera sees (kept until hidden).
        args?.getString(BriefWatcher.ARG_PROFILE)?.let { forProfile = it }
        night = services.system.state.value.nightActive && !forceDay
        travel = null
        scope?.cancel()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        if (demo) rebuild()
        else sc.launch {
            // Anything the brief reads changing while it is open updates it.
            combine(services.weather.weather, services.calendar.events, services.notifications.recent, services.profiles.active) { _, _, _, _ -> }
                .collect { rebuild() }
        }
        sc.launch { services.system.state.collect { st -> if ((st.nightActive && !forceDay) != night) { night = st.nightActive && !forceDay; invalidate() } } }
        removeCallbacks(dismiss); postDelayed(dismiss, AUTO_CLOSE_MS)
        removeCallbacks(tick); postDelayed(tick, 60_000L - System.currentTimeMillis() % 60_000L + 50)
    }

    override fun onScreenHidden() {
        shown = false
        demo = false; forceDay = false; forProfile = null
        removeCallbacks(dismiss); removeCallbacks(tick)
        scope?.cancel(); scope = null; travelJob = null
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); onScreenHidden() }

    private fun close() { onScreenHidden(); services.navigator.home() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) { performClick(); close() }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun rebuild() {
        val z = zone(); val now = System.currentTimeMillis()
        if (demo) { data = BriefBuilder.demo(now, z); invalidate(); return }
        val profile = forProfile?.let { id -> services.profiles.profiles.value.firstOrNull { it.id == id } } ?: services.profiles.active.value
        val events = services.calendar.events.value
        val first = BriefBuilder.firstEvent(events, now, profile)
        val buffer = AlertSettings.int(services.settings, CommuteSettings.BUFFER_MIN, LeaveRule.DEFAULT_BUFFER_MIN)
        val known = travel?.takeIf { first != null && it.first == first.id }?.third
        data = BriefBuilder.build(now, z, profile, services.weather.weather.value, events, known, buffer, services.notifications.recent.value)
        if (first != null && !first.location.isNullOrBlank() && travel?.first != first.id) lookUpTravel(first.id, first.location.orEmpty(), first.startMillis)
        if (shown) invalidate()
    }

    private fun lookUpTravel(id: String, place: String, start: Long) {
        travel = Triple(id, start, null) // asked: don't ask again for this event
        travelJob?.cancel()
        travelJob = scope?.launch {
            val minutes = services.travel.minutesTo(place, start) ?: return@launch
            travel = Triple(id, start, minutes)
            rebuild()
        }
    }

    private fun c(color: Int) = Night.map(color, night)
    private fun dim(a: Float = 0.7f) = c(withAlpha(Color.WHITE, a))

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val d = data ?: return
        if (!night) { p.shader = sky; canvas.drawRect(0f, 0f, 960f, 480f, p); p.shader = null }
        // Header
        date.draw(canvas, d.dateLine, 48f, date.baselineIn(44f), dim())
        title.draw(canvas, title.ellipsize(d.greeting, 640f), 48f, title.baselineIn(44f + date.normalLineHeight + 4f, 60f), c(Color.WHITE))
        val t = clock12(System.currentTimeMillis(), zone()).substringBefore(' ')
        clock.drawRight(canvas, t, 912f, clock.baselineIn(50f, 64f), dim(0.92f))
        // Cards
        val w = (960f - 96f - 32f) / 3f
        val top = 150f; val h = 250f
        for (i in 0 until 3) {
            val x = 48f + i * (w + 16f)
            p.color = c(withAlpha(Color.WHITE, 0.08f)); rect.set(x, top, x + w, top + h); canvas.drawRoundRect(rect, 28f, 28f, p)
        }
        weatherCard(canvas, d.weather, 48f, top, w, h)
        eventCard(canvas, d.event, 48f + (w + 16f), top, w, h)
        overnightCard(canvas, d.overnight, 48f + 2 * (w + 16f), top, w, h)
        val note = if (d.demo) "Demo data · tap anywhere to close" else "Tap anywhere to close"
        hint.draw(canvas, note, 480f - hint.width(note) / 2f, 448f, dim(0.4f))
    }

    private fun weatherCard(canvas: Canvas, wx: BriefWeather?, x: Float, top: Float, w: Float, h: Float) {
        val ix = x + 22f; val iw = w - 44f
        if (wx == null) { empty.draw(canvas, "Weather unavailable", ix, empty.baselineIn(top + 20f), dim(0.5f)); return }
        label.draw(canvas, label.ellipsize(wx.place.ifEmpty { "Weather" }, iw - 40f), ix, label.baselineIn(top + 20f), dim())
        weatherIcon(canvas, wx.condition, x + w - 22f - 30f, top + 17f)
        temp.draw(canvas, wx.temp, ix, temp.baselineIn(top + 50f, 48f), c(Color.WHITE))
        body.draw(canvas, body.ellipsize(wx.summary, iw), ix, body.baselineIn(top + 104f), dim())
        // Chips from the bottom up: rain, air, UV, pollen (at most 4 lines of 22 px).
        val chips = wx.chips.take(4)
        chips.forEachIndexed { i, ch ->
            val rowTop = top + h - 18f - (chips.size - i) * 22f
            p.color = c(ch.color); canvas.drawCircle(ix + 4f, rowTop + 11f, 4f, p)
            chipText.draw(canvas, chipText.ellipsize(ch.text, iw - 16f), ix + 16f, chipText.baselineIn(rowTop, 22f), dim(0.9f))
        }
    }

    private fun weatherIcon(canvas: Canvas, cond: WeatherCondition, x: Float, y: Float) {
        s.strokeWidth = 2f
        when (cond) {
            WeatherCondition.CLEAR -> { s.color = c(0xFFFFD60A.toInt()); canvas.icon(SUN, x, y, 30f, s) }
            WeatherCondition.PARTLY_CLOUDY -> { s.color = c(0xFFFFD60A.toInt()); canvas.icon(SUN, x, y, 30f, s) }
            else -> { s.color = c(if (cond in WET) 0xFF64D2FF.toInt() else 0xFFB0B8C4.toInt()); canvas.icon(CLOUD, x, y, 30f, s) }
        }
    }

    private fun eventCard(canvas: Canvas, ev: BriefEvent?, x: Float, top: Float, w: Float, h: Float) {
        val ix = x + 22f; val iw = w - 44f
        label.draw(canvas, "Up next", ix, label.baselineIn(top + 20f), c(0xFFFF9F0A.toInt()))
        if (ev == null) {
            evTitle.draw(canvas, "Nothing on today", ix, evTitle.baselineIn(top + 52f, 28f), dim(0.9f))
            body.draw(canvas, "Your calendar is clear", ix, body.baselineIn(top + 90f), dim())
            return
        }
        val lines = evTitle.wrap(ev.title, iw, 2)
        lines.forEachIndexed { i, l -> evTitle.draw(canvas, l, ix, evTitle.baselineIn(top + 52f + i * 28f, 28f), c(Color.WHITE)) }
        body.draw(canvas, body.ellipsize(ev.whenText, iw), ix, body.baselineIn(top + 52f + lines.size * 28f + 6f), dim())
        if (ev.leave != null) {
            val tw = pill.width(ev.leave.let { pill.ellipsize(it, iw - 24f) })
            val py = top + h - 20f - 32f
            p.color = c(withAlpha(0xFFFF9F0A.toInt(), 0.16f)); rect.set(ix, py, ix + tw + 24f, py + 32f); canvas.drawRoundRect(rect, 16f, 16f, p)
            pill.draw(canvas, pill.ellipsize(ev.leave, iw - 24f), ix + 12f, pill.baselineIn(py, 32f), c(0xFFFFB340.toInt()))
        }
    }

    private fun overnightCard(canvas: Canvas, on: Overnight, x: Float, top: Float, w: Float, h: Float) {
        val ix = x + 22f; val iw = w - 44f
        label.draw(canvas, "Overnight", ix, label.baselineIn(top + 20f), dim())
        if (on.total > 0) label.drawRight(canvas, if (on.total == 1) "1 new" else "${on.total} new", x + w - 22f, label.baselineIn(top + 20f), dim())
        if (on.groups.isEmpty()) { empty.draw(canvas, "All quiet", ix, empty.baselineIn(top + 56f), dim(0.9f)); rowSub.draw(canvas, "No new notifications", ix, rowSub.baselineIn(top + 84f), dim(0.5f)); return }
        on.groups.forEachIndexed { i, g ->
            val ry = top + 50f + i * 62f
            p.color = c(g.color); rect.set(ix, ry + 3f, ix + 10f, ry + 47f); canvas.drawRoundRect(rect, 5f, 5f, p)
            val count = g.count.toString()
            row.drawRight(canvas, count, x + w - 22f, row.baselineIn(ry), dim(0.9f))
            row.draw(canvas, row.ellipsize(g.app, iw - 24f - row.width(count) - 12f), ix + 20f, row.baselineIn(ry), c(Color.WHITE))
            rowSub.draw(canvas, rowSub.ellipsize(g.titles.joinToString(" · "), iw - 20f), ix + 20f, rowSub.baselineIn(ry + 24f), dim())
        }
    }

    companion object {
        const val AUTO_CLOSE_MS = 60_000L
        private val WET = setOf(WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.SLEET, WeatherCondition.SNOW, WeatherCondition.LIGHTNING, WeatherCondition.HAIL)
        /** Stroked 24-unit icons (Alarm.dc.html's sun; a cloud). */
        private const val SUN = "M12 7.8a4.2 4.2 0 1 0 0 8.4 4.2 4.2 0 0 0 0-8.4zM12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"
        private const val CLOUD = "M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 10.5 3.75 3.75 0 0 1 17.25 18z"
    }
}
