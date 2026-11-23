package app.vesper.feature.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.CalendarEvent
import app.vesper.core.model.DeviceName
import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.Profile
import app.vesper.core.model.VesperServices
import app.vesper.core.model.VesperTimer
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.observeWhileAttached
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One widget in a StandBy stack ([WidgetStack]), drawn at the tile's size ([W]×[H], device px).
 * Like the faces it recomputes its strings in [update] (on data, the minute tick, Night or a
 * person change) and redraws only when they changed; [onDraw] never allocates.
 * Data flows are collected only while the view is attached (the stack attaches the current
 * widget and its two neighbours).
 */
abstract class StackWidget(context: Context, protected val services: VesperServices) : View(context) {
    abstract val kind: WidgetKind

    var night = false
        set(v) { if (field != v) { field = v; onNightChanged(); refresh(force = true) } }

    /** The recognised person (null = nobody / shared). */
    var person: Profile? = null
        set(v) { if (field != v) { field = v; refresh(force = true) } }

    /** On screen (or sliding in) on a shown page: only then may a widget tick. */
    var active = false
        set(v) { if (field != v) { field = v; onActiveChanged() } }

    /**
     * Off screen in its stack: data / minute refreshes are noted, not drawn (nothing may draw
     * frames at rest for a widget nobody sees); the widget catches up when it slides in.
     */
    var parked = false
        set(v) {
            if (field == v) return
            field = v
            if (!v && stale) { val f = staleForce; stale = false; staleForce = false; recompute(f) }
        }
    private var stale = false
    private var staleForce = false

    protected val cal: Calendar = Calendar.getInstance()
    protected val is24: Boolean get() = DateFormat.is24HourFormat(context)

    private val headerText = SpecText(15f, 800, 0.06f)
    private val badgeText = SpecText(15f, 700)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }

    override fun hasOverlappingRendering() = false

    // also while parked: the first record happens here, on the idle moment the stack attaches it
    override fun onAttachedToWindow() { super.onAttachedToWindow(); recompute(force = true) }

    /** Re-reads the clock and data; invalidates when something visible changed (or [force]). Deferred while [parked]. */
    fun refresh(force: Boolean = false) {
        if (parked) { stale = true; staleForce = staleForce || force; return }
        recompute(force)
    }

    private fun recompute(force: Boolean) {
        cal.timeZone = TimeZone.getDefault()
        cal.timeInMillis = System.currentTimeMillis()
        if (update() || force) invalidate()
    }

    protected abstract fun update(): Boolean
    protected abstract fun drawWidget(canvas: Canvas)
    final override fun onDraw(canvas: Canvas) {
        // everything sits [DY] lower: the island (top centre) and its shadow cover the first ~70 px
        canvas.translate(0f, DY)
        drawWidget(canvas)
    }

    /** A tap at ([x], [y]) in the widget's drawing px (after the [DY] shift). */
    open fun onTap(x: Float, y: Float) {}
    protected open fun onActiveChanged() {}
    protected open fun onNightChanged() {}

    protected fun col(c: Int): Int = Night.map(c, night)
    protected val fg: Int get() = if (night) Night.COLOR else Color.WHITE
    protected fun dim(a: Float): Int = col(withAlpha(Color.WHITE, a))

    /** The caps title row at the top (icon optional), in [color]. */
    protected fun drawHeader(canvas: Canvas, label: String, color: Int, icon: Drawable? = null, drawGlyph: ((Canvas, Float, Float, Int) -> Unit)? = null) {
        var x = PAD
        val rowH = 22f
        if (icon != null) { drawIcon(canvas, icon, x, PAD, rowH, color); x += rowH + 8f }
        if (drawGlyph != null) { drawGlyph(canvas, x, PAD, color); x += rowH + 8f }
        headerText.draw(canvas, label, x, headerText.baselineIn(PAD, rowH), color)
    }

    /** The recognised person, top right: a dot and their name in their colour. */
    protected fun drawPersonBadge(canvas: Canvas) {
        val p = person ?: return
        val c = col(p.color)
        val w = badgeText.width(p.name)
        val x = W - PAD - w
        badgeText.draw(canvas, p.name, x, badgeText.baselineIn(PAD, 22f), c)
        dotPaint.color = c
        canvas.drawCircle(x - 12f, PAD + 11f, 5f, dotPaint)
    }

    protected fun divider(canvas: Canvas, y: Float) {
        linePaint.color = dim(0.16f)
        canvas.drawLine(PAD, y, W - PAD, y, linePaint)
    }

    protected fun navigate(screen: String) {
        try { services.navigator.show(screen) } catch (_: UninitializedPropertyAccessException) {}
    }

    companion object {
        const val W = 448f
        const val H = 432f
        const val PAD = 28f
        const val CONTENT_W = W - 2 * PAD
        /** Content shift down; the last content line must end by [H] - [PAD] - [DY]. */
        const val DY = 16f
    }
}

/** [s] cut with an ellipsis to fit [room] px. */
fun SpecText.fit(s: String, room: Float): String {
    if (width(s) <= room) return s
    var n = s.length
    while (n > 1 && width(s.substring(0, n).trimEnd() + "…") > room) n--
    return s.substring(0, n).trimEnd() + "…"
}

/** "Today", "Tomorrow" or the weekday of [millis], seen from [now]. */
internal fun dayWord(millis: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val d = ((WidgetData.dayStart(millis, zone, 0) - WidgetData.dayStart(now, zone, 0)) / 86_400_000.0 + 0.5).toInt()
    return when (d) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> TimeText.DAYS[Calendar.getInstance(zone).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK) - 1]
    }
}

