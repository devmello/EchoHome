package com.echobedside.feature.clock

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/*
 * Pure math for the faces (no Android types, unit-tested in src/test).
 */

/**
 * Analog dial ring (NOTES §5.7, Analog.dc.html → ring()): the point on the superellipse
 * |x/a|^n + |y/b|^n = 1 (n = 5) in direction θ (clockwise from 12), centred on (cx, cy),
 * with the outward unit normal and the tick rotation (degrees) that follows that normal.
 */
object Superellipse {
    const val N = 5.0

    data class RingPoint(val x: Float, val y: Float, val nx: Float, val ny: Float, val rotDeg: Float)

    fun ring(deg: Double, a: Double, b: Double, cx: Double = 480.0, cy: Double = 240.0, n: Double = N): RingPoint {
        val th = deg * PI / 180
        val dx = sin(th)
        val dy = -cos(th)
        val t = (abs(dx / a).pow(n) + abs(dy / b).pow(n)).pow(-1 / n)
        val x = dx * t
        val y = dy * t
        val gx = sign(x) * abs(x / a).pow(n - 1) / a
        val gy = sign(y) * abs(y / b).pow(n - 1) / b
        val g = hypot(gx, gy).takeIf { it != 0.0 } ?: 1.0
        return RingPoint((cx + x).toFloat(), (cy + y).toFloat(), (gx / g).toFloat(), (gy / g).toFloat(),
            (atan2(gx, -gy) * 180 / PI).toFloat())
    }

    /** |x/a|^n + |y/b|^n for a point relative to the centre (1 = on the curve). */
    fun level(x: Double, y: Double, a: Double, b: Double, n: Double = N) = abs(x / a).pow(n) + abs(y / b).pow(n)
}

/** Sun position for the World face's day/night (World.dc.html) and sunrise/sunset times. */
object SunMath {
    private const val DAY_MS = 86_400_000.0

    /** Fractional day of the year as the design computes it: (t - Date.UTC(year, 0, 0)) / day. */
    fun dayOfYear(utcMillis: Long): Double {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        val y = cal.get(java.util.Calendar.YEAR)
        cal.clear(); cal.set(y, 0, 0, 0, 0, 0) // Dec 31 of the year before, like Date.UTC(y, 0, 0)
        return (utcMillis - cal.timeInMillis) / DAY_MS
    }

    /** Solar declination, radians: −23.44° · cos(2π/365 · (doy + 10)). */
    fun declination(utcMillis: Long): Double = -23.44 * cos(2 * PI / 365 * (dayOfYear(utcMillis) + 10)) * PI / 180

    /** Longitude (degrees) where the sun is overhead: −15 · (UTC hours − 12). */
    fun subsolarLongitude(utcMillis: Long): Double {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60.0
        return -15 * (h - 12)
    }

    /** cos(solar zenith) at lat/lon (degrees) for a declination and subsolar longitude. */
    fun cosZenith(latDeg: Double, lonDeg: Double, decl: Double, sunLonDeg: Double): Double {
        val la = latDeg * PI / 180
        val dl = (lonDeg - sunLonDeg) * PI / 180
        return sin(la) * sin(decl) + cos(la) * cos(decl) * cos(dl)
    }

    /** 0 (night) … 1 (day) with the design's smooth twilight band: smoothstep over cz ∈ [−0.1, 0.1]. */
    fun daylight(cz: Double): Double {
        val k = ((cz + 0.1) / 0.2).coerceIn(0.0, 1.0)
        return k * k * (3 - 2 * k)
    }

    /**
     * Sunrise and sunset (UTC millis) for the local date containing [utcMillis] at lat/lon, or null
     * in polar day/night. NOAA-style approximation (equation of time, −0.833° for refraction),
     * good to a couple of minutes, which is all the Weather face shows.
     */
    fun sunriseSunset(utcMillis: Long, latDeg: Double, lonDeg: Double, zone: java.util.TimeZone): Pair<Long, Long>? {
        val local = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = utcMillis
            set(java.util.Calendar.HOUR_OF_DAY, 12); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val noonGuess = local.timeInMillis
        val doy = local.get(java.util.Calendar.DAY_OF_YEAR).toDouble()
        val decl = declination(noonGuess)
        val b = 2 * PI * (doy - 81) / 364
        val eotMin = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        // Solar noon in UTC hours for this date
        val noonUtcH = 12 - lonDeg / 15 - eotMin / 60
        val la = latDeg * PI / 180
        val cosH = (sin(-0.833 * PI / 180) - sin(la) * sin(decl)) / (cos(la) * cos(decl))
        if (cosH < -1 || cosH > 1) return null
        val h0 = acos(cosH) * 180 / PI / 15 // hours
        val utcMidnight = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH), local.get(java.util.Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val rise = utcMidnight + ((noonUtcH - h0) * 3_600_000).toLong()
        val set = utcMidnight + ((noonUtcH + h0) * 3_600_000).toLong()
        return rise to set
    }
}

