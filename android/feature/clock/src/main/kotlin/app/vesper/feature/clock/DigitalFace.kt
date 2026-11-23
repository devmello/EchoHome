package app.vesper.feature.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.FaceView
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.Weather
import app.vesper.core.model.observeWhileAttached
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max

/**
 * StandBy "Digital" face, ported 1:1 from design/Digital.dc.html (960×480 device px).
 *
 * Layout (CSS flex row, padding 0 44 0 40, gap 28, items centred):
 *   [ time: flex 1, 252px/600, line-height .8, tracking -.045em ] [ complications: 168px column ]
 *
 * Draws only when the minute, the date or a complication changes, so between minutes it costs
 * no frames at all. Weather comes from [VesperServices.weather], the alarm from [VesperServices.alarms];
 * a complication with no data is left out.
 */
class DigitalFace(context: Context, private val services: VesperServices) : View(context), FaceView {

    override val view: View get() = this

    private var config = FaceConfig()
    private var weather: Weather? = null
    private var nextAlarm: NextAlarm? = null

    // --- text styles straight from the spec ---
    private val timeText = SpecText(252f, 600, -0.045f)
    private val dayShortText = SpecText(17f, 700, 0.06f)
    private val dayNumText = SpecText(52f, 600, -0.03f)
    private val tempText = SpecText(36f, 600, -0.03f)
    private val highLowText = SpecText(15f, 500)
    private val airText = SpecText(13f, 600, 0.02f)
    private val alarmText = SpecText(20f, 600, -0.02f)

    private val weatherIcon: Drawable = context.getDrawable(R.drawable.ic_cx_weather)!!.mutate()
    private val alarmIcon: Drawable = context.getDrawable(R.drawable.ic_cx_alarm)!!.mutate()