// ---------------------------------------------------------------------------------------- Clock

class ClockWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.CLOCK
    private val dayText = SpecText(17f, 700, 0.06f)
    private val dateText = SpecText(30f, 700, -0.015f)
    private val timeText = SpecText(144f, 600, -0.045f)
    private val alarmText = SpecText(19f, 600)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_22)

    private var day = ""
    private var date = ""
    private var time = ""
    private var alarm: String? = null

    init {
        observeWhileAttached(services.alarms.next) { refresh() }
        observeWhileAttached(services.alarms.alarms) { refresh() }
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val d = TimeText.DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].uppercase()
        val dt = TimeText.MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.DAY_OF_MONTH)
        val t = TimeText.time(cal, is24)
        val pick = WidgetData.alarmFor(person?.id, services.alarms.alarms.value, services.alarms.next.value, now)
        val a = pick?.let {
            val w = dayWord(it.triggerAtMillis, now)
            (if (w == "Today") "" else "$w ") + TimeText.clock(it.triggerAtMillis, is24)
        }
        if (d == day && dt == date && t == time && a == alarm) return false
        day = d; date = dt; time = t; alarm = a
        return true
    }

    override fun drawWidget(canvas: Canvas) {
        dayText.draw(canvas, day, PAD, dayText.baselineIn(PAD), col(VesperColors.RED))
        dateText.draw(canvas, date, PAD, dateText.baselineIn(PAD + 22f), fg)
        timeText.drawCentered(canvas, time, W / 2f, 290f, fg)
        alarm?.let {
            val c = dim(0.78f)
            val rowH = 22f
            val top = H - PAD - DY - rowH
            val w = 20f + 8f + alarmText.width(it)
            val x = (W - w) / 2f
            drawIcon(canvas, alarmIcon, x, top + 1f, 20f, col(VesperColors.ALARM))
            alarmText.draw(canvas, it, x + 28f, alarmText.baselineIn(top, rowH), c)
        }
    }
}

// -------------------------------------------------------------------------------------- Weather

class WeatherWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.WEATHER
    private val placeText = SpecText(17f, 600)
    private val tempText = SpecText(104f, 300, -0.04f)
    private val condText = SpecText(22f, 700, -0.01f)
    private val hlText = SpecText(18f, 500)
    private val hourLabel = SpecText(14f, 600)
    private val hourTemp = SpecText(19f, 600)
    private val icLocation = context.icon(R.drawable.ic_location)
    private val icSun = context.icon(R.drawable.ic_wx_sun)
    private val icMoon = context.icon(R.drawable.ic_wx_moon)
    private val icCloud = context.icon(R.drawable.ic_wx_cloud)
    private val icRain = context.icon(R.drawable.ic_wx_rain)

    private class Hour(val label: String, val icon: Drawable, val sun: Boolean, val temp: String)

    private var key = ""
    private var place = ""
    private var temp = "--"
    private var cond = ""
    private var hl = ""
    private var icon: Drawable? = null
    private var iconSun = false
    private var hours: List<Hour> = emptyList()

    init {
        observeWhileAttached(services.weather.weather) { refresh() }
        observeWhileAttached(services.location) { refresh() }
    }

    override fun update(): Boolean {
        val w = services.weather.weather.value
        val now = cal.timeInMillis
        val zone = cal.timeZone
        // the hourly labels move on the hour: key on the hour too
        val k = "${w?.updatedAtMillis}/${w?.temperature}/${w?.condition}/${now / 3_600_000L}/$is24/${services.location.value.name}"
        if (k == key) return false
        key = k
        place = placeText.fit(w?.location?.ifEmpty { null } ?: services.location.value.name, CONTENT_W - 22f - 60f)
        if (w == null) {
            temp = "--"; cond = "Weather unavailable"; hl = ""; icon = null; hours = emptyList()
            return true
        }
        val rise = w.sunriseMillis; val set = w.sunsetMillis
        val dayNow = if (rise != null && set != null) now in rise until set else !w.isNight
        temp = "${w.temperature}°"
        cond = condText.fit(conditionLabel(w.condition, dayNow), CONTENT_W)
        hl = "H:${w.high}°  L:${w.low}°"
        icon = iconFor(w.condition, dayNow); iconSun = icon === icSun
        val list = ArrayList<Hour>(HOURS)
        for (f in w.hourly.filter { it.timeMillis > now - 3_600_000L }.take(HOURS)) {
            val hc = Calendar.getInstance(zone).apply { timeInMillis = f.timeMillis }
            val h = hc.get(Calendar.HOUR_OF_DAY)
            val label = if (is24) h.toString().padStart(2, '0') else ((h % 12).takeIf { it != 0 } ?: 12).toString() + (if (h < 12) "AM" else "PM")
            val ic = iconFor(f.condition, isDay(f.timeMillis, w, zone))
            list += Hour(label, ic, ic === icSun, "${f.temperature}°")
        }
        hours = list
        return true
    }

    private fun isDay(t: Long, w: Weather, zone: TimeZone): Boolean {
        val r = w.sunriseMillis; val s = w.sunsetMillis
        val day = 86_400_000L
        fun tod(x: Long) = ((x + zone.getOffset(x)) % day + day) % day
        if (r == null || s == null) return Calendar.getInstance(zone).apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY) in 7..18
        return tod(t) in tod(r) until tod(s)
    }

    private fun iconFor(c: WeatherCondition, day: Boolean): Drawable = when (c) {
        WeatherCondition.CLEAR, WeatherCondition.PARTLY_CLOUDY -> if (day) icSun else icMoon
        WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING, WeatherCondition.SLEET,
        WeatherCondition.HAIL, WeatherCondition.SNOW -> icRain
        else -> icCloud
    }

    private fun conditionLabel(c: WeatherCondition, day: Boolean) = when (c) {
        WeatherCondition.CLEAR -> if (day) "Sunny" else "Clear"
        WeatherCondition.PARTLY_CLOUDY -> "Partly Cloudy"
        WeatherCondition.CLOUDY -> "Cloudy"
        WeatherCondition.FOG -> "Fog"
        WeatherCondition.RAIN -> "Rain"
        WeatherCondition.POURING -> "Heavy Rain"
        WeatherCondition.LIGHTNING -> "Thunderstorms"
        WeatherCondition.SNOW -> "Snow"
        WeatherCondition.SLEET -> "Sleet"
        WeatherCondition.HAIL -> "Hail"
        WeatherCondition.WIND -> "Windy"
        WeatherCondition.EXCEPTIONAL -> ""
    }

    override fun drawWidget(canvas: Canvas) {
        val sub = dim(0.78f)
        drawIcon(canvas, icLocation, PAD, PAD + 2f, 18f, sub)
        placeText.draw(canvas, place, PAD + 24f, placeText.baselineIn(PAD, 22f), sub)
        icon?.let { drawIcon(canvas, it, W - PAD - 52f, PAD - 4f, 52f, if (iconSun) col(VesperColors.YELLOW) else fg) }
        tempText.draw(canvas, temp, PAD - 4f, 172f, fg)
        condText.draw(canvas, cond, PAD, condText.baselineIn(186f), fg)
        if (hl.isNotEmpty()) hlText.draw(canvas, hl, PAD, hlText.baselineIn(214f), sub)
        if (hours.isEmpty()) return
        divider(canvas, 262f)
        val colW = CONTENT_W / HOURS
        for ((i, h) in hours.withIndex()) {
            val cx = PAD + colW * (i + 0.5f)
            hourLabel.drawCentered(canvas, h.label, cx, hourLabel.baselineIn(282f), dim(0.7f))
            drawIcon(canvas, h.icon, cx - 14f, 316f, 28f, if (h.sun) col(VesperColors.YELLOW) else fg)
            hourTemp.drawCentered(canvas, h.temp, cx, hourTemp.baselineIn(358f), fg)
        }
    }

    companion object { private const val HOURS = 5 }
}