/** The World face's dotted map (World.dc.html → rows(), proj()). */
object WorldMap {
    const val COLS = 96
    const val ROWS = 36
    const val DEG = 3.75
    const val LEFT_LON = 157.5
    const val TOP_LAT = 76.0
    const val TOP_PX = 64.0
    const val DOT_PX = 10.0

    /** 96 × 36 land mask, hex, one row per string (top = 76°N, left = 157.5°E). */
    val rows = arrayOf(
        "000007df83ff0000040ffd14", "ec1fbfeff1ff001e0affffff", "ff3fffff38f8c07fffffffff", "fc3ffffd78e080ffffffffff",
        "c03cfff8780001effffffffe", "80001fff7c000cffffffffe1", "00000ffffe0017fffffffff0", "00000ffffb0007ffffffffe0",
        "000007fff0000ffcffffffd8", "000007ffe0001c3fffffff90", "000007ffc0000f87fffffdb0", "000003ff80001fd1fffffe40",
        "000001fd80001ffffffffe00", "000000f080003ffff3fffc00", "00000032c0007ffffc7ff800", "0000003e00007fff7879e200",
        "0000000700007fffe030e200", "000000013c003ffff0302300", "000000007e001fffe0108700", "000000007f8000ffe000cc00",
        "00000000ffc000ffc000dc40", "00000000fff0007f8000627a", "000000007ff8007f8000101c", "000000007ff0007f80000040",
        "000000003ff0007fb00001f8", "000000001fe0007f200007fc", "000000001fe0003f60000ffe", "000000001f80003e00000ffe",
        "000000001f80001c00000ffe", "000000001f0000180000063c", "080000003e0000000000000c", "100000003800000000000004",
        "300000003000000000000000", "000000003000000000000000", "000000003000000000000000", "000000000000000000000000",
    )

    class Dot(val col: Int, val row: Int, val lat: Double, val lon: Double) {
        /** Centre of the 5 px dot in screen px (the design rounds the dot's left/top with toFixed(0)). */
        val cx: Float = (jsRound(col * DOT_PX + 2.5) + 2.5).toFloat()
        val cy: Float = (jsRound(TOP_PX + row * DOT_PX + 2.5) + 2.5).toFloat()
        /** Under the top-left labels or the bottom-left time: drawn at 35 %. */
        val underText: Boolean = run {
            val x = col * DOT_PX + 2.5; val y = TOP_PX + row * DOT_PX + 2.5
            (x < 300 && y < 96) || (x < 400 && y > 336)
        }
    }

    val land: List<Dot> by lazy {
        val out = ArrayList<Dot>(1200)
        rows.forEachIndexed { r, hex ->
            for (i in hex.indices) {
                val nib = hex[i].digitToInt(16)
                for (b in 0 until 4) if (nib and (8 shr b) != 0) {
                    val c = i * 4 + b
                    out += Dot(c, r, TOP_LAT - (r + 0.5) * DEG, LEFT_LON + (c + 0.5) * DEG)
                }
            }
        }
        out
    }

    /** Screen px of a lat/lon on the map. */
    fun project(lat: Double, lon: Double): Pair<Double, Double> {
        val x = (((lon - LEFT_LON) % 360) + 360) % 360 / DEG * DOT_PX
        val y = TOP_PX + (TOP_LAT - lat) / DEG * DOT_PX
        return x to y
    }

    /** JavaScript's Number.toFixed(0) / Math.round for non-negative values: half rounds up. */
    fun jsRound(v: Double): Double = kotlin.math.floor(v + 0.5)
}

/**
 * Auto Night (NOTES §5.3): enter when the light stays below [enterLux] for [enterDelayMs], leave as
 * soon as it goes above [leaveLux]. Between the two thresholds nothing changes (hysteresis).
 * The light sensor is on-change, so a dark room sends no more events: call [onTick] at
 * [nextCheckAt] to finish the 30 s wait.
 */
