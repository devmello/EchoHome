package com.echobedside.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.View
import com.echobedside.R
import java.util.Calendar
import kotlin.math.max

/**
 * StandBy "Digital" face, ported 1:1 from design/Digital.dc.html (960×480 device px).
 *
 * Layout (CSS flex row, padding 0 44 0 40, gap 28, items centred):
 *   [ time: flex 1, 252px/600, line-height .8, tracking -.045em ] [ complications: 168px column ]
 *
 * Draws only when the minute or the date changes (the owner calls [refresh] on TIME_TICK),
 * so between minutes it costs no frames at all.
 */
class DigitalClockView(context: Context) : View(context) {

    /** Data for the right-hand column. Weather and alarm are the design's sample values until the
     *  Home Assistant link (milestone 5) and the alarm engine (milestone 4) exist. */
    data class Complications(
        val tempNow: String = "54°",
        val highLow: String = "H:61° L:47°",
        val nextAlarm: String? = "7:00 AM",
    )

    var color: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    /** Night mode: everything #BE180E, no alpha steps (NOTES §3.3). Dimming is done by the window. */
    var night: Boolean = false
        set(v) { field = v; invalidate() }
    var showComplications: Boolean = true
        set(v) { field = v; invalidate() }
    var complications: Complications = Complications()
        set(v) { field = v; invalidate() }

    // --- text styles straight from the spec ---
    private val timeText = SpecText(252f, 600, -0.045f)
    private val dayShortText = SpecText(17f, 700, 0.06f)
    private val dayNumText = SpecText(52f, 600, -0.03f)
    private val tempText = SpecText(36f, 600, -0.03f)
    private val highLowText = SpecText(15f, 500)
    private val alarmText = SpecText(20f, 600, -0.02f)

    private val weatherIcon: Drawable = context.getDrawable(R.drawable.ic_cx_weather)!!.mutate()
    private val alarmIcon: Drawable = context.getDrawable(R.drawable.ic_cx_alarm)!!.mutate()

    // --- current values, rebuilt in refresh() (never in onDraw) ---
    private var hh = ""
    private var mm = ""
    private var dayShort = ""
    private var dayNum = ""
    private val cal = Calendar.getInstance()

    init {
        setBackgroundColor(Color.BLACK)
        refresh()
    }

    /** Re-reads the clock. Call on TIME_TICK, TIME_SET, TIMEZONE_CHANGED and on resume. */
    fun refresh() {
        cal.timeZone = java.util.TimeZone.getDefault()
        cal.timeInMillis = System.currentTimeMillis()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val newHh = if (DateFormat.is24HourFormat(context)) h.toString().padStart(2, '0')
                    else (if (h % 12 == 0) 12 else h % 12).toString()
        val newMm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val newDayShort = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val newDayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
        if (newHh != hh || newMm != mm || newDayShort != dayShort || newDayNum != dayNum) {
            hh = newHh; mm = newMm; dayShort = newDayShort; dayNum = newDayNum
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Scale the 960×480 spec onto whatever the view is (1:1 on the Echo).
        val s = width / W
        canvas.save()
        canvas.scale(s, height / H)

        val fg = if (night) NIGHT else color
        val fg2 = if (night) NIGHT else withAlpha(fg, 0.92f)
        val fg3 = if (night) NIGHT else withAlpha(fg, 0.6f)

        drawTime(canvas, fg)
        if (showComplications) drawComplications(canvas, fg, fg2, fg3)

        canvas.restore()
    }

    private fun drawTime(canvas: Canvas, fg: Int) {
        val t = timeText
        val colonGap = 0.01f * t.size              // margin: 0 0.01em
        val colonLift = -0.05f * t.size            // translateY(-0.05em)
        val total = t.width(hh) + colonGap + t.width(":") + colonGap + t.width(mm)

        // Time area: from the left padding to the complications column (or the right padding).
        val areaLeft = PAD_L
        val areaRight = if (showComplications) W - PAD_R - COL_W - GAP else W - PAD_R
        var x = if (showComplications) areaLeft else areaLeft + (areaRight - areaLeft - total) / 2f

        // Each span is a flex item with a 0.8em line box, centred in the 480px row.
        val lineH = 0.8f * t.size
        val baseline = t.baselineIn(H / 2f - lineH / 2f, lineH)
        x = t.draw(canvas, hh, x, baseline, fg)
        x = t.draw(canvas, ":", x + colonGap, baseline + colonLift, fg)
        t.draw(canvas, mm, x + colonGap, baseline, fg)
    }

    private fun drawComplications(canvas: Canvas, fg: Int, fg2: Int, fg3: Int) {
        val c = complications
        val x = W - PAD_R - COL_W

        // Block heights (CSS: gap 2 / gap 6 inside blocks, gap 30 between blocks).
        val dateH = dayShortText.normalLineHeight + 2f + dayNumText.size // dayNum has line-height 1
        val weatherRowH = max(30f, tempText.normalLineHeight)
        val weatherH = weatherRowH + 6f + highLowText.normalLineHeight
        val alarmRowH = max(20f, alarmText.normalLineHeight)
        val blocks = if (c.nextAlarm != null) 3 else 2
        val colH = dateH + 30f + weatherH + (if (blocks == 3) 30f + alarmRowH else 0f)
        var y = H / 2f - colH / 2f

        // Date: "SUN" / "22"
        dayShortText.draw(canvas, dayShort, x, dayShortText.baselineIn(y), fg)
        y += dayShortText.normalLineHeight + 2f
        dayNumText.draw(canvas, dayNum, x, dayNumText.baselineIn(y, dayNumText.size), fg2)
        y += dayNumText.size + 30f

        // Weather: icon + "54°", then "H:61° L:47°"
        drawIcon(canvas, weatherIcon, x, y + (weatherRowH - 30f) / 2f, 30f, fg2)
        tempText.draw(canvas, c.tempNow, x + 30f + 10f,
            tempText.baselineIn(y + (weatherRowH - tempText.normalLineHeight) / 2f), fg2)
        y += weatherRowH + 6f
        highLowText.draw(canvas, c.highLow, x, highLowText.baselineIn(y), fg3)
        y += highLowText.normalLineHeight + 30f

        // Next alarm: icon + "7:00 AM"
        c.nextAlarm?.let { label ->
            drawIcon(canvas, alarmIcon, x, y + (alarmRowH - 20f) / 2f, 20f, fg2)
            alarmText.draw(canvas, label, x + 20f + 8f,
                alarmText.baselineIn(y + (alarmRowH - alarmText.normalLineHeight) / 2f), fg2)
        }
    }

    private fun drawIcon(canvas: Canvas, d: Drawable, x: Float, y: Float, size: Float, tint: Int) {
        canvas.save()
        canvas.translate(x, y)
        d.setBounds(0, 0, size.toInt(), size.toInt())
        d.setTint(tint)
        d.draw(canvas)
        canvas.restore()
    }

    companion object {
        const val W = 960f
        const val H = 480f
        const val PAD_L = 40f
        const val PAD_R = 44f
        const val GAP = 28f
        const val COL_W = 168f
        val NIGHT = Color.parseColor("#BE180E")
        private val DAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

        /** Accent swatches from the design (Apple system dark colours). */
        val SWATCHES = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFFF453A.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(),
            0xFF30D158.toInt(), 0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(),
        )

        fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255f + 0.5f).toInt() shl 24)
    }
}