// ------------------------------------------------------------------------------------- Calendar

class CalendarWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.CALENDAR
    private val dayText = SpecText(15f, 800, 0.06f)
    private val dateText = SpecText(26f, 700, -0.01f)
    private val timeText = SpecText(15f, 600)
    private val titleText = SpecText(22f, 700, -0.01f)
    private val subText = SpecText(15f, 500)
    private val emptyText = SpecText(24f, 700, -0.01f)
    private val tomorrowText = SpecText(17f, 600)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    private data class Row(val color: Int, val time: String, val title: String, val sub: String)

    private var day = ""
    private var date = ""
    private var rows: List<Row> = emptyList()
    private var tomorrow: String? = null

    init {
        observeWhileAttached(services.calendar.events) { refresh() }
        observeWhileAttached(services.profiles.profiles) { refresh() }
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val d = TimeText.DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].uppercase()
        val dt = TimeText.MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.DAY_OF_MONTH)
        val events = WidgetData.visible(person?.id, services.calendar.events.value)
        val textX = PAD + 18f
        val room = W - PAD - textX
        val r = WidgetData.today(events, now).map { e ->
            val time = when {
                e.allDay -> "All day"
                e.startMillis <= now -> "Now · until ${TimeText.clock(e.endMillis, is24)}"
                else -> "${TimeText.clock(e.startMillis, is24)} – ${TimeText.clock(e.endMillis, is24)}"
            }
            Row(colorOf(e), time, titleText.fit(e.title, room), subText.fit(e.subtitle.ifEmpty { e.location ?: "" }, room))
        }
        val t = if (r.isNotEmpty()) null else WidgetData.firstTomorrow(events, now)?.let {
            tomorrowText.fit("Tomorrow · ${it.title} ${TimeText.clock(it.startMillis, is24)}", CONTENT_W)
        }
        if (d == day && dt == date && r == rows && t == tomorrow) return false
        day = d; date = dt; rows = r; tomorrow = t
        return true
    }

    /** A person's events in their colour, shared ones in the calendar's. */
    private fun colorOf(e: CalendarEvent): Int {
        val pid = e.profileId ?: return e.color
        return services.profiles.profiles.value.firstOrNull { it.id == pid }?.color ?: e.color
    }

    override fun drawWidget(canvas: Canvas) {
        dayText.draw(canvas, day, PAD, dayText.baselineIn(PAD, 22f), col(VesperColors.RED))
        dateText.draw(canvas, date, PAD, dateText.baselineIn(PAD + 22f), fg)
        drawPersonBadge(canvas)
        if (rows.isEmpty()) {
            emptyText.draw(canvas, "No more events today", PAD, emptyText.baselineIn(170f), fg)
            tomorrow?.let { tomorrowText.draw(canvas, it, PAD, tomorrowText.baselineIn(206f), dim(0.7f)) }
            return
        }
        var y = 104f
        for (row in rows) {
            barPaint.color = col(row.color)
            bar.set(PAD, y + 2f, PAD + 5f, y + 74f)
            canvas.drawRoundRect(bar, 2.5f, 2.5f, barPaint)
            val x = PAD + 18f
            timeText.draw(canvas, row.time, x, timeText.baselineIn(y), dim(0.7f))
            titleText.draw(canvas, row.title, x, titleText.baselineIn(y + 20f), fg)
            if (row.sub.isNotEmpty()) subText.draw(canvas, row.sub, x, subText.baselineIn(y + 50f), dim(0.6f))
            y += 98f
        }
    }
}

