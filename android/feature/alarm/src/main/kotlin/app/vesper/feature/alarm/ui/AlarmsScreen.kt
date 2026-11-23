package app.vesper.feature.alarm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import app.vesper.core.design.Backdrop
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.Alarm
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.alarm.AlarmFormat
import app.vesper.feature.alarm.engine.LeaveBy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The alarm list and editor (no board yet: built from the Timers board's warm gradient, glass cards
 * and controls). The list shows every alarm with its repeat / leave-by / skip-holidays summary and
 * an on/off switch, and, above it, the next alarm with its reason ("Earlier for Dentist 9:00 · 25
 * min drive", "Skipping Thanksgiving"). Tapping a row or "New alarm" opens the editor: time, repeat
 * days, Leave-by with the getting-ready time, Skip holidays. Everything is drawn when it changes.
 * Debug: `--es screen alarms --es alarms_demo editor|editor_new`.
 */
@SuppressLint("ViewConstructor")
class AlarmsScreen(context: Context, private val services: VesperServices) : AbsLayout(context), VesperScreen {
    private val title = SpecText(28f, 800, -0.025f).apply { paint.fontFeatureSettings = "" }
    private val summary = SpecText(14f, 600).apply { paint.fontFeatureSettings = "" }
    private val reasonT = SpecText(14f, 700).apply { paint.fontFeatureSettings = "" }
    private val emptyT = SpecText(30f, 700)
    private val hint = SpecText(15f, 600).apply { paint.fontFeatureSettings = "" }
    private var night = false
    private var shown = false
    private var next: NextAlarm? = null
    private var alarms: List<Alarm> = emptyList()

    private var bg: Bitmap? = null
    private var backdrop: Backdrop? = null
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 4, 28, 28) }
    private val scroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; clipToPadding = false
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val editor = AlarmEditor(context)

    private val addBtn = GlassButton(context, Glass.button(), SpecText(15f, 700), "New alarm")
        .apply { icon = Icon.PLUS; iconSize = 18f; iconStroke = 2.6f; gap = 7f; radius = 22f }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        addBtn.backdropOrigin = this
        val w = addBtn.wrapWidth(14f, 18f)
        place(addBtn, 932f - w, 22f, w, 44)
        place(scroll, 0f, 100f, 960, 380)
        place(editor, 0f, 0f, 960, 480)
        editor.visibility = GONE
        editor.backdropOrigin = this
        editor.onSave = { a -> services.alarms.upsert(a); closeEditor() }
        editor.onDelete = { id -> services.alarms.delete(id); closeEditor() }
        editor.onCancel = { closeEditor() }
        addBtn.onTap = { openEditor(null) }
        observeWhileAttached(services.alarms.alarms) { alarms = it; if (shown) rebuild() }
        observeWhileAttached(services.alarms.next) { next = it; invalidate() }
        observeWhileAttached(services.system.state) { s -> if (s.nightActive != night) { night = s.nightActive; applyNight() } }
    }

    override fun onScreenShown(args: Bundle?) {
        shown = true
        ensureBackground()
        rebuild()
        when (args?.getString("alarms_demo")) {
            "editor" -> openEditor(Alarm("demo", 7, 0, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                "Work days", leaveBy = true, readyMinutes = 45, skipHolidays = true))
            "editor_new" -> openEditor(null)
        }
    }

    override fun onScreenHidden() {
        shown = false
        closeEditor()
    }

    private fun ensureBackground() {
        if (bg != null) return
        val b = WarmBackground.render()
        bg = b
        backdrop = Backdrop(PreBlur.blur(b, 20f, 4, 1.8f, 1.1f), 0f, 0f, 960f, 480f)
        addBtn.glass.backdrop = backdrop
        editor.backdrop = backdrop
        invalidate()
    }

    private fun applyNight() {
        addBtn.glass.night = night
        addBtn.color = if (night) Night.COLOR else Color.WHITE
        addBtn.invalidate()
        editor.setNight(night)
        if (shown) rebuild()
        invalidate()
    }

    private fun openEditor(a: Alarm?) {
        editor.open(a)
        editor.visibility = VISIBLE
    }

    private fun closeEditor() { editor.visibility = GONE }

    private fun rebuild() {
        list.removeAllViews()
        for (a in alarms.sortedWith(compareBy({ it.hour }, { it.minute }))) {
            val row = AlarmRow(context, a)
            row.glass.backdrop = backdrop
            row.glass.night = night
            row.onTap = { if (row.lastDownX > 904 - 112) services.alarms.setEnabled(a.id, !a.enabled) else openEditor(a) }
            list.addView(row, LinearLayout.LayoutParams(904, 76).apply { bottomMargin = 12 })
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (night) c.drawColor(Color.BLACK) else bg?.let { c.drawBitmap(it, 0f, 0f, bgPaint) }
        val white = Night.map(Color.WHITE, night)
        title.draw(c, "Alarms", 32f, title.baselineIn(22f, 28f * 1.1f), white)
        var y = 22f + 28f * 1.1f + 2f
        summary.draw(c, nextText(), 32f, summary.baselineIn(y), Night.map(withAlpha(Color.WHITE, 0.78f), night))
        next?.reason?.let {
            y += summary.normalLineHeight + 1f
            reasonT.draw(c, it, 32f, reasonT.baselineIn(y), Night.map(0xFFFFC46B.toInt(), night))
        }
        if (alarms.isEmpty()) {
            val s = "No alarms"
            emptyT.draw(c, s, 480f - emptyT.width(s) / 2, emptyT.baselineIn(200f), Night.map(withAlpha(Color.WHITE, 0.86f), night))
            val h = "Tap New alarm, or say “Hey Nova, wake me at 7”"
            hint.draw(c, h, 480f - hint.width(h) / 2, hint.baselineIn(250f), Night.map(withAlpha(Color.WHITE, 0.72f), night))
        }
    }

    private fun nextText(): String {
        val n = next ?: return "No alarm set"
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(n.triggerAtMillis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val label = when (day) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        }
        return "Next · $label ${AlarmFormat.eventTime(context, n.triggerAtMillis)}"
    }

    // --- one alarm in the list ---

    private inner class AlarmRow(context: Context, val alarm: Alarm) : PressableView(context) {
        val glass = SpecGlassPainter(Glass.card())
        var lastDownX = 0f
        private val timeT = SpecText(38f, 600, -0.03f)
        private val ampmT = SpecText(16f, 700)
        private val labelT = SpecText(18f, 700, -0.015f).apply { paint.fontFeatureSettings = "" }
        private val detailT = SpecText(14f, 600).apply { paint.fontFeatureSettings = "" }
        private val sw = SwitchPainter()

        init { pressScale = 0.985f }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastDownX = e.x
            return super.dispatchTouchEvent(e)
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            var x = 0f; var y = 0f
            var v: View? = this
            while (v != null && v !== this@AlarmsScreen) { x += v.left - (if (v !== this) v.scrollX else 0); y += v.top - (if (v !== this) v.scrollY else 0); v = v.parent as? View }
            glass.draw(c, w, h, 28f, x, y)
            val dim = if (alarm.enabled) 1f else 0.5f
            val white = Night.map(withAlpha(Color.WHITE, dim), night)
            val soft = Night.map(withAlpha(Color.WHITE, 0.72f * dim), night)
            val t = AlarmFormat.alarmTime(context, alarm)
            timeT.draw(c, t, 24f, timeT.baselineIn(0f, h), white)
            var tx = 24f + timeT.width(t)
            if (!AlarmFormat.is24(context)) {
                ampmT.draw(c, if (alarm.hour < 12) "AM" else "PM", tx + 6f, timeT.baselineIn(0f, h), soft)
                tx += 6f + ampmT.width("AM")
            }
            val left = 220f
            val lineH = labelT.normalLineHeight + detailT.normalLineHeight + 3f
            val top = (h - lineH) / 2
            labelT.draw(c, alarm.label, left, labelT.baselineIn(top), white)
            detailT.draw(c, details(alarm), left, detailT.baselineIn(top + labelT.normalLineHeight + 3f), soft)
            sw.draw(c, w - 24f - 58f, (h - 34f) / 2, alarm.enabled, night)
        }
    }

    private fun details(a: Alarm): String = listOfNotNull(
        daysText(a.days),
        if (a.leaveBy) "Leave-by" else null,
        if (a.skipHolidays) "Skips holidays" else null,
    ).joinToString(" · ")

    companion object {
        fun daysText(days: Set<DayOfWeek>): String = when {
            days.isEmpty() -> "Once"
            days.size == 7 -> "Every day"
            days == WEEKDAYS -> "Weekdays"
            days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
            else -> days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }

        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** Glass values shared by the alarm list and editor (the Timers board's cards and buttons). */
internal object Glass {
    fun button() = GlassSpec(
        fill = withAlpha(Color.WHITE, 0.1f), border = withAlpha(Color.WHITE, 0.26f), rimTop = 0.45f, rimBottom = 0,
        shadow = withAlpha(Color.BLACK, 0.3f), shadowDy = 8f, shadowBlur = 24f,
        specAngle = 165f, specStops = floatArrayOf(0f, 0.55f), specAlphas = floatArrayOf(0.26f, 0f),
    )

    fun card() = GlassSpec(
        fill = withAlpha(Color.WHITE, 0.09f), border = withAlpha(Color.WHITE, 0.26f), rimTop = 0.48f, rimTopWidth = 1.5f,
        rimBottom = withAlpha(Color.WHITE, 0.08f), shadow = withAlpha(Color.BLACK, 0.3f), shadowDy = 10f, shadowBlur = 28f,
        specAngle = 160f, specStops = floatArrayOf(0f, 0.36f, 0.6f, 1f), specAlphas = floatArrayOf(0.24f, 0.03f, 0f, 0.06f),
    )

    fun panel() = GlassSpec(
        fill = withAlpha(Color.WHITE, 0.07f), border = withAlpha(Color.WHITE, 0.26f), rimTop = 0.48f, rimTopWidth = 1.5f,
        rimBottom = withAlpha(Color.WHITE, 0.08f), shadow = withAlpha(Color.BLACK, 0.45f), shadowDy = 18f, shadowBlur = 44f,
        specAngle = 160f, specStops = floatArrayOf(0f, 0.36f, 0.6f, 1f), specAlphas = floatArrayOf(0.2f, 0.03f, 0f, 0.05f),
    )
}

/** An iOS-style switch, drawn in place by whoever owns it. */
internal class SwitchPainter {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun draw(c: Canvas, x: Float, y: Float, on: Boolean, night: Boolean) {
        r.set(x, y, x + 58f, y + 34f)
        p.color = Night.map(if (on) VesperColors.GREEN else withAlpha(Color.WHITE, 0.24f), night)
        if (night && on) p.color = withAlpha(Night.COLOR, 0.7f)
        c.drawRoundRect(r, 17f, 17f, p)
        p.color = if (night) Night.COLOR else Color.WHITE
        c.drawCircle(if (on) x + 58f - 17f else x + 17f, y + 17f, 14f, p)
    }
}

/** A toggle switch as a view (the editor's rows). */
internal class SwitchView(context: Context) : PressableView(context) {
    var on = false
        set(v) { field = v; invalidate() }
    var night = false
    var onChange: ((Boolean) -> Unit)? = null
    private val sw = SwitchPainter()

    init { pressScale = 0.94f; onTap = { on = !on; onChange?.invoke(on) } }

    override fun onDraw(c: Canvas) = sw.draw(c, 0f, 0f, on, night)
}

/** A pill choice (days, ready times, AM/PM). */
internal class Chip(context: Context, var label: String, size: Float = 16f) : PressableView(context) {
    private val t = SpecText(size, 700)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var chosen = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var night = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var round = false

    init { pressScale = 0.92f }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        p.color = when {
            chosen && night -> withAlpha(Night.COLOR, 0.5f)
            chosen -> VesperColors.ORANGE
            night -> withAlpha(Night.COLOR, 0.14f)
            else -> withAlpha(Color.WHITE, 0.14f)
        }
        c.drawRoundRect(r, h / 2, h / 2, p)
        val ink = when {
            night -> Night.COLOR
            chosen -> 0xFF2A1200.toInt()
            else -> Color.WHITE
        }
        t.draw(c, label, (w - t.width(label)) / 2, t.baselineIn(0f, h), ink)
    }
}

/** The editor: a glass sheet over the list. */
@SuppressLint("ViewConstructor")
internal class AlarmEditor(context: Context) : AbsLayout(context) {
    var onSave: ((Alarm) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null
    var onCancel: (() -> Unit)? = null
    var backdropOrigin: View? = null
    var backdrop: Backdrop? = null
        set(v) { field = v; panel.backdrop = v; buttons.forEach { it.glass.backdrop = v }; invalidate() }

    private var night = false
    private var cur = Alarm("new", 7, 0)
    private var isNew = true
    private var confirmDelete = false

    private val panel = SpecGlassPainter(Glass.panel())
    private val head = SpecText(24f, 800, -0.02f).apply { paint.fontFeatureSettings = "" }
    private val digits = SpecText(84f, 600, -0.04f)
    private val colon = SpecText(70f, 600)
    private val cap = SpecText(14f, 700).apply { paint.fontFeatureSettings = "" }
    private val rowT = SpecText(18f, 700, -0.01f).apply { paint.fontFeatureSettings = "" }
    private val rowS = SpecText(13f, 600).apply { paint.fontFeatureSettings = "" }

    private val cancelBtn = GlassButton(context, Glass.button(), SpecText(16f, 600), "Cancel").apply { radius = 22f }
    private val deleteBtn = GlassButton(context, Glass.button(), SpecText(16f, 600), "Delete").apply { radius = 22f }
    private val saveBtn = GlassButton(context, GlassSpec(
        fill = withAlpha(VesperColors.BLUE, 0.62f), border = withAlpha(Color.WHITE, 0.28f), rimTop = 0.5f, rimBottom = 0,
        shadow = withAlpha(Color.BLACK, 0.45f), shadowDy = 8f, shadowBlur = 24f,
        specAngle = 165f, specStops = floatArrayOf(0f, 0.5f), specAlphas = floatArrayOf(0.3f, 0f),
    ), SpecText(16f, 700), "Save").apply { radius = 22f }
    private val buttons = listOf(cancelBtn, deleteBtn, saveBtn)

    private val hourUp = CircleButton(context).apply { iconSize = 20f; icon = Icon.PLUS }
    private val hourDown = CircleButton(context).apply { iconSize = 20f; icon = Icon.MINUS }
    private val minUp = CircleButton(context).apply { iconSize = 20f; icon = Icon.PLUS }
    private val minDown = CircleButton(context).apply { iconSize = 20f; icon = Icon.MINUS }
    private val steppers = listOf(hourUp, hourDown, minUp, minDown)
    private val am = Chip(context, "AM", 15f)
    private val pm = Chip(context, "PM", 15f)
    private val dayChips = listOf("M", "T", "W", "T", "F", "S", "S").map { Chip(context, it, 16f) }
    private val readyChips = LeaveBy.READY_CHOICES.map { Chip(context, "$it", 15f) }
    private val leaveSwitch = SwitchView(context)
    private val skipSwitch = SwitchView(context)
    private val is24 = AlarmFormat.is24(context)

    init {
        setWillNotDraw(false)
        clipChildren = false
        // Header buttons, right aligned in the 28..932 sheet.
        val sw = saveBtn.wrapWidth(22f, 22f); val cw = cancelBtn.wrapWidth(20f, 20f); val dw = deleteBtn.wrapWidth(20f, 20f)
        place(saveBtn, 908f - sw, 30f, sw, 44)
        place(cancelBtn, 908f - sw - 12f - cw, 30f, cw, 44)
        place(deleteBtn, 908f - sw - 12f - cw - 12f - dw, 30f, dw, 44)
        buttons.forEach { it.backdropOrigin = this }
        // Time: hour column centred at x 120, minute column at x 270; digits 84 px.
        place(hourUp, 120f - 22f, 96f, 44, 44); place(hourDown, 120f - 22f, 262f, 44, 44)
        place(minUp, 270f - 22f, 96f, 44, 44); place(minDown, 270f - 22f, 262f, 44, 44)
        place(am, 352f, 156f, 64, 36); place(pm, 352f, 202f, 64, 36)
        if (is24) { am.visibility = GONE; pm.visibility = GONE }
        dayChips.forEachIndexed { i, chip -> place(chip, 56f + i * 54f, 340f, 44, 44) }
        // Right column: two switch rows and the ready-time chips.
        place(leaveSwitch, 848f, 104f, 58, 34)
        readyChips.forEachIndexed { i, chip -> place(chip, 504f + i * 76f, 186f, 66, 38) }
        place(skipSwitch, 848f, 262f, 58, 34)

        cancelBtn.onTap = { onCancel?.invoke() }
        saveBtn.onTap = { onSave?.invoke(cur) }
        deleteBtn.onTap = {
            if (confirmDelete) onDelete?.invoke(cur.id)
            else { confirmDelete = true; deleteBtn.label = "Tap again"; deleteBtn.color = VesperColors.RED; deleteBtn.invalidate() }
        }
        hourUp.onTap = { setTime(cur.hour + 1, cur.minute) }
        hourDown.onTap = { setTime(cur.hour - 1, cur.minute) }
        minUp.onTap = { setTime(cur.hour, cur.minute + 5) }
        minDown.onTap = { setTime(cur.hour, cur.minute - 5) }
        am.onTap = { if (cur.hour >= 12) setTime(cur.hour - 12, cur.minute) }
        pm.onTap = { if (cur.hour < 12) setTime(cur.hour + 12, cur.minute) }
        dayChips.forEachIndexed { i, chip ->
            chip.onTap = {
                val d = DayOfWeek.of(i + 1)
                cur = cur.copy(days = if (d in cur.days) cur.days - d else cur.days + d)
                sync()
            }
        }
        readyChips.forEachIndexed { i, chip -> chip.onTap = { cur = cur.copy(readyMinutes = LeaveBy.READY_CHOICES[i]); sync() } }
        leaveSwitch.onChange = { cur = cur.copy(leaveBy = it); sync() }
        skipSwitch.onChange = { cur = cur.copy(skipHolidays = it); sync() }
        // The sheet swallows touches so nothing underneath reacts.
        isClickable = true
        setNight(false)
    }

    fun setNight(n: Boolean) {
        night = n
        panel.night = n
        buttons.forEach { it.glass.night = n; it.color = if (n) Night.COLOR else Color.WHITE; it.invalidate() }
        saveBtn.color = if (n) Night.COLOR else Color.WHITE
        steppers.forEach { s ->
            val icon = s.icon
            if (n) s.set(withAlpha(Night.COLOR, 0.2f), Night.COLOR, icon) else s.set(withAlpha(Color.WHITE, 0.16f), Color.WHITE, icon)
        }
        listOf(am, pm).plus(dayChips).plus(readyChips).forEach { it.night = n }
        leaveSwitch.night = n; skipSwitch.night = n
        invalidate()
    }

    fun open(a: Alarm?) {
        isNew = a == null
        cur = a ?: Alarm("alarm-" + System.currentTimeMillis(), 7, 0, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        confirmDelete = false
        deleteBtn.label = "Delete"; deleteBtn.color = if (night) Night.COLOR else Color.WHITE
        deleteBtn.visibility = if (isNew) GONE else VISIBLE
        sync()
    }

    private fun setTime(h: Int, m: Int) {
        var minutes = ((h * 60 + m) % 1440 + 1440) % 1440
        // Minutes wrap inside the hour (stepping 55 + 5 doesn't change the hour); hours wrap inside the day.
        if (m != cur.minute && h == cur.hour) minutes = cur.hour * 60 + ((m % 60) + 60) % 60
        cur = cur.copy(hour = minutes / 60, minute = minutes % 60)
        sync()
    }

    /** Pushes [cur] into the controls and redraws. */
    private fun sync() {
        dayChips.forEachIndexed { i, chip -> chip.chosen = DayOfWeek.of(i + 1) in cur.days }
        readyChips.forEachIndexed { i, chip -> chip.chosen = LeaveBy.READY_CHOICES[i] == cur.readyMinutes; chip.visibility = if (cur.leaveBy) VISIBLE else GONE }
        am.chosen = cur.hour < 12; pm.chosen = cur.hour >= 12
        leaveSwitch.on = cur.leaveBy; skipSwitch.on = cur.skipHolidays
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        // Scrim over the list, then the sheet.
        c.drawColor(withAlpha(Color.BLACK, if (night) 0.7f else 0.5f))
        c.save(); c.translate(28f, 16f)
        panel.draw(c, 904f, 448f, 34f, 28f, 16f)
        c.restore()
        val white = Night.map(Color.WHITE, night)
        val soft = Night.map(withAlpha(Color.WHITE, 0.7f), night)
        head.draw(c, if (isNew) "New alarm" else "Edit alarm", 56f, head.baselineIn(30f, 44f), white)
        // Time.
        val h12 = if (is24) cur.hour else if (cur.hour % 12 == 0) 12 else cur.hour % 12
        val hs = if (is24) "%02d".format(h12) else "$h12"
        digits.draw(c, hs, 120f - digits.width(hs) / 2, digits.baselineIn(150f, 100f), white)
        colon.draw(c, ":", 195f - colon.width(":") / 2, colon.baselineIn(150f, 100f), soft)
        val ms = "%02d".format(cur.minute)
        digits.draw(c, ms, 270f - digits.width(ms) / 2, digits.baselineIn(150f, 100f), white)
        cap.draw(c, "Repeat", 56f, cap.baselineIn(318f, 18f), soft)
        val days = AlarmsScreen.daysText(cur.days)
        cap.draw(c, days, 56f, cap.baselineIn(396f, 18f), Night.map(withAlpha(Color.WHITE, 0.86f), night))
        // Options.
        val line = Night.map(withAlpha(Color.WHITE, 0.14f), night)
        val lp = linePaint.apply { color = line; strokeWidth = 1f }
        c.drawLine(480f, 82f, 904f, 82f, lp)
        rowT.draw(c, "Leave-by", 480f, rowT.baselineIn(100f), white)
        rowS.draw(c, "Ring earlier when your first event with a", 480f, rowS.baselineIn(124f), soft)
        rowS.draw(c, "place needs it. Never later than set.", 480f, rowS.baselineIn(142f), soft)
        if (cur.leaveBy) {
            cap.draw(c, "Getting ready takes (min)", 480f, cap.baselineIn(166f, 18f), soft)
        }
        c.drawLine(480f, 240f, 904f, 240f, lp)
        rowT.draw(c, "Skip holidays", 480f, rowT.baselineIn(258f), white)
        rowS.draw(c, "US holidays and all-day vacation,", 480f, rowS.baselineIn(282f), soft)
        rowS.draw(c, if (cur.days.isEmpty()) "day off or PTO. Repeating alarms only." else "day off or PTO.", 480f, rowS.baselineIn(300f), soft)
        c.drawLine(480f, 332f, 904f, 332f, lp)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)

}
