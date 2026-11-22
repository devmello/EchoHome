package com.echobedside.feature.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.CalendarEvent
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import java.util.Calendar
import kotlin.math.max

/**
 * Calendar face, from design/Calendar.dc.html: big day and date with a mini month on the left,
 * the agenda for today and tomorrow on the right with a live "now" line, past events dimmed and
 * an "in N min" badge for the next hour. Data: [EchoServices.calendar]. Redraws on the minute.
 */
class CalendarFace(context: Context, services: EchoServices) : SpecFace(context, services) {

    private val dayNameText = SpecText(22f, 800, 0.06f)
    private val dayNumText = SpecText(150f, 600, -0.05f)
    private val monthText = SpecText(24f, 600)
    private val dowText = SpecText(11f, 700)
    private val cellText = SpecText(12f, 500)
    private val cellTodayText = SpecText(12f, 800)
    private val headerText = SpecText(15f, 800, 0.06f)
    private val nowText = SpecText(13f, 700)
    private val timeText = SpecText(17f, 600)
    private val titleText = SpecText(22f, 700, -0.015f)
    private val whereText = SpecText(15f, 500)
    private val badgeText = SpecText(13f, 800)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var events: List<CalendarEvent> = emptyList()
    private var minuteKey = -1L

    private sealed interface Row
    private class Header(val label: String, val gap: Float) : Row
    private class NowLine(val label: String) : Row
    private class Ev(val time: String, val title: String, val where: String, val color: Int, val past: Boolean, val soon: String?) : Row

    private var rows: List<Row> = emptyList()
    private var dayName = ""
    private var dayNum = ""
    private var monthName = ""
    private var firstDow = 0
    private var daysInMonth = 30
    private var today = 1