// ---------------------------------------------------------------------------------- Next alarm

class AlarmWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.ALARM
    private val timeText = SpecText(112f, 600, -0.04f)
    private val ampmText = SpecText(30f, 600)
    private val whenText = SpecText(19f, 600)
    private val labelText = SpecText(24f, 700, -0.01f)
    private val reasonText = SpecText(16f, 600)
    private val emptyText = SpecText(40f, 700, -0.02f)
    private val hintText = SpecText(17f, 600)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_22)

    private var has = false
    private var time = ""
    private var ampm = ""
    private var whenLine = ""
    private var label = ""
    private var reason: String? = null

    init {
        observeWhileAttached(services.alarms.next) { refresh() }
        observeWhileAttached(services.alarms.alarms) { refresh() }
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val p = WidgetData.alarmFor(person?.id, services.alarms.alarms.value, services.alarms.next.value, now)
        val h = p != null
        val t = p?.let { TimeText.clock(it.triggerAtMillis, is24, amPm = false) } ?: ""
        val ap = if (p == null || is24) "" else Calendar.getInstance().apply { timeInMillis = p.triggerAtMillis }.let { if (it.get(Calendar.HOUR_OF_DAY) < 12) "AM" else "PM" }
        val w = p?.let { "${dayWord(it.triggerAtMillis, now)} · ${WidgetData.inText(it.triggerAtMillis - now)}" } ?: ""
        val l = p?.let { labelText.fit(it.alarm.label.ifEmpty { "Alarm" }, CONTENT_W) } ?: ""
        val r = p?.reason?.let { reasonText.fit(it, CONTENT_W) }
        if (h == has && t == time && ap == ampm && w == whenLine && l == label && r == reason) return false
        has = h; time = t; ampm = ap; whenLine = w; label = l; reason = r
        return true
    }

    override fun onTap(x: Float, y: Float) = navigate("alarms")

    override fun drawWidget(canvas: Canvas) {
        val orange = col(VesperColors.ALARM)
        drawHeader(canvas, "ALARM", orange, alarmIcon)
        drawPersonBadge(canvas)
        if (!has) {
            emptyText.draw(canvas, "No alarm", PAD, emptyText.baselineIn(168f), fg)
            hintText.draw(canvas, "Tap to set one", PAD, hintText.baselineIn(218f), dim(0.6f))
            return
        }
        val end = timeText.draw(canvas, time, PAD - 4f, 186f, fg)
        if (ampm.isNotEmpty()) ampmText.draw(canvas, ampm, end + 8f, 186f, fg)
        whenText.draw(canvas, whenLine, PAD, whenText.baselineIn(206f), dim(0.78f))
        labelText.draw(canvas, label, PAD, labelText.baselineIn(262f), fg)
        reason?.let { reasonText.draw(canvas, it, PAD, reasonText.baselineIn(298f), withAlpha(orange, 0.9f)) }
    }
}

// --------------------------------------------------------------------------------------- Timers

class TimersWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.TIMERS
    private val bigText = SpecText(100f, 600, -0.03f)
    private val labelText = SpecText(22f, 700, -0.01f)
    private val stateText = SpecText(17f, 600)
    private val rowText = SpecText(20f, 600)
    private val emptyText = SpecText(40f, 700, -0.02f)
    private val hintText = SpecText(17f, 600)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f; strokeCap = Paint.Cap.ROUND }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh(); schedule() }

    private data class Row(val label: String, val time: String, val state: VesperTimer.State)

    private var first: Row? = null
    private var progress = 0f
    private var others: List<Row> = emptyList()

    init {
        observeWhileAttached(services.timers.timers) { refresh(); schedule() }
    }

    override fun onActiveChanged() = schedule()
    override fun onDetachedFromWindow() { handler.removeCallbacks(tick); super.onDetachedFromWindow() }

    /** One tick at the next whole second of the first running timer, only while active and running. */
    private fun schedule() {
        handler.removeCallbacks(tick)
        if (!active || !isAttachedToWindow) return
        val now = SystemClock.elapsedRealtime()
        val running = services.timers.timers.value.filter { it.state == VesperTimer.State.RUNNING && it.remainingMs(now) > 0 }
        if (running.isEmpty()) return
        val rem = running.minOf { it.remainingMs(now) }
        val delay = (rem % 1000).let { if (it == 0L) 1000L else it } + 5
        handler.postDelayed(tick, delay)
    }

    override fun update(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val list = services.timers.timers.value
        val rows = list.map { t ->
            Row(t.label.ifEmpty { "Timer" }, WidgetData.countdown(t.remainingMs(now)), t.state)
        }
        val f = rows.firstOrNull()?.let { it.copy(label = labelText.fit(it.label, CONTENT_W - 90f)) }
        val p = list.firstOrNull()?.let { t -> if (t.durationMs > 0) (t.remainingMs(now).toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f } ?: 0f
        val o = rows.drop(1).map { it.copy(label = rowText.fit(it.label, CONTENT_W - 110f)) }
        val pq = (p * 400).toInt() / 400f
        if (f == first && o == others && pq == progress) return false
        first = f; others = o; progress = pq
        return true
    }

    override fun onTap(x: Float, y: Float) = navigate("timers")

    private fun drawTimerGlyph(c: Canvas, x: Float, y: Float, color: Int) {
        glyph.color = color
        val s = 22f / 24f
        c.drawCircle(x + 12f * s, y + 13.5f * s, 8f * s, glyph)
        c.drawLine(x + 9.5f * s, y + 2.5f * s, x + 14.5f * s, y + 2.5f * s, glyph)
        c.drawLine(x + 12f * s, y + 13.5f * s, x + 12f * s, y + 9f * s, glyph)
    }

    override fun drawWidget(canvas: Canvas) {
        val orange = col(VesperColors.ALARM)
        val n = (if (first != null) 1 else 0) + others.size
        drawHeader(canvas, if (n > 1) "TIMERS" else "TIMER", orange, drawGlyph = ::drawTimerGlyph)
        val f = first
        if (f == null) {
            emptyText.draw(canvas, "No timers", PAD, emptyText.baselineIn(168f), fg)
            hintText.draw(canvas, "Tap to start one", PAD, hintText.baselineIn(218f), dim(0.6f))
            return
        }
        val paused = f.state == VesperTimer.State.PAUSED
        val done = f.state == VesperTimer.State.DONE
        bigText.draw(canvas, f.time, PAD - 4f, 172f, if (paused) dim(0.55f) else fg)
        labelText.draw(canvas, f.label, PAD, labelText.baselineIn(192f), fg)
        val st = when { done -> "Done"; paused -> "Paused"; else -> null }
        st?.let { stateText.draw(canvas, it, W - PAD - stateText.width(it), stateText.baselineIn(195f), orange) }
        // remaining bar
        val top = 236f
        barRect.set(PAD, top, W - PAD, top + 6f)
        track.color = dim(0.16f)
        canvas.drawRoundRect(barRect, 3f, 3f, track)
        if (progress > 0f) {
            barRect.right = PAD + CONTENT_W * progress
            track.color = if (paused) withAlpha(orange, 0.5f) else orange
            canvas.drawRoundRect(barRect, 3f, 3f, track)
        }
        var y = 266f
        for (o in others) {
            divider(canvas, y)
            val base = rowText.baselineIn(y + 14f)
            rowText.draw(canvas, o.label, PAD, base, dim(0.85f))
            rowText.draw(canvas, o.time, W - PAD - rowText.width(o.time), base,
                if (o.state == VesperTimer.State.RUNNING) fg else if (o.state == VesperTimer.State.DONE) orange else dim(0.5f))
            y += 54f
        }
    }
}

// ---------------------------------------------------------------------------------- Now Playing

class NowPlayingWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.NOW_PLAYING
    private val sourceText = SpecText(13f, 700, 0.05f)
    private val deviceText = SpecText(17f, 600)
    private val titleText = SpecText(26f, 700, -0.015f)
    private val artistText = SpecText(19f, 600)
    private val emptyText = SpecText(28f, 700, -0.015f)
    private val hintText = SpecText(16f, 600)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val artRect = RectF(PAD, PAD, PAD + ART, PAD + ART)
    private val artMatrix = Matrix()
    private val path = Path()
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        // luminance → Night red (#BE180E), alpha kept
        0.2126f * 0.745f, 0.7152f * 0.745f, 0.0722f * 0.745f, 0f, 0f,
        0.2126f * 0.094f, 0.7152f * 0.094f, 0.0722f * 0.094f, 0f, 0f,
        0.2126f * 0.055f, 0.7152f * 0.055f, 0.0722f * 0.055f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))

    private var session: MediaSession? = null
    private var art: Bitmap? = null
    private var source = ""
    private var device = ""
    private var title = ""
    private var artist = ""
    private var playing = false
    private var canSkip = true
    private var empty = true
    private var hint = ""

    init {
        observeWhileAttached(services.media.current) { session = it; refresh() }
    }

    override fun onNightChanged() { artPaint.colorFilter = if (night) nightFilter else null }

    override fun update(): Boolean {
        val s = session
        val st = s?.state
        val e = st == null || (st.title.isEmpty() && st.artist.isEmpty())
        val textX = PAD + ART + 18f
        val src = if (st == null) "" else when (st.source) {
            MediaSourceKind.AIRPLAY -> "AIRPLAY"
            MediaSourceKind.BLUETOOTH -> "BLUETOOTH"
            MediaSourceKind.SPOTIFY -> "SPOTIFY"
            MediaSourceKind.OTHER_APP -> "NOW PLAYING"
        }
        val dev = deviceText.fit(st?.sourceLabel ?: "", W - PAD - textX)
        val t = titleText.fit(st?.title ?: "", CONTENT_W)
        val a = artistText.fit(st?.artist ?: "", CONTENT_W)
        val p = st?.playing == true
        val sk = s?.controls?.canSkip ?: false
        val h = hintText.fit("AirPlay or Bluetooth to “${DeviceName.get(services.settings)}”", CONTENT_W)
        val bmp = st?.artwork?.takeIf { !it.isRecycled }
        if (e == empty && src == source && dev == device && t == title && a == artist && p == playing && sk == canSkip && bmp === art && h == hint) return false
        if (bmp !== art) {
            art = bmp
            artPaint.shader = bmp?.let { b ->
                val sc = max(ART / b.width, ART / b.height)
                artMatrix.setScale(sc, sc)
                artMatrix.postTranslate(PAD + (ART - b.width * sc) / 2f, PAD + (ART - b.height * sc) / 2f)
                BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(artMatrix) }
            }
        }
        empty = e; source = src; device = dev; title = t; artist = a; playing = p; canSkip = sk; hint = h
        return true
    }

    override fun onTap(x: Float, y: Float) {
        val c = session?.controls ?: return
        if (empty || abs(y - CONTROLS_Y) > 56f) return
        when {
            abs(x - W / 2f) < 56f -> if (playing) c.pause() else c.play()
            abs(x - (W / 2f - SKIP_DX)) < 50f -> if (canSkip) c.previous()
            abs(x - (W / 2f + SKIP_DX)) < 50f -> if (canSkip) c.next()
        }
    }

    private fun drawNote(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        // beamed eighth notes, 56 px
        note.color = color; icon.color = color
        path.rewind()
        path.moveTo(cx - 10f, cy + 16f); path.lineTo(cx - 10f, cy - 20f); path.lineTo(cx + 18f, cy - 26f); path.lineTo(cx + 18f, cy + 10f)
        canvas.drawPath(path, note)
        canvas.drawCircle(cx - 17f, cy + 17f, 8f, icon)
        canvas.drawCircle(cx + 11f, cy + 11f, 8f, icon)
    }

    override fun drawWidget(canvas: Canvas) {
        if (empty) {
            drawNote(canvas, W / 2f, 140f, dim(0.6f))
            emptyText.drawCentered(canvas, "Not playing", W / 2f, emptyText.baselineIn(206f), fg)
            hintText.drawCentered(canvas, hint, W / 2f, hintText.baselineIn(246f), dim(0.6f))
            return
        }
        if (art != null) canvas.drawRoundRect(artRect, 18f, 18f, artPaint)
        else {
            placeholder.color = dim(0.12f)
            canvas.drawRoundRect(artRect, 18f, 18f, placeholder)
            drawNote(canvas, artRect.centerX(), artRect.centerY(), dim(0.5f))
        }
        val x = PAD + ART + 18f
        sourceText.draw(canvas, source, x, sourceText.baselineIn(PAD + 6f), dim(0.6f))
        if (device.isNotEmpty()) deviceText.draw(canvas, device, x, deviceText.baselineIn(PAD + 28f), dim(0.85f))
        titleText.draw(canvas, title, PAD, titleText.baselineIn(184f), fg)
        artistText.draw(canvas, artist, PAD, artistText.baselineIn(218f), dim(0.7f))
        // transport: previous, play / pause, next
        val skipColor = if (canSkip) fg else dim(0.3f)
        drawSkip(canvas, W / 2f - SKIP_DX, CONTROLS_Y, forward = false, skipColor)
        drawSkip(canvas, W / 2f + SKIP_DX, CONTROLS_Y, forward = true, skipColor)
        icon.color = fg
        if (playing) {
            artRect.set(W / 2f - 14f, CONTROLS_Y - 20f, W / 2f - 4f, CONTROLS_Y + 20f)
            canvas.drawRoundRect(artRect, 3f, 3f, icon)
            artRect.set(W / 2f + 4f, CONTROLS_Y - 20f, W / 2f + 14f, CONTROLS_Y + 20f)
            canvas.drawRoundRect(artRect, 3f, 3f, icon)
            artRect.set(PAD, PAD, PAD + ART, PAD + ART)
        } else {
            path.rewind()
            path.moveTo(W / 2f - 13f, CONTROLS_Y - 21f); path.lineTo(W / 2f + 21f, CONTROLS_Y); path.lineTo(W / 2f - 13f, CONTROLS_Y + 21f); path.close()
            canvas.drawPath(path, icon)
        }
    }

    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, forward: Boolean, color: Int) {
        icon.color = color
        val d = if (forward) 1f else -1f
        path.rewind()
        for (k in 0..1) {
            val x0 = cx + d * (-16f + k * 16f)
            path.moveTo(x0, cy - 12f); path.lineTo(x0 + d * 16f, cy); path.lineTo(x0, cy + 12f); path.close()
        }
        canvas.drawPath(path, icon)
    }

    companion object {
        private const val ART = 132f
        private const val CONTROLS_Y = 322f
        private const val SKIP_DX = 108f
    }
}

// ------------------------------------------------------------------------------------- Air & UV

class AirWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.AIR
    private val capsText = SpecText(15f, 800, 0.06f)
    private val aqiText = SpecText(84f, 600, -0.03f)
    private val catText = SpecText(22f, 700, -0.01f)
    private val uvText = SpecText(52f, 600, -0.03f)
    private val uvCatText = SpecText(20f, 700)
    private val noteText = SpecText(16f, 600)
    private val emptyText = SpecText(24f, 700, -0.01f)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val barRect = RectF(PAD, BAR_Y, W - PAD, BAR_Y + 8f)
    private val dayShader = LinearGradient(PAD, 0f, W - PAD, 0f,
        intArrayOf(0xFF30D158.toInt(), 0xFFFFD60A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt(), 0xFFBF5AF2.toInt(), 0xFFA2264B.toInt()),
        floatArrayOf(0f, 1 / 6f, 2 / 6f, 3 / 6f, 4 / 6f, 1f), Shader.TileMode.CLAMP)

    private var aqi: String? = null
    private var cat = ""
    private var catColor = 0
    private var frac = 0f
    private var uv: String? = null
    private var uvCat = ""
    private var uvColor = 0
    private var uvMax = ""
    private var pollen = ""

    init { observeWhileAttached(services.weather.weather) { refresh() } }

    override fun update(): Boolean {
        val a = services.weather.weather.value?.air
        val q = a?.usAqi
        val nAqi = q?.toString()
        val nCat = a?.aqiCategory ?: ""
        val nColor = q?.let { WidgetData.aqiColor(it) } ?: 0
        val nFrac = q?.let { (it / 300f).coerceIn(0f, 1f) } ?: 0f
        val u = a?.uvIndex
        val nUv = u?.let { "%.0f".format(it) }
        val nUvCat = u?.let { WidgetData.uvCategory(it) } ?: ""
        val nUvColor = u?.let { WidgetData.uvColor(it) } ?: 0
        val nMax = a?.uvIndexMax?.let { "Today’s high %.0f".format(it) } ?: ""
        val nPollen = a?.pollen?.maxByOrNull { it.value }?.takeIf { it.value >= 1f }?.let { (k, v) ->
            "Pollen · ${k.replaceFirstChar { it.uppercase() }} %.0f grains/m³".format(v)
        } ?: ""
        if (nAqi == aqi && nCat == cat && nFrac == frac && nUv == uv && nUvCat == uvCat && nMax == uvMax && nPollen == pollen && nColor == catColor && nUvColor == uvColor) return false
        aqi = nAqi; cat = nCat; catColor = nColor; frac = nFrac; uv = nUv; uvCat = nUvCat; uvColor = nUvColor; uvMax = nMax; pollen = nPollen
        return true
    }

    override fun drawWidget(canvas: Canvas) {
        capsText.draw(canvas, "AIR QUALITY", PAD, capsText.baselineIn(PAD, 22f), dim(0.6f))
        if (aqi == null && uv == null) {
            emptyText.draw(canvas, "Air quality unavailable", PAD, emptyText.baselineIn(170f), fg)
            return
        }
        aqi?.let {
            val end = aqiText.draw(canvas, it, PAD - 3f, 140f, fg)
            catText.draw(canvas, cat, end + 14f, 140f, col(catColor))
            // scale 0–300 with the reading's mark
            barPaint.shader = if (night) null else dayShader
            barPaint.color = if (night) withAlpha(Night.COLOR, 0.4f) else Color.WHITE
            canvas.drawRoundRect(barRect, 4f, 4f, barPaint)
            val mx = PAD + 8f + (CONTENT_W - 16f) * frac
            markPaint.color = if (night) Night.COLOR else Color.WHITE
            ringPaint.color = if (night) Color.BLACK else withAlpha(Color.BLACK, 0.35f)
            canvas.drawCircle(mx, BAR_Y + 4f, 9f, markPaint)
            canvas.drawCircle(mx, BAR_Y + 4f, 9f, ringPaint)
        }
        divider(canvas, 220f)
        capsText.draw(canvas, "UV INDEX", PAD, capsText.baselineIn(236f, 22f), dim(0.6f))
        uv?.let {
            val end = uvText.draw(canvas, it, PAD - 2f, 318f, fg)
            uvCatText.draw(canvas, uvCat, end + 12f, 318f, col(uvColor))
        } ?: noteText.draw(canvas, "No UV reading", PAD, noteText.baselineIn(280f), dim(0.6f))
        if (uvMax.isNotEmpty()) noteText.draw(canvas, uvMax, PAD, noteText.baselineIn(326f), dim(0.7f))
        if (pollen.isNotEmpty()) noteText.draw(canvas, noteText.fit(pollen, CONTENT_W), PAD, noteText.baselineIn(352f), dim(0.7f))
    }

    companion object { private const val BAR_Y = 168f }
}

// ----------------------------------------------------------------------------------- Sun & Moon

