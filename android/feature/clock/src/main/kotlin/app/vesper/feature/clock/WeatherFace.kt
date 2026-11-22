package app.vesper.feature.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.Weather
import app.vesper.core.model.WeatherCondition
import app.vesper.core.model.observeWhileAttached
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Weather face, from design/Weather.dc.html: the sky follows the time of day (day / dusk / night
 * from sunrise and sunset), current conditions on the left, and content modules on the right
 * (hourly, sunrise with the sun's path, tomorrow's rain). Data: [VesperServices.weather].
 *
 * Motion (stars twinkling, cloud banks drifting) updates once a second (the design uses 1 s
 * linear transitions; the clouds move ≤ 1.5 px/s, so whole-second steps are invisible under a
 * 40 px blur). Nothing moves in Night mode or while hidden. Clouds are pre-blurred bitmaps.
 */
class WeatherFace(context: Context, services: VesperServices) : SpecFace(context, services) {

    private val placeText = SpecText(22f, 600, -0.01f)
    private val tempText = SpecText(176f, 200, -0.05f)
    private val condText = SpecText(24f, 600, -0.01f)
    private val hlText = SpecText(20f, 500)
    private val capsText = SpecText(13f, 700, 0.05f)
    private val hourLabelText = SpecText(15f, 600)
    private val hourTempText = SpecText(20f, 600)
    private val sunriseText = SpecText(38f, 500, -0.03f)
    private val rainText = SpecText(17f, 600)

    private val icLocation = context.icon(R.drawable.ic_location)
    private val icClock = context.icon(R.drawable.ic_clock_small)
    private val icSun = context.icon(R.drawable.ic_wx_sun)
    private val icMoon = context.icon(R.drawable.ic_wx_moon)
    private val icCloud = context.icon(R.drawable.ic_wx_cloud)
    private val icRain = context.icon(R.drawable.ic_wx_rain)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val skyPaint = Paint()
    private val rect = RectF()
    private val arcPath = Path()
    private val handler = Handler(Looper.getMainLooper())

    private var weather: Weather? = null
    private var sky = Sky.NIGHT
    private var skyShader: Shader? = null
    private var shown = false

    // strings, rebuilt in update()
    private var minuteKey = -1L
    private var place = ""
    private var temp = ""
    private var condition = ""
    private var highLow = ""
    private var header = ""
    private var sunriseLabel = ""
    private var sunFrac = 0f
    private var rainSummary = ""
    private class Hour(val label: String, val icon: Drawable, val sunny: Boolean, val temp: String)
    private var hours: List<Hour> = emptyList()
    private var rain: IntArray = IntArray(0)