    init {
        observeWhileAttached(services.calendar.events) { events = it; minuteKey = -1; refresh(force = false) }
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val key = now / 60_000
        if (key == minuteKey) return false
        minuteKey = key
        dayName = TimeText.DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1].uppercase()
        dayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
        monthName = TimeText.MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.YEAR)
        today = cal.get(Calendar.DAY_OF_MONTH)
        daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        firstDow = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }.get(Calendar.DAY_OF_WEEK) - 1

        val startToday = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val startTomorrow = (cal.clone() as Calendar).apply { timeInMillis = startToday; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        val startAfter = (cal.clone() as Calendar).apply { timeInMillis = startToday; add(Calendar.DAY_OF_YEAR, 2) }.timeInMillis
        val todays = events.filter { it.startMillis < startTomorrow && it.endMillis > startToday }.sortedBy { it.startMillis }
        val tomorrows = events.filter { it.startMillis in startTomorrow until startAfter }.sortedBy { it.startMillis }

        val out = ArrayList<Row>()
        out += Header("TODAY", 0f)
        var placedNow = false
        val nowLabel = TimeText.clock(now, is24)
        for (e in todays) {
            if (!placedNow && now < e.startMillis) { out += NowLine(nowLabel); placedNow = true }
            val until = ((e.startMillis - now) / 60_000).toInt()
            out += Ev(timeLabel(e), e.title, e.subtitle, e.color, e.endMillis < now, if (e.startMillis > now && until in 0..60) "in ${max(1, until)} min" else null)
        }
        if (!placedNow) out += NowLine(nowLabel)
        if (tomorrows.isNotEmpty()) {
            out += Header("TOMORROW", 8f)
            for (e in tomorrows) out += Ev(timeLabel(e), e.title, e.subtitle, e.color, false, null)
        }
        rows = out
        return true
    }

    private fun timeLabel(e: CalendarEvent) = if (e.allDay) "All day" else TimeText.clock(e.startMillis, is24)

    override fun drawSpec(canvas: Canvas) {
        val night = config.night
        val accent = if (night) Night.COLOR else config.color
        val fg = if (night) accent else Color.WHITE
        val fg2 = if (night) accent else withAlpha(Color.WHITE, 0.68f)
        val fg3 = if (night) accent else withAlpha(Color.WHITE, 0.5f)

        // --- left: day, date, month, mini calendar (left 48, top 36, 280 wide) ---
        var y = 36f
        dayNameText.draw(canvas, dayName, 48f, dayNameText.baselineIn(y), accent)
        y += dayNameText.normalLineHeight
        val numH = 0.9f * dayNumText.size
        dayNumText.draw(canvas, dayNum, 48f - 6f, dayNumText.baselineIn(y, numH), fg)
        y += numH + 4f
        monthText.draw(canvas, monthName, 48f, monthText.baselineIn(y), fg2)
        y += monthText.normalLineHeight + 18f
        val colW = 252f / 7f
        for (i in 0 until 7) {
            dowText.drawCentered(canvas, DOW[i], 48f + colW * (i + 0.5f), dowText.baselineIn(y), fg3) // top-aligned in its 20 px row
        }
        y += 20f + 2f
        var cell = firstDow
        for (d in 1..daysInMonth) {
            val col = cell % 7; val row = cell / 7
            val cx = 48f + colW * (col + 0.5f)
            val top = y + row * (22f + 2f)
            val isToday = d == today
            if (isToday) { fill.color = accent; canvas.drawCircle(cx, top + 11f, 11f, fill) }
            val t = if (isToday) cellTodayText else cellText
            t.drawCentered(canvas, d.toString(), cx, t.baselineIn(top, 22f),
                if (isToday) Color.BLACK else if (night) accent else withAlpha(Color.WHITE, 0.85f))
            cell++
        }

        // --- right: agenda (380..916, from 40, gap 10, until 450) ---
        val l = 380f; val r = 960f - 44f
        y = 40f
        for ((i, row) in rows.withIndex()) {
            if (i > 0) y += 10f
            when (row) {
                is Header -> {
                    y += row.gap
                    if (y + headerText.normalLineHeight > 450f) return
                    headerText.draw(canvas, row.label, l, headerText.baselineIn(y), fg3)
                    y += headerText.normalLineHeight
                }
                is NowLine -> {
                    fill.color = accent
                    canvas.drawCircle(l + 5f, y + 5f, 5f, fill)
                    val lw = nowText.width(row.label)
                    canvas.drawRect(l + 10f + 8f, y + 4f, r - lw - 8f, y + 6f, fill)
                    nowText.draw(canvas, row.label, r - lw, nowText.baselineIn(y + 5f - nowText.normalLineHeight / 2), accent)
                    y += 10f
                }
                is Ev -> {
                    val h = titleText.normalLineHeight + whereText.normalLineHeight
                    if (y + h > 450f) return
                    val a = if (row.past) 0.4f else 1f
                    fill.color = withAlpha(if (night) accent else row.color, a * Color.alpha(if (night) accent else row.color) / 255f)
                    rect.set(l, y, l + 5f, y + h)
                    canvas.drawRoundRect(rect, 2.5f, 2.5f, fill)
                    val tx = l + 5f + 14f
                    timeText.draw(canvas, row.time, tx, timeText.baselineIn(y + 2f), fade(fg2, a))
                    val textX = tx + 92f + 14f
                    var badgeW = 0f
                    row.soon?.let { s ->
                        badgeW = badgeText.width(s) + 24f
                        rect.set(r - badgeW, y + h / 2 - 15f, r, y + h / 2 + 15f)
                        fill.color = accent
                        canvas.drawRoundRect(rect, 15f, 15f, fill)
                        badgeText.draw(canvas, s, r - badgeW + 12f, badgeText.baselineIn(y + h / 2 - 15f, 30f), Color.BLACK)
                        badgeW += 14f
                    }
                    val maxW = r - badgeW - textX
                    titleText.draw(canvas, ellipsize(titleText, row.title, maxW), textX, titleText.baselineIn(y), fade(fg, a))
                    whereText.draw(canvas, ellipsize(whereText, row.where, maxW), textX, whereText.baselineIn(y + titleText.normalLineHeight), fade(fg2, a))
                    y += h
                }
            }
        }
    }

    private fun fade(c: Int, a: Float) = withAlpha(c, Color.alpha(c) / 255f * a)

    private fun ellipsize(t: SpecText, s: String, maxW: Float): String {
        if (t.width(s) <= maxW) return s
        var n = s.length
        while (n > 1 && t.width(s.substring(0, n) + "…") > maxW) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    companion object {
        val DEFAULT_COLOR = 0xFFFF453A.toInt()
        val COLORS = listOf(0xFFFF453A, 0xFFFF9F0A, 0xFF30D158, 0xFF0A84FF, 0xFFBF5AF2).map { it.toInt() }
        private val DOW = arrayOf("S", "M", "T", "W", "T", "F", "S")
    }
}
