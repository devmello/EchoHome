package app.vesper.feature.clock

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.VesperServices
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.observeWhileAttached
import java.util.Calendar
import java.util.TimeZone

/**
 * The far layout (NOTES §6.5 item 2): what StandBy shows when the camera says whoever's looking is
 * across the room (`SystemState.viewDistance`). There's no artboard for it; it's derived from the
 * StandBy designs: the same type, larger, fewer elements.
 *
 *  - Faces that are already one big clock (Analog, Float, Liquid Glass) stay, with their small
 *    details faded out ([app.vesper.core.model.FaceView.setFarAmount]).
 *  - The others (Digital, World, Solar, Weather, Calendar) give way to [FarClockView]: the time
 *    as large as the screen allows in the face's type and colour, and one key fact under it.
 *  - Faces from other features (photos) have no far layout.
 */
object FarLayout {
    enum class Style { NONE, OWN, BIG_CLOCK }

    private val OWN = setOf("analog", "float", "glass")
    /** The face's time weight (Digital.dc.html 600, Solar.dc.html 400). */
    private val BIG = mapOf("digital" to 600, "world" to 600, "solar" to 400, "weather" to 600, "calendar" to 600)

    fun styleOf(faceId: String): Style = when (faceId) {
        in OWN -> Style.OWN
        in BIG -> Style.BIG_CLOCK
        else -> Style.NONE
    }

    fun weightOf(faceId: String): Int = BIG[faceId] ?: 600

    /** Far transition: Motion.EASE over this long. */
    const val TRANSITION_MS = 600L
    /** The face grows a little as it fades out, the big clock grows in from this scale. */
    const val FACE_GROW = 0.06f
    const val FAR_FROM = 0.9f
}

/** The one key fact under the far clock: the most useful thing that reads from across the room. */
object FarFact {
    enum class Kind { ALARM, RAIN, WEATHER, DATE }
    data class Fact(val kind: Kind, val text: String, val condition: WeatherCondition? = null, val day: Boolean = true)

    /** An alarm counts when it rings within this (the night's alarm, a nap). */
    const val ALARM_WITHIN_MS = 12 * 3_600_000L
    /** Rain counts when it starts within this many minutes (from the 15-min nowcast). */
    const val RAIN_WITHIN_MIN = 60

    /** Alarm within 12 h, else rain within the hour, else the weather now, else the date. */
    fun pick(now: Long, alarm: NextAlarm?, weather: Weather?, is24: Boolean, zone: TimeZone = TimeZone.getDefault()): Fact {
        val at = alarm?.triggerAtMillis
        if (at != null && at > now && at - now <= ALARM_WITHIN_MS) return Fact(Kind.ALARM, TimeText.clock(at, is24, zone = zone))
        if (weather != null) {
            val rain = weather.nowcast?.minutesUntilRain(now)
            val raining = weather.condition in RAINY
            if (rain != null && rain <= RAIN_WITHIN_MIN && !raining)
                return Fact(Kind.RAIN, if (rain <= 0) "Rain now" else "Rain in $rain min", WeatherCondition.RAIN)
            val label = conditionLabel(weather.condition, !weather.isNight)
            return Fact(Kind.WEATHER, "${weather.temperature}°" + if (label.isEmpty()) "" else "  $label", weather.condition, !weather.isNight)
        }
        val c = Calendar.getInstance(zone).apply { timeInMillis = now }
        return Fact(Kind.DATE, TimeText.DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH))
    }

    private val RAINY = setOf(WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING)

    fun conditionLabel(c: WeatherCondition, day: Boolean) = when (c) {
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
}

/**
 * The big far clock, 960×480 spec px: Digital's time (Inter, line-height .8, tracking −0.045em,
 * the colon 0.01em apart and lifted 0.05em) at the largest size where "00:00" fits between 48 px
 * margins (~338 px; Digital's is 252), centred, and under it the [FarFact] at 44/600 with its
 * 40 px icon, in the face colour at 0.92 (Digital's secondary). Night: everything #BE180E.
 *
 * Redraws only when a string or the colour changes (minute tick, alarm, weather), so at rest it
 * costs no frames; the face behind it is hidden and stopped.
 */
