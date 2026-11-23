package app.vesper.feature.radio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where parsed path segments go (android.graphics.Path in the app, a recorder in tests). */
internal interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun close()
}

/**
 * Minimal SVG path-data parser for the design's 24×24 icons: M L H V C S Q T A Z, absolute and
 * relative, implicit repeats and compact numbers ("4.5.5", "-4 12.74", arc flags "0 0 1-4 0").
 * Quadratics and arcs are converted to cubics.
 */
internal object SvgPath {
    fun parse(d: String, sink: PathSink) {
        val t = Tokens(d)
        var cmd = ' '
        var x = 0f; var y = 0f
        var sx = 0f; var sy = 0f
        var lcx = 0f; var lcy = 0f // last control point (for S/T)
        var prev = ' '
        while (true) {
            t.skipSeparators()
            if (t.atEnd()) break
            if (t.peekCommand()) cmd = t.next() else if (cmd == ' ') return
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + t.num(); y = oy + t.num(); sx = x; sy = y
                    sink.moveTo(x, y)
                    cmd = if (rel) 'l' else 'L' // further pairs are line-tos
                }
                'L' -> { x = ox + t.num(); y = oy + t.num(); sink.lineTo(x, y) }
                'H' -> { x = ox + t.num(); sink.lineTo(x, y) }
                'V' -> { y = oy + t.num(); sink.lineTo(x, y) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    x = ox + t.num(); y = oy + t.num()
                    sink.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'S' -> {
                    val x1 = if (prev.uppercaseChar() in "CS") 2 * x - lcx else x
                    val y1 = if (prev.uppercaseChar() in "CS") 2 * y - lcy else y
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    x = ox + t.num(); y = oy + t.num()
                    sink.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2
                }
                'Q', 'T' -> {
                    val (qx, qy) = if (cmd.uppercaseChar() == 'Q') (ox + t.num()) to (oy + t.num())
                    else if (prev.uppercaseChar() in "QT") (2 * x - lcx) to (2 * y - lcy) else x to y
                    val ex = ox + t.num(); val ey = oy + t.num()
                    sink.cubicTo(x + 2f / 3 * (qx - x), y + 2f / 3 * (qy - y), ex + 2f / 3 * (qx - ex), ey + 2f / 3 * (qy - ey), ex, ey)
                    x = ex; y = ey; lcx = qx; lcy = qy
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val ex = ox + t.num(); val ey = oy + t.num()
                    arc(sink, x, y, rx, ry, rot, large, sweep, ex, ey)
                    x = ex; y = ey
                }
                'Z' -> { sink.close(); x = sx; y = sy }
                else -> return
            }
            prev = cmd
        }
    }

    /** SVG arc (endpoint form) → cubics, per the SVG spec's implementation notes (F.6). */
    private fun arc(sink: PathSink, x1: Float, y1: Float, rxIn: Float, ryIn: Float, angleDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0) { sink.lineTo(x2, y2); return }
        val phi = Math.toRadians(angleDeg.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        val th1 = atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
        var dth = atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - th1
        if (sweep && dth < 0) dth += 2 * PI else if (!sweep && dth > 0) dth -= 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dth / segs
        val k = 4.0 / 3.0 * tan(delta / 4)
        var th = th1
        for (i in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th + delta); val s2 = sin(th + delta)
            fun px(c: Double, s: Double) = (cx + rx * c * cp - ry * s * sp)
            fun py(c: Double, s: Double) = (cy + rx * c * sp + ry * s * cp)
            // derivative direction at an angle: (-rx sin, ry cos) rotated by phi
            fun dxAt(c: Double, s: Double) = (-rx * s * cp - ry * c * sp)
            fun dyAt(c: Double, s: Double) = (-rx * s * sp + ry * c * cp)
            val ax = px(c1, s1); val ay = py(c1, s1)
            val bx = px(c2, s2); val by = py(c2, s2)
            val last = i == segs - 1
            sink.cubicTo(
                (ax + k * dxAt(c1, s1)).toFloat(), (ay + k * dyAt(c1, s1)).toFloat(),
                (bx - k * dxAt(c2, s2)).toFloat(), (by - k * dyAt(c2, s2)).toFloat(),
                if (last) x2 else bx.toFloat(), if (last) y2 else by.toFloat(),
            )
            th += delta
        }
    }

    private class Tokens(private val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipSeparators() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun peekCommand(): Boolean = i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E'
        fun next(): Char = s[i++]
        fun flag(): Boolean { skipSeparators(); return s[i++] == '1' }
        fun num(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++
                    if (s[i] == '-' || s[i] == '+') i++
                } else break
            }
            return s.substring(start, i).toFloat()
        }
    }
}
