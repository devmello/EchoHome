package com.echobedside.feature.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.HomeLocation
import com.echobedside.core.model.NextAlarm
import com.echobedside.core.model.observeWhileAttached
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * StandBy "World" face, from design/World.dc.html: the 96×36 dotted land mask (NOTES §5.7) lit by
 * the sun's declination and longitude with a smooth twilight band, world-clock cities, your
 * location, the next alarm and the time. No colour option (as in Apple's).
 *
 * The ~1,000 dots are recomputed once a minute and drawn as a few dozen `drawPoints` batches
 * (one per brightness level), so a redraw is cheap even while edit mode animates the face.
 */
class WorldFace(context: Context, services: EchoServices) : SpecFace(context, services) {

    /** World-clock cities: `clock.world.cities` = "City|Area/Zone|lat|lon;..." (default: the design's three). */
    data class City(val name: String, val zone: TimeZone, val lat: Double, val lon: Double)

    private val labelText = SpecText(17f, 600, -0.015f)
    private val zoneText = SpecText(12f, 600, -0.01f).apply { paint.setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val timeText = SpecText(136f, 600, -0.045f)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_22)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 5f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nextAlarm: NextAlarm? = null
    private var location: HomeLocation? = null
    private var cities: List<City> = emptyList()

    // per-minute state
    private var minuteKey = -1L
    private var time = ""
    private var alarmLabel: String? = null
    private val zoneLabels = ArrayList<String>()
    /** Brightness levels → point arrays for drawPoints. */
    private val levelPoints = arrayOfNulls<FloatArray>(LEVELS * 2)
    private val levelCounts = IntArray(LEVELS * 2)
    private val levelColors = IntArray(LEVELS * 2)

    init {
        observeWhileAttached(services.alarms.next) { nextAlarm = it; refresh(force = false) }
        observeWhileAttached(services.weather.weather) { refresh(force = false) }
        observeWhileAttached(services.settings.changes) { k ->
            if (k == KEY_CITIES) { cities = parseCities(services.settings.getString(KEY_CITIES, "")); minuteKey = -1; refresh(force = true) }
        }
        observeWhileAttached(services.location) { refresh(force = false) }
        cities = parseCities(services.settings.getString(KEY_CITIES, ""))
    }

    override fun onConfigChanged(old: com.echobedside.core.model.FaceConfig) {
        if (old.night != config.night) minuteKey = -1
    }

    override fun update(): Boolean {
        val now = cal.timeInMillis
        val key = now / 60_000
        val loc = services.location.value
        val al = nextAlarm?.let { TimeText.clock(it.triggerAtMillis, is24) }
        if (key == minuteKey && loc == location && al == alarmLabel) return false
        location = loc
        alarmLabel = al
        if (key != minuteKey) {
            minuteKey = key
            time = TimeText.time(cal, is24)
            computeDots(now)
            zoneLabels.clear()
            for (c in cities) zoneLabels += c.name + " " + TimeText.clock(now, is24, zone = c.zone)
        }
        return true
    }

    private fun computeDots(now: Long) {
        val decl = SunMath.declination(now)
        val sunLon = SunMath.subsolarLongitude(now)
        val night = config.night
        levelCounts.fill(0)
        val lit = FloatArray(WorldMap.land.size)
        WorldMap.land.forEachIndexed { i, d ->
            lit[i] = SunMath.daylight(SunMath.cosZenith(d.lat, d.lon, decl, sunLon)).toFloat()
            levelCounts[bucket(lit[i], d.underText)]++
        }
        for (b in levelPoints.indices) {
            val n = levelCounts[b] * 2
            if (levelPoints[b]?.size != n) levelPoints[b] = FloatArray(n)
            val l = (b % LEVELS) / (LEVELS - 1f)
            var a = 0.2f + 0.72f * l
            if (b >= LEVELS) a *= 0.35f
            levelColors[b] = if (night) withAlpha(Night.COLOR, a * 0.8f)
                else Colors.argb(a, (150 + 105 * l).roundToInt(), (150 + 100 * l).roundToInt(), (160 + 70 * l).roundToInt())
        }
        val fillAt = IntArray(levelPoints.size)
        WorldMap.land.forEachIndexed { i, d ->
            val b = bucket(lit[i], d.underText)
            val arr = levelPoints[b]!!
            arr[fillAt[b]++] = d.cx; arr[fillAt[b]++] = d.cy
        }
    }

    private fun bucket(lit: Float, under: Boolean) = (lit * (LEVELS - 1) + 0.5f).toInt().coerceIn(0, LEVELS - 1) + if (under) LEVELS else 0

    override fun drawSpec(canvas: Canvas) {
        val night = config.night
        val fg = if (night) Night.COLOR else Color.WHITE
        val fg2 = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.72f)
        val orange = 0xFFFF9F0A.toInt()

        for (b in levelPoints.indices) {
            val pts = levelPoints[b] ?: continue
            if (pts.isEmpty()) continue
            dotPaint.color = levelColors[b]
            canvas.drawPoints(pts, dotPaint)
        }

        // Cities: 9 px dot with a 3 px black ring, then the label (gap 6), row centred on the dot
        val zoneColor = if (night) Night.COLOR else orange
        cities.forEachIndexed { i, c ->
            val (px, py) = WorldMap.project(c.lat, c.lon)
            val left = WorldMap.jsRound(px - 4.5).toFloat()
            val top = WorldMap.jsRound(py - 4.5 - 1).toFloat()
            val rowH = maxOf(9f, zoneText.normalLineHeight)
            val cy = top + rowH / 2
            fill.color = withAlpha(Color.BLACK, 0.85f)
            canvas.drawCircle(left + 4.5f, cy, 7.5f, fill)
            fill.color = zoneColor
            canvas.drawCircle(left + 4.5f, cy, 4.5f, fill)
            zoneText.draw(canvas, zoneLabels.getOrElse(i) { c.name }, left + 9f + 6f, zoneText.baselineIn(top, rowH), fg2)
        }

        // You are here: 28 px halo, 12 px dot with a 2.5 px ring
        location?.let { loc ->
            val (px, py) = WorldMap.project(loc.lat, loc.lon)
            val hx = WorldMap.jsRound(px - 14).toFloat() + 14f
            val hy = WorldMap.jsRound(py - 14).toFloat() + 14f
            fill.color = if (night) withAlpha(Night.COLOR, 0.25f) else withAlpha(orange, 0.28f)
            canvas.drawCircle(hx, hy, 14f, fill)
            val dx = WorldMap.jsRound(px - 6).toFloat() + 6f
            val dy = WorldMap.jsRound(py - 6).toFloat() + 6f
            fill.color = if (night) Color.BLACK else orange
            canvas.drawCircle(dx, dy, 8.5f, fill)
            fill.color = fg
            canvas.drawCircle(dx, dy, 6f, fill)
        }

        // Top-left: alarm, then the location (gap 4)
        var y = 30f
        val rowH = maxOf(17f, labelText.normalLineHeight)
        alarmLabel?.let {
            drawIcon(canvas, alarmIcon, 40f, y + (rowH - 17f) / 2, 17f, fg2)
            labelText.draw(canvas, it, 40f + 17f + 7f, labelText.baselineIn(y, rowH), fg2)
            y += rowH + 4f
        }
        val name = location?.name.orEmpty().ifEmpty { services.weather.weather.value?.location.orEmpty() }
        if (name.isNotEmpty()) labelText.draw(canvas, name, 40f, labelText.baselineIn(y), fg)

        // Bottom-left: the time (left 36, bottom 26, line-height .8)
        val lineH = 0.8f * timeText.size
        timeText.draw(canvas, time, 36f, timeText.baselineIn(H - 26f - lineH, lineH), fg)
    }

    companion object {
        const val KEY_CITIES = "clock.world.cities"
        private const val LEVELS = 24

        val DEFAULT_CITIES = listOf(
            City("New York", TimeZone.getTimeZone("America/New_York"), 40.71, -74.0),
            City("London", TimeZone.getTimeZone("Europe/London"), 51.5, -0.13),
            City("Hyderabad", TimeZone.getTimeZone("Asia/Kolkata"), 17.39, 78.49),
        )

        fun encodeCity(c: City) = "${c.name}|${c.zone.id}|${c.lat}|${c.lon}"

        /** "City|Zone|lat|lon" entries separated by "," (settings TEXT_LIST) or ";"; max three. */
        fun parseCities(s: String): List<City> {
            if (s.isBlank()) return DEFAULT_CITIES
            return s.split(';', ',').mapNotNull { e ->
                val p = e.split('|')
                if (p.size < 4) null else runCatching { City(p[0], TimeZone.getTimeZone(p[1]), p[2].toDouble(), p[3].toDouble()) }.getOrNull()
            }.take(3).ifEmpty { DEFAULT_CITIES }
        }
    }
}