class SunMoonWidget(context: Context, services: VesperServices) : StackWidget(context, services) {
    override val kind = WidgetKind.SUN_MOON
    private val capsText = SpecText(15f, 800, 0.06f)
    private val timeText = SpecText(19f, 600)
    private val nextText = SpecText(16f, 600)
    private val moonText = SpecText(22f, 700, -0.01f)
    private val moonSub = SpecText(16f, 600)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; strokeCap = Paint.Cap.ROUND }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val arc = Path()
    private val done = Path()
    private val arrowPath = Path()
    private val moon = MoonPainter(rimWidth = 2f)

    private var rise = "--"
    private var set = "--"
    private var frac = -1f
    private var next = ""
    private var moonName = ""
    private var moonLine = ""
    private var illum = 0.0
    private var rightLit = true

    init {
        observeWhileAttached(services.weather.weather) { refresh() }
        observeWhileAttached(services.location) { refresh() }
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val zone = cal.timeZone
        val loc = services.location.value
        val w = services.weather.weather.value
        val reported = if (w?.sunriseMillis != null && w.sunsetMillis != null) w.sunriseMillis!! to w.sunsetMillis!! else null
        val today = reported ?: SunMath.sunriseSunset(now, loc.lat, loc.lon, zone)
        val r = today?.let { TimeText.clock(it.first, is24) } ?: "--"
        val s = today?.let { TimeText.clock(it.second, is24) } ?: "--"
        val f = today?.let { (a, b) -> if (now in a..b && b > a) ((now - a).toFloat() / (b - a) * 500).toInt() / 500f else -1f } ?: -1f
        val n = when {
            today == null -> ""
            now < today.first -> "Sunrise ${WidgetData.inText(today.first - now)}"
            now < today.second -> "Sunset ${WidgetData.inText(today.second - now)}"
            else -> SunTimes.next(now, loc.lat, loc.lon, zone, null)?.let { "Sunrise ${WidgetData.inText(it.first - now)}" } ?: ""
        }
        val p = MoonMath.phase(now)
        val (event, days) = WidgetData.nextMoonEvent(p.elongation)
        val mn = p.name
        val ml = "${p.percent}% lit · " + when (days) { 0 -> "$event today"; 1 -> "$event tomorrow"; else -> "$event in $days days" }
        val rl = p.waxing == (loc.lat >= 0)
        if (r == rise && s == set && f == frac && n == next && mn == moonName && ml == moonLine && rl == rightLit) return false
        rise = r; set = s; frac = f; next = n; moonName = mn; moonLine = ml; illum = p.illumination; rightLit = rl
        return true
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (arc.isEmpty) {
            val oval = RectF(ARC_L, HORIZON - ARC_RY, W - ARC_L, HORIZON + ARC_RY)
            arc.addArc(oval, 180f, 180f)
        }
    }

    override fun drawWidget(canvas: Canvas) {
        capsText.draw(canvas, "SUN & MOON", PAD, capsText.baselineIn(PAD, 22f), dim(0.6f))
        val yellow = col(VesperColors.YELLOW)
        // horizon and the day's arc; the part already travelled in yellow
        linePaint.color = dim(0.22f)
        canvas.drawLine(PAD, HORIZON, W - PAD, HORIZON, linePaint)
        arcPaint.color = dim(0.25f)
        canvas.drawPath(arc, arcPaint)
        if (frac >= 0f) {
            done.rewind()
            val oval = tmpOval.apply { set(ARC_L, HORIZON - ARC_RY, W - ARC_L, HORIZON + ARC_RY) }
            done.addArc(oval, 180f, 180f * frac)
            arcPaint.color = withAlpha(yellow, 0.85f)
            canvas.drawPath(done, arcPaint)
            val a = Math.PI * (1.0 - frac)
            val rx = (W - 2 * ARC_L) / 2f
            val sx = W / 2f + (rx * cos(a)).toFloat()
            val sy = HORIZON - (ARC_RY * sin(a)).toFloat()
            sunPaint.color = withAlpha(yellow, 0.25f)
            canvas.drawCircle(sx, sy, 18f, sunPaint)
            sunPaint.color = yellow
            canvas.drawCircle(sx, sy, 10f, sunPaint)
        }
        val base = timeText.baselineIn(HORIZON + 14f)
        val c = fg
        drawArrow(canvas, PAD + 7f, base - 7f, up = true, yellow)
        timeText.draw(canvas, rise, PAD + 22f, base, c)
        val sw = timeText.width(set)
        timeText.draw(canvas, set, W - PAD - sw, base, c)
        drawArrow(canvas, W - PAD - sw - 15f, base - 7f, up = false, col(VesperColors.ORANGE))
        if (next.isNotEmpty()) nextText.drawCentered(canvas, next, W / 2f, nextText.baselineIn(HORIZON + 46f), dim(0.7f))
        divider(canvas, 292f)
        moon.draw(canvas, PAD + 34f, 348f, 32f, illum, rightLit, if (night) Night.COLOR else withAlpha(Color.WHITE, 0.92f))
        moonText.draw(canvas, moonName, PAD + 84f, moonText.baselineIn(320f), fg)
        moonSub.draw(canvas, moonSub.fit(moonLine, W - PAD - (PAD + 84f)), PAD + 84f, moonSub.baselineIn(350f), dim(0.7f))
    }

    private val tmpOval = RectF()

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, up: Boolean, color: Int) {
        arrow.color = color
        val h = 7f
        val d = if (up) -1f else 1f
        arrowPath.rewind()
        arrowPath.moveTo(cx, cy - h * d); arrowPath.lineTo(cx, cy + h * d)
        arrowPath.moveTo(cx - 5f, cy + (h - 5f) * d); arrowPath.lineTo(cx, cy + h * d); arrowPath.lineTo(cx + 5f, cy + (h - 5f) * d)
        canvas.drawPath(arrowPath, arrow)
    }

    companion object {
        private const val HORIZON = 196f
        private const val ARC_L = PAD + 40f
        private const val ARC_RY = 128f
    }
}

/** Builds the view for [kind]. */
fun createWidget(kind: WidgetKind, context: Context, services: VesperServices): StackWidget = when (kind) {
    WidgetKind.CLOCK -> ClockWidget(context, services)
    WidgetKind.WEATHER -> WeatherWidget(context, services)
    WidgetKind.CALENDAR -> CalendarWidget(context, services)
    WidgetKind.ALARM -> AlarmWidget(context, services)
    WidgetKind.TIMERS -> TimersWidget(context, services)
    WidgetKind.NOW_PLAYING -> NowPlayingWidget(context, services)
    WidgetKind.AIR -> AirWidget(context, services)
    WidgetKind.SUN_MOON -> SunMoonWidget(context, services)
}
