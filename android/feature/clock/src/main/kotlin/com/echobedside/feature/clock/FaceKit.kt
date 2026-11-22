package com.echobedside.feature.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.View
import com.echobedside.core.design.SpecText
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FaceConfig
import com.echobedside.core.model.FaceView
import java.util.Calendar
import java.util.TimeZone

/**
 * Base for the single-View faces: holds the config, scales the 960×480 spec onto the view
 * (1:1 on the Echo) and redraws only when [refresh] says something visible changed.
 */
abstract class SpecFace(context: Context, protected val services: EchoServices) : View(context), FaceView {
    override val view: View get() = this
    protected var config = FaceConfig()
        private set
    protected val cal: Calendar = Calendar.getInstance()
    protected val is24: Boolean get() = DateFormat.is24HourFormat(context)

    init { setBackgroundColor(Color.BLACK) }

    override fun setConfig(config: FaceConfig) {
        if (config == this.config) return
        val old = this.config
        this.config = config
        onConfigChanged(old)
        refresh(force = true)
    }

    protected open fun onConfigChanged(old: FaceConfig) {}

    override fun onTimeChanged() = refresh(force = false)

    /** Re-reads the time and data; returns true if the face must redraw. Never allocates in onDraw. */
    protected abstract fun update(): Boolean

    protected fun refresh(force: Boolean) {
        cal.timeZone = TimeZone.getDefault()
        cal.timeInMillis = System.currentTimeMillis()
        if (update() || force) invalidate()
    }

    final override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        drawSpec(canvas)
        canvas.restore()
    }

    protected abstract fun drawSpec(canvas: Canvas)

    companion object {
        const val W = 960f
        const val H = 480f
    }
}

/** Time strings shared by the faces (12 h without AM/PM, as in the designs, or 24 h). */
object TimeText {
    val DAYS_SHORT = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December")

    fun hour(cal: Calendar, is24: Boolean): String {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return if (is24) h.toString().padStart(2, '0') else (if (h % 12 == 0) 12 else h % 12).toString()
    }

    fun minute(cal: Calendar): String = cal.get(Calendar.MINUTE).toString().padStart(2, '0')

    /** "9:41" (12 h) or "09:41" (24 h). */
    fun time(cal: Calendar, is24: Boolean) = hour(cal, is24) + ":" + minute(cal)

    /** "7:00 AM" / "07:00"; [amPm] false gives "7:00" (Analog's 3 o'clock label). */
    fun clock(millis: Long, is24: Boolean, amPm: Boolean = true, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        if (is24) return "${h.toString().padStart(2, '0')}:$m"
        val base = "${if (h % 12 == 0) 12 else h % 12}:$m"
        return if (amPm) "$base ${if (h < 12) "AM" else "PM"}" else base
    }

    /** "Sunday, November 22". */
    fun dateLine(cal: Calendar) = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1] + ", " + MONTHS[cal.get(Calendar.MONTH)] + " " + cal.get(Calendar.DAY_OF_MONTH)
}

/** Text helpers on top of [SpecText]. */
fun SpecText.drawCentered(canvas: Canvas, s: String, cx: Float, baseline: Float, color: Int): Float =
    draw(canvas, s, cx - width(s) / 2f, baseline, color)

/** Draws [d] at (x, y) with [size] px, tinted. */
fun drawIcon(canvas: Canvas, d: Drawable, x: Float, y: Float, size: Float, tint: Int) {
    canvas.save()
    canvas.translate(x, y)
    d.setBounds(0, 0, size.toInt(), size.toInt())
    d.setTint(tint) // SRC_IN: the tint's alpha applies too
    d.draw(canvas)
    canvas.restore()
}

fun Context.icon(id: Int): Drawable = getDrawable(id)!!.mutate()