    // --- current strings, rebuilt in refresh() (never in onDraw) ---
    private var hh = ""
    private var mm = ""
    private var dayShort = ""
    private var dayNum = ""
    private var tempNow = ""
    private var highLow = ""
    private var airLine = ""
    private var alarmLabel: String? = null
    private val cal = Calendar.getInstance()

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(services.weather.weather) { weather = it; onTimeChanged() }
        observeWhileAttached(services.alarms.next) { nextAlarm = it; onTimeChanged() }
        onTimeChanged()
    }

    override fun setConfig(config: FaceConfig) {
        if (config != this.config) { this.config = config; invalidate() }
    }

    /** Re-reads the clock and complications; invalidates only if a string changed. */
    override fun onTimeChanged() {
        cal.timeZone = TimeZone.getDefault()
        cal.timeInMillis = System.currentTimeMillis()
        val is24 = DateFormat.is24HourFormat(context)
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val newHh = if (is24) h.toString().padStart(2, '0') else (if (h % 12 == 0) 12 else h % 12).toString()
        val newMm = cal.get(Calendar.MINUTE).toString().padStart(2, '0')
        val newDayShort = DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val newDayNum = cal.get(Calendar.DAY_OF_MONTH).toString()
        val w = weather
        val newTemp = w?.let { "${it.temperature}°" } ?: ""
        val newHl = w?.let { "H:${it.high}° L:${it.low}°" } ?: ""
        val newAir = airLine(w)
        val newAlarm = nextAlarm?.let { formatAlarm(it.triggerAtMillis, is24) }
        if (newHh != hh || newMm != mm || newDayShort != dayShort || newDayNum != dayNum ||
            newTemp != tempNow || newHl != highLow || newAir != airLine || newAlarm != alarmLabel) {
            hh = newHh; mm = newMm; dayShort = newDayShort; dayNum = newDayNum
            tempNow = newTemp; highLow = newHl; airLine = newAir; alarmLabel = newAlarm
            invalidate()
        }
    }

    /** "AQI 42 · UV 3": only the parts that exist (UV is left out when it's 0, e.g. at night). */
    private fun airLine(w: Weather?): String = formatAir(w?.air)

    private fun formatAlarm(at: Long, is24: Boolean): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE).toString().padStart(2, '0')
        return if (is24) "${h.toString().padStart(2, '0')}:$m" else "${if (h % 12 == 0) 12 else h % 12}:$m ${if (h < 12) "AM" else "PM"}"
    }

    override fun onDraw(canvas: Canvas) {
        // Scale the 960×480 spec onto whatever the view is (1:1 on the Echo).
        canvas.save()
        canvas.scale(width / W, height / H)

        val night = config.night
        val fg = if (night) Night.COLOR else config.color
        val fg2 = if (night) Night.COLOR else withAlpha(fg, 0.92f)
        val fg3 = if (night) Night.COLOR else withAlpha(fg, 0.6f)

        drawTime(canvas, fg)
        if (config.complications) drawComplications(canvas, fg, fg2, fg3)

        canvas.restore()
    }

    private fun drawTime(canvas: Canvas, fg: Int) {
        val t = timeText
        val colonGap = 0.01f * t.size              // margin: 0 0.01em
        val colonLift = -0.05f * t.size            // translateY(-0.05em)
        val total = t.width(hh) + colonGap + t.width(":") + colonGap + t.width(mm)

        // Time area: from the left padding to the complications column (or the right padding).
        val areaLeft = PAD_L
        val areaRight = if (config.complications) W - PAD_R - COL_W - GAP else W - PAD_R
        var x = if (config.complications) areaLeft else areaLeft + (areaRight - areaLeft - total) / 2f

        // Each span is a flex item with a 0.8em line box, centred in the 480px row.
        val lineH = 0.8f * t.size
        val baseline = t.baselineIn(H / 2f - lineH / 2f, lineH)
        x = t.draw(canvas, hh, x, baseline, fg)
        x = t.draw(canvas, ":", x + colonGap, baseline + colonLift, fg)
        t.draw(canvas, mm, x + colonGap, baseline, fg)
    }

    private fun drawComplications(canvas: Canvas, fg: Int, fg2: Int, fg3: Int) {
        val x = W - PAD_R - COL_W
        val hasWeather = tempNow.isNotEmpty()
        val alarm = alarmLabel

        // Block heights (CSS: gap 2 / gap 6 inside blocks, gap 30 between blocks).
        val dateH = dayShortText.normalLineHeight + 2f + dayNumText.size // dayNum has line-height 1
        val weatherRowH = max(30f, tempText.normalLineHeight)
        val weatherH = weatherRowH + 6f + highLowText.normalLineHeight + (if (airLine.isNotEmpty()) 4f + airText.normalLineHeight else 0f)
        val alarmRowH = max(20f, alarmText.normalLineHeight)
        val colH = dateH + (if (hasWeather) 30f + weatherH else 0f) + (if (alarm != null) 30f + alarmRowH else 0f)
        var y = H / 2f - colH / 2f

        // Date: "SUN" / "22"
        dayShortText.draw(canvas, dayShort, x, dayShortText.baselineIn(y), fg)
        y += dayShortText.normalLineHeight + 2f
        dayNumText.draw(canvas, dayNum, x, dayNumText.baselineIn(y, dayNumText.size), fg2)
        y += dayNumText.size + 30f

        // Weather: icon + "54°", then "H:61° L:47°"
        if (hasWeather) {
            drawIcon(canvas, weatherIcon, x, y + (weatherRowH - 30f) / 2f, 30f, fg2)
            tempText.draw(canvas, tempNow, x + 30f + 10f,
                tempText.baselineIn(y + (weatherRowH - tempText.normalLineHeight) / 2f), fg2)
            y += weatherRowH + 6f
            highLowText.draw(canvas, highLow, x, highLowText.baselineIn(y), fg3)
            y += highLowText.normalLineHeight
            if (airLine.isNotEmpty()) {
                y += 4f
                airText.draw(canvas, airLine, x, airText.baselineIn(y), fg3)
                y += airText.normalLineHeight
            }
            y += 30f
        }

        // Next alarm: icon + "7:00 AM"
        if (alarm != null) {
            drawIcon(canvas, alarmIcon, x, y + (alarmRowH - 20f) / 2f, 20f, fg2)
            alarmText.draw(canvas, alarm, x + 20f + 8f,
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
        private val DAYS = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    }
}

/** "AQI 42 · UV 3" from [Weather.air]; empty when neither is known (UV below 1 is left out). */
fun formatAir(air: app.vesper.core.model.AirQuality?): String {
    if (air == null) return ""
    val parts = ArrayList<String>(2)
    air.usAqi?.let { parts += "AQI $it" }
    air.uvIndex?.let { if (it >= 0.5f) parts += "UV ${Math.round(it)}" }
    return parts.joinToString(" · ")
}