class AutoNight(
    private val enterLux: Float = 3f,
    private val leaveLux: Float = 10f,
    private val enterDelayMs: Long = 30_000,
) {
    var active = false
        private set
    private var darkSince: Long? = null

    /** Feeds a reading; returns [active]. */
    fun onLux(lux: Float, nowMs: Long): Boolean {
        if (active) {
            if (lux > leaveLux) { active = false; darkSince = null }
        } else {
            if (lux < enterLux) {
                val since = darkSince ?: nowMs.also { darkSince = it }
                if (nowMs - since >= enterDelayMs) active = true
            } else {
                darkSince = null
            }
        }
        return active
    }

    /** Re-checks the pending dark timer with no new reading. */
    fun onTick(nowMs: Long): Boolean {
        val since = darkSince
        if (!active && since != null && nowMs - since >= enterDelayMs) active = true
        return active
    }

    /** When [onTick] could next change the state, or null. */
    fun nextCheckAt(): Long? = if (active) null else darkSince?.plus(enterDelayMs)

    companion object {
        fun resolve(mode: com.echobedside.core.model.NightMode, autoActive: Boolean) = when (mode) {
            com.echobedside.core.model.NightMode.ON -> true
            com.echobedside.core.model.NightMode.OFF -> false
            com.echobedside.core.model.NightMode.AUTO -> autoActive
        }
    }
}

/** Colour helpers that mirror the designs' JS (mix(), hsl(), tone(), pass()). ARGB ints. */
object Colors {
    fun r(c: Int) = (c shr 16) and 255
    fun g(c: Int) = (c shr 8) and 255
    fun b(c: Int) = c and 255

    fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** mix(hex, 'white'|'black', t, a): RGB moved toward white/black by t, with alpha a. */
    fun mix(c: Int, toWhite: Boolean, t: Float, a: Float = 1f): Int {
        val q = if (toWhite) 255 else 0
        fun ch(v: Int) = (v + (q - v) * t).roundToInt()
        return argb(a, ch(r(c)), ch(g(c)), ch(b(c)))
    }

    /** RGB → [h 0..360, s 0..100, l 0..100] (Float.dc.html → hsl()). */
    fun toHsl(c: Int): FloatArray {
        val rr = r(c) / 255f; val gg = g(c) / 255f; val bb = b(c) / 255f
        val mx = maxOf(rr, gg, bb); val mn = minOf(rr, gg, bb); val l = (mx + mn) / 2
        var h = 0f; var s = 0f
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                rr -> (gg - bb) / d + (if (gg < bb) 6 else 0)
                gg -> (bb - rr) / d + 2
                else -> (rr - gg) / d + 4
            } * 60f
        }
        return floatArrayOf(h, s * 100, l * 100)
    }

    /** CSS hsl(h, s%, l%) → opaque ARGB. */
    fun fromHsl(h: Float, s: Float, l: Float, a: Float = 1f): Int {
        val ss = (s / 100f).coerceIn(0f, 1f); val ll = (l / 100f).coerceIn(0f, 1f)
        val hh = ((h % 360f) + 360f) % 360f / 360f
        if (ss == 0f) { val v = (ll * 255).roundToInt(); return argb(a, v, v, v) }
        val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
        val p = 2 * ll - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return argb(a, (hue(hh + 1f / 3) * 255).roundToInt(), (hue(hh) * 255).roundToInt(), (hue(hh - 1f / 3) * 255).roundToInt())
    }

    /** tone(hsl, dh, dl) from Float.dc.html, with the CSS string's integer rounding. */
    fun tone(hsl: FloatArray, dh: Float, dl: Float): Int {
        val h = ((hsl[0] + dh + 360f) % 360f).roundToInt().toFloat()
        val s = hsl[1].roundToInt().toFloat()
        val l = (hsl[2] + dl).coerceIn(0f, 100f).roundToInt().toFloat()
        return fromHsl(h, s, l)
    }

    /** pass() from Glass.dc.html: light through coloured glass (toward the tint, then toward white). */
    fun pass(c: Int, tint: Int, tAmt: Float, wAmt: Float, a: Float): Int {
        fun ch(v: Int, q: Int): Int {
            val tinted = v + (q * (0.35f + 0.65f * v / 255f) - v) * tAmt
            return (tinted + (255 - tinted) * wAmt).roundToInt()
        }
        return argb(a, ch(r(c), r(tint)), ch(g(c), g(tint)), ch(b(c), b(tint)))
    }

    fun isWhite(c: Int) = (c and 0xFFFFFF) == 0xFFFFFF
}