@SuppressLint("ViewConstructor")
class FarClockView(context: Context, private val services: VesperServices) : View(context) {
    private var weight = 0
    private var time = SpecText(100f, 600, -0.045f)
    private val factText = SpecText(FACT_PX, 600, -0.02f)
    private val icAlarm: Drawable = context.icon(R.drawable.ic_cx_alarm)
    private val icSun: Drawable = context.icon(R.drawable.ic_wx_sun)
    private val icMoon: Drawable = context.icon(R.drawable.ic_wx_moon)
    private val icCloud: Drawable = context.icon(R.drawable.ic_wx_cloud)
    private val icRain: Drawable = context.icon(R.drawable.ic_wx_rain)

    private var color = Color.WHITE
    private var night = false
    private var hh = ""
    private var mm = ""
    private var fact: FarFact.Fact? = null
    private var alarm: NextAlarm? = null
    private var weather: Weather? = null
    private val cal = Calendar.getInstance()

    init {
        setWeight(600)
        observeWhileAttached(services.alarms.next) { alarm = it; refresh() }
        observeWhileAttached(services.weather.weather) { weather = it; refresh() }
    }

    private fun setWeight(w: Int) {
        if (w == weight) return
        weight = w
        // The largest size where four tabular digits fit: measured at 100 px, then scaled.
        val probe = SpecText(100f, w, -0.045f)
        val unit = (probe.width("00") * 2 + probe.width(":") + 2 * 0.01f * 100f) / 100f
        val px = ((W - 2 * MARGIN) / unit).coerceAtMost(MAX_TIME_PX)
        time = SpecText(px, w, -0.045f)
        hh = ""
    }

    /** Colour and Night from the face on screen; [faceId] picks the time weight. */
    fun bind(faceColor: Int, night: Boolean, faceId: String) {
        val w = FarLayout.weightOf(faceId)
        if (faceColor == color && night == this.night && w == weight) return
        color = faceColor; this.night = night
        setWeight(w)
        refresh()
        invalidate()
    }

    /** Re-reads the clock and the fact; invalidates only if a string changed. */
    fun refresh() {
        cal.timeZone = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        val is24 = DateFormat.is24HourFormat(context)
        val h = TimeText.hour(cal, is24)
        val m = TimeText.minute(cal)
        val f = FarFact.pick(now, alarm, weather, is24)
        if (h != hh || m != mm || f != fact) {
            hh = h; mm = m; fact = f
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(width / W, height / H)
        val fg = if (night) Night.COLOR else color
        val fg2 = if (night) Night.COLOR else withAlpha(color, 0.92f)
        val f = fact
        val t = time
        val lineH = 0.8f * t.size
        val blockH = lineH + (if (f != null) GAP + factText.normalLineHeight else 0f)
        val top = (H - blockH) / 2f

        // time: centred, Digital's spans (0.01em colon margins, colon lifted 0.05em)
        val colonGap = 0.01f * t.size
        val total = t.width(hh) + colonGap + t.width(":") + colonGap + t.width(mm)
        var x = (W - total) / 2f
        val baseline = t.baselineIn(top, lineH)
        x = t.draw(canvas, hh, x, baseline, fg)
        x = t.draw(canvas, ":", x + colonGap, baseline - 0.05f * t.size, fg)
        t.draw(canvas, mm, x + colonGap, baseline, fg)

        if (f != null) {
            val y = top + lineH + GAP
            val icon = iconFor(f)
            val lead = if (icon != null) ICON + ICON_GAP else 0f
            val x0 = (W - lead - factText.width(f.text)) / 2f
            if (icon != null) drawIcon(canvas, icon, x0, y + (factText.normalLineHeight - ICON) / 2f, ICON, fg2)
            factText.draw(canvas, f.text, x0 + lead, factText.baselineIn(y), fg2)
        }
        canvas.restore()
    }

    private fun iconFor(f: FarFact.Fact): Drawable? = when (f.kind) {
        FarFact.Kind.ALARM -> icAlarm
        FarFact.Kind.RAIN -> icRain
        FarFact.Kind.DATE -> null
        FarFact.Kind.WEATHER -> when (f.condition) {
            WeatherCondition.CLEAR, WeatherCondition.PARTLY_CLOUDY -> if (f.day) icSun else icMoon
            WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING, WeatherCondition.SLEET,
            WeatherCondition.HAIL, WeatherCondition.SNOW -> icRain
            else -> icCloud
        }
    }

    companion object {
        const val W = 960f
        const val H = 480f
        const val MARGIN = 48f
        const val MAX_TIME_PX = 340f
        const val FACT_PX = 44f
        const val GAP = 26f
        const val ICON = 40f
        const val ICON_GAP = 14f
    }
}