    private val content = android.graphics.RenderNode("weather-content")
    private var contentDirty = true
    private var cloud1: Bitmap? = null
    private var cloud2: Bitmap? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!shown || !animated()) return
            invalidate()
            handler.postAtTime(this, SystemClock.uptimeMillis() + 1000 - System.currentTimeMillis() % 1000)
        }
    }

    init {
        observeWhileAttached(services.weather.weather) { weather = it; minuteKey = -1; refresh(force = false) }
        observeWhileAttached(services.location) { minuteKey = -1; refresh(force = false) }
    }

    private fun animated() = !config.night

    override fun onConfigChanged(old: FaceConfig) { minuteKey = -1; restartTick() }

    override fun onScreenShown(args: Bundle?) { shown = true; restartTick() }

    override fun onScreenHidden() { shown = false; handler.removeCallbacks(tick) }

    private fun restartTick() { handler.removeCallbacks(tick); if (shown) tick.run() }

    override fun onDetachedFromWindow() {
        onScreenHidden()
        cloud1?.recycle(); cloud1 = null; cloud2?.recycle(); cloud2 = null
        super.onDetachedFromWindow()
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val key = now / 60_000
        if (key == minuteKey) return false
        minuteKey = key
        contentDirty = true
        val w = weather
        val loc = services.location.value
        val zone = TimeZone.getDefault()

        // sunrise / sunset: from the weather source, else computed for the home location
        var rise = w?.sunriseMillis
        var set = w?.sunsetMillis
        if (rise == null || set == null) SunMath.sunriseSunset(now, loc.lat, loc.lon, zone)?.let { rise = it.first; set = it.second }
        val r = rise; val s = set
        val newSky = when {
            r == null || s == null -> { val hr = cal.get(Calendar.HOUR_OF_DAY); if (hr in 7..16) Sky.DAY else if (hr == 6 || hr in 17..18) Sky.DUSK else Sky.NIGHT }
            now in r until s -> Sky.DAY
            now in (r - HOUR) until r || now in s until (s + 2 * HOUR) -> Sky.DUSK
            else -> Sky.NIGHT
        }
        if (newSky != sky || skyShader == null) { sky = newSky; skyShader = null; cloud1?.recycle(); cloud1 = null; cloud2?.recycle(); cloud2 = null }
        restartTick()

        place = w?.location?.ifEmpty { null } ?: loc.name
        if (w == null) {
            temp = "--"; condition = ""; highLow = ""; header = ""; hours = emptyList(); rain = IntArray(0); rainSummary = ""
        } else {
            temp = "${w.temperature}°"
            condition = conditionLabel(w.condition, sky == Sky.DAY)
            highLow = "H:${w.high}° L:${w.low}°"
            header = (w.tomorrowSummary ?: condition).uppercase()
            val list = ArrayList<Hour>()
            val nowH = cal.get(Calendar.HOUR_OF_DAY)
            list += Hour("Now", iconFor(w.condition, isDayHour(nowH, r, s, zone, now)), false, "${w.temperature}°")
            for (f in w.hourly.take(6)) {
                val hc = Calendar.getInstance(zone).apply { timeInMillis = f.timeMillis }
                val h = hc.get(Calendar.HOUR_OF_DAY)
                list += Hour(((h % 12).takeIf { it != 0 } ?: 12).toString() + (if (h < 12) "AM" else "PM"),
                    iconFor(f.condition, isDayHour(h, r, s, zone, f.timeMillis)), false, "${f.temperature}°")
            }
            hours = list.map { Hour(it.label, it.icon, it.icon === icSun, it.temp) }
            // tomorrow's rain: 10 of tomorrow's hourly entries, centred on the rain
            val tomorrow = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }.get(Calendar.DAY_OF_YEAR)
            val tm = w.hourly.filter { Calendar.getInstance(zone).apply { timeInMillis = it.timeMillis }.get(Calendar.DAY_OF_YEAR) == tomorrow }
            rain = rainWindow(tm.map { it.precipChance }, RAIN_BARS)
            rainSummary = w.tomorrowSummary ?: ""
        }
        sunriseLabel = r?.let { TimeText.clock(it, is24) } ?: "--"
        sunFrac = if (r != null && s != null) ((now - r).toFloat() / (s - r)).coerceIn(0f, 1f) else 0f
        return true
    }

    private fun isDayHour(h: Int, rise: Long?, set: Long?, zone: TimeZone, t: Long): Boolean {
        if (rise == null || set == null) return h in 7..16
        // compare time of day only (rise/set are today's)
        val day = 86_400_000L
        val off = zone.getOffset(t)
        val tod = ((t + off) % day + day) % day
        val r = ((rise + zone.getOffset(rise)) % day + day) % day
        val s = ((set + zone.getOffset(set)) % day + day) % day
        return tod in r until s
    }

    private fun iconFor(c: WeatherCondition, day: Boolean): Drawable = when (c) {
        WeatherCondition.CLEAR -> if (day) icSun else icMoon
        WeatherCondition.RAIN, WeatherCondition.POURING, WeatherCondition.LIGHTNING, WeatherCondition.SLEET, WeatherCondition.HAIL, WeatherCondition.SNOW -> icRain
        WeatherCondition.PARTLY_CLOUDY -> if (day) icSun else icMoon
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

    override fun drawSpec(canvas: Canvas) {
        val night = config.night
        val fg = if (night) Night.COLOR else Color.WHITE
        val fg2 = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.78f)
        val t = System.currentTimeMillis() / 1000.0

        if (!night) {
            if (skyShader == null) skyShader = when (sky) {
                Sky.DAY -> LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF2C7BD6.toInt(), 0xFF6FB1EE.toInt(), 0xFFA8D2F5.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                Sky.DUSK -> LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF2B2F6B.toInt(), 0xFF8E4F8A.toInt(), 0xFFF08A5D.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                Sky.NIGHT -> LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF050B22.toInt(), 0xFF0E1D45.toInt(), 0xFF23366B.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            }
            skyPaint.shader = skyShader
            canvas.drawRect(0f, 0f, W, H, skyPaint)

            // stars (dusk / night sky)
            if (sky != Sky.DAY) {
                fill.color = Color.WHITE
                for (s in STARS) {
                    fill.alpha = (255 * s.base * (0.7 + 0.3 * sin(t * 1.3 + s.x * 0.05))).toInt().coerceIn(0, 255)
                    canvas.drawCircle(s.x + s.r / 2, s.y + s.r / 2, s.r / 2, fill)
                }
            }
            // cloud banks
            val cc = if (sky == Sky.DAY) withAlpha(Color.WHITE, 0.5f) else Colors.argb(0.18f, 150, 170, 220)
            if (cloud1 == null) cloud1 = cloudBitmap(760f, 220f, 40f, cc)
            if (cloud2 == null) cloud2 = cloudBitmap(700f, 220f, 46f, cc)
            drawCloud(canvas, cloud1!!, 120f + (30 * sin(t / 20)).toFloat(), 300f, 760f, 220f, 40f)
            drawCloud(canvas, cloud2!!, -200f + (40 * cos(t / 26)).toFloat(), 360f, 700f, 220f, 46f)
        }

        // Everything below changes at most once a minute: recorded once into a RenderNode, so the
        // once-a-second motion frame only re-records the sky, stars and clouds above.
        if (canvas.isHardwareAccelerated) {
            if (contentDirty) {
                content.setPosition(0, 0, W.toInt(), H.toInt())
                val c = content.beginRecording()
                try { drawContent(c, night, fg, fg2) } finally { content.endRecording() }
                contentDirty = false
            }
            canvas.drawRenderNode(content)
        } else drawContent(canvas, night, fg, fg2)
    }

    private fun drawContent(canvas: Canvas, night: Boolean, fg: Int, fg2: Int) {

        // current conditions (left 52, top 40)
        var y = 40f
        val rowH = max(16f, placeText.normalLineHeight)
        if (place.isNotEmpty()) {
            drawIcon(canvas, icLocation, 52f, y + (rowH - 16f) / 2, 16f, fg)
            placeText.draw(canvas, place, 52f + 16f + 8f, placeText.baselineIn(y, rowH), fg)
        }
        y += rowH + 6f
        val tempH = 0.95f * tempText.size
        tempText.draw(canvas, temp, 52f - 6f, tempText.baselineIn(y, tempH), fg)
        y += tempH + 2f
        if (condition.isNotEmpty()) condText.draw(canvas, condition, 52f, condText.baselineIn(y), fg)
        y += condText.normalLineHeight + 4f
        if (highLow.isNotEmpty()) hlText.draw(canvas, highLow, 52f, hlText.baselineIn(y), fg2)

        if (weather == null) return
        val module = if (night) Colors.argb(0.08f, 190, 24, 14) else withAlpha(Color.WHITE, 0.14f)
        val border = if (night) withAlpha(Night.COLOR, 0.3f) else withAlpha(Color.WHITE, 0.18f)

        // hourly module: 420..924 × 40..246
        drawModule(canvas, 420f, 40f, 924f, 246f, module, border)
        val cl = 420f + 18f; val cr = 924f - 18f
        val headH = max(14f, capsText.normalLineHeight)
        var hy = 40f + 16f
        drawIcon(canvas, icClock, cl, hy + (headH - 14f) / 2, 14f, fg2)
        capsText.draw(canvas, ellipsize(capsText, header, cr - cl - 21f), cl + 21f, capsText.baselineIn(hy, headH), fg2)
        hy += headH + 12f
        fill.color = if (night) withAlpha(Night.COLOR, 0.4f) else withAlpha(Color.WHITE, 0.24f)
        canvas.drawRect(cl, hy, cr, hy + 0.5f, fill)
        hy += 0.5f + 10f
        val colW = (cr - cl) / 7f
        hours.take(7).forEachIndexed { i, h ->
            val cx = cl + colW * (i + 0.5f)
            var yy = hy
            hourLabelText.drawCentered(canvas, h.label, cx, hourLabelText.baselineIn(yy), fg)
            yy += hourLabelText.normalLineHeight + 12f
            drawIcon(canvas, h.icon, cx - 15f, yy, 30f, if (night) Night.COLOR else if (h.sunny) 0xFFFFD60A.toInt() else Color.WHITE)
            yy += 30f + 12f
            hourTempText.drawCentered(canvas, h.temp, cx, hourTempText.baselineIn(yy), fg)
        }

        // small modules: 244 wide, gap 16, 262..438
        val mw = (924f - 420f - 16f) / 2f
        drawSunrise(canvas, 420f, mw, module, border, fg, fg2, night)
        drawRain(canvas, 420f + mw + 16f, mw, module, border, fg, fg2, night)
    }

    private fun drawSunrise(canvas: Canvas, l: Float, w: Float, module: Int, border: Int, fg: Int, fg2: Int, night: Boolean) {
        drawModule(canvas, l, 262f, l + w, 438f, module, border)
        val cl = l + 18f; val cw = w - 36f
        val top = 262f + 16f; val bottom = 438f - 16f
        capsText.draw(canvas, "SUNRISE", cl, capsText.baselineIn(top), fg2)
        // space-between: label, time, the 34 px sun path
        val pathTop = bottom - 34f
        val labelBottom = top + capsText.normalLineHeight
        val timeH = sunriseText.normalLineHeight
        val timeTop = labelBottom + ((pathTop - labelBottom) - timeH) / 2f
        sunriseText.draw(canvas, sunriseLabel, cl, sunriseText.baselineIn(timeTop), fg)
        // horizon
        fill.color = if (night) withAlpha(Night.COLOR, 0.5f) else withAlpha(Color.WHITE, 0.35f)
        canvas.drawRect(cl, pathTop + 24f, cl + cw, pathTop + 25f, fill)
        // the arc: 8%..92% wide, top 4, 56 tall, top-rounded border 2 px, clipped to its top 60 %
        val al = cl + 0.08f * cw + 1f; val ar = cl + 0.92f * cw - 1f
        val at = pathTop + 4f + 1f
        val ry = 28f - 1f
        arcPath.reset()
        val clipBottom = pathTop + 4f + 56f * 0.6f
        arcPath.moveTo(al, clipBottom)
        arcPath.lineTo(al, at + ry)
        rect.set(al, at, ar, at + 2 * ry)
        arcPath.arcTo(rect, 180f, 180f)
        arcPath.lineTo(ar, clipBottom)
        stroke.strokeWidth = 2f
        stroke.color = if (night) withAlpha(Night.COLOR, 0.6f) else withAlpha(Color.WHITE, 0.4f)
        canvas.drawPath(arcPath, stroke)
        // the sun
        val sx = cl + cw * (0.08f + 0.84f * sunFrac)
        val sy = pathTop + (24f - 6f - 20f * sin(PI * sunFrac).toFloat()) + 6f
        val sun = if (night) Night.COLOR else 0xFFFFD60A.toInt()
        fill.color = sun
        if (!night) fill.setShadowLayer(9.5f, 0f, 0f, withAlpha(sun, 0.9f))
        canvas.drawCircle(sx, sy, 6f, fill)
        fill.clearShadowLayer()
    }

    private fun drawRain(canvas: Canvas, l: Float, w: Float, module: Int, border: Int, fg: Int, fg2: Int, night: Boolean) {
        drawModule(canvas, l, 262f, l + w, 438f, module, border)
        val cl = l + 18f; val cw = w - 36f
        val top = 262f + 16f; val bottom = 438f - 16f
        capsText.draw(canvas, "TOMORROW", cl, capsText.baselineIn(top), fg2)
        val textH = rainText.normalLineHeight
        rainText.draw(canvas, ellipsize(rainText, rainSummary, cw), cl, rainText.baselineIn(bottom - textH), fg)
        if (rain.isEmpty()) return
        val labelBottom = top + capsText.normalLineHeight
        val barsTop = labelBottom + ((bottom - textH - labelBottom) - 64f) / 2f
        val n = rain.size
        val bw = (cw - 6f * (n - 1)) / n
        for (i in 0 until n) {
            val h = rain[i].coerceIn(0, 100)
            val bh = 64f * h / 100f
            fill.color = if (night) Colors.argb(0.8f, 190, 24, 14) else Colors.argb(0.35f + h / 160f, 100, 210, 255)
            val x = cl + i * (bw + 6f)
            rect.set(x, barsTop + 64f - bh, x + bw, barsTop + 64f)
            canvas.drawRoundRect(rect, 4f, 4f, fill)
        }
    }

    /** Content module (not glass: NOTES §3.1 rule 1): plain fill + hairline border, radius 28. */
    private fun drawModule(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, border: Int) {
        rect.set(l, t, r, b)
        fill.color = color
        canvas.drawRoundRect(rect, 28f, 28f, fill)
        stroke.strokeWidth = 0.5f
        stroke.color = border
        rect.inset(0.25f, 0.25f)
        canvas.drawRoundRect(rect, 28f, 28f, stroke)
    }

    private fun drawCloud(canvas: Canvas, bmp: Bitmap, left: Float, top: Float, w: Float, h: Float, sigma: Float) {
        val pad = 3 * sigma
        rect.set(left - pad, top - pad, left + w + pad, top + h + pad)
        canvas.drawBitmap(bmp, null, rect, bmpPaint)
    }

    private fun ellipsize(t: SpecText, s: String, maxW: Float): String {
        if (t.width(s) <= maxW) return s
        var n = s.length
        while (n > 1 && t.width(s.substring(0, n) + "…") > maxW) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    private enum class Sky { DAY, DUSK, NIGHT }
    private class Star(val x: Float, val y: Float, val r: Float, val base: Float)

    companion object {
        private const val HOUR = 3_600_000L
        const val RAIN_BARS = 10

        /** The design's 46 stars (seeded LCG: seed = (seed·9301 + 49297) mod 233280). */
        private val STARS: List<Star> = run {
            var seed = 7.0
            fun rnd(): Double { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 }
            List(46) {
                val x = WorldMap.jsRound(rnd() * 960).toFloat()
                val y = WorldMap.jsRound(rnd() * 300).toFloat()
                val r = (Math.round((1 + rnd() * 1.8) * 10) / 10.0).toFloat()
                Star(x, y, r, (0.25 + rnd() * 0.6).toFloat())
            }
        }

        /** An ellipse blurred by [sigma], at 1/8 resolution (it's only soft light). */
        private fun cloudBitmap(w: Float, h: Float, sigma: Float, color: Int): Bitmap {
            val s = 1 / 8f
            val pad = 3 * sigma
            val bmp = Bitmap.createBitmap(((w + 2 * pad) * s).toInt() + 1, ((h + 2 * pad) * s).toInt() + 1, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(s, s)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                maskFilter = BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawOval(pad, pad, pad + w, pad + h, p)
            return bmp
        }
    }
}
