package app.vesper.feature.system.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A tiny SVG path-data parser (M L H V C S Q T A Z, absolute and relative), so the icons in the
 * design files can be pasted verbatim. Output goes to a [PathSink] so it can be unit-tested
 * without android.graphics; [AndroidPathSink] builds a real Path. Arcs become cubic Béziers.
 */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun close()
}

object SvgPath {
    fun parse(d: String, sink: PathSink) {
        val t = Tokens(d)
        var cx = 0f; var cy = 0f          // current point
        var sx = 0f; var sy = 0f          // subpath start
        var lcx = 0f; var lcy = 0f        // last control point (for S / T)
        var lastCmd = ' '
        var cmd = ' '
        while (true) {
            t.skipSeparators()
            if (t.atEnd()) break
            val c = t.peek()
            if (c.isLetter()) { cmd = c; t.next() } else if (cmd == ' ') error("path must start with a command: $d")
            else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { cx = ox + t.num(); cy = oy + t.num(); sx = cx; sy = cy; sink.moveTo(cx, cy) }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); sink.lineTo(cx, cy) }
                'H' -> { cx = ox + t.num(); sink.lineTo(cx, cy) }
                'V' -> { cy = oy + t.num(); sink.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num(); val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    sink.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'S' -> {
                    val refl = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                    val x1 = if (refl) 2 * cx - lcx else cx; val y1 = if (refl) 2 * cy - lcy else cy
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    sink.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'Q' -> {
                    val qx = ox + t.num(); val qy = oy + t.num(); val x = ox + t.num(); val y = oy + t.num()
                    quad(sink, cx, cy, qx, qy, x, y); cx = x; cy = y; lcx = qx; lcy = qy
                }
                'T' -> {
                    val refl = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                    val qx = if (refl) 2 * cx - lcx else cx; val qy = if (refl) 2 * cy - lcy else cy
                    val x = ox + t.num(); val y = oy + t.num()
                    quad(sink, cx, cy, qx, qy, x, y); cx = x; cy = y; lcx = qx; lcy = qy
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    arc(sink, cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y
                }
                'Z' -> { sink.close(); cx = sx; cy = sy }
                else -> error("unsupported path command '$cmd'")
            }
            lastCmd = cmd
        }
    }

    private fun quad(s: PathSink, x0: Float, y0: Float, qx: Float, qy: Float, x: Float, y: Float) =
        s.cubicTo(x0 + 2f / 3 * (qx - x0), y0 + 2f / 3 * (qy - y0), x + 2f / 3 * (qx - x), y + 2f / 3 * (qy - y), x, y)

    /** SVG arc (endpoint parameterisation, spec F.6.5) → cubic Béziers of at most 90° each. */
    private fun arc(s: PathSink, x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x: Float, y: Float) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { s.lineTo(x, y); return }
        val phi = rotDeg * PI / 180
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { val k = sqrt(lambda); rx *= k; ry *= k }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val ccx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val ccy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dth / segs
        val k = 4.0 / 3.0 * tan(delta / 4)
        var th = th1
        var px = x0.toDouble(); var py = y0.toDouble()
        for (i in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val th2 = th + delta
            val c2 = cos(th2); val s2 = sin(th2)
            // endpoints/derivatives on the unit circle, then scale, rotate, translate
            fun mapX(ux: Double, uy: Double) = cosP * rx * ux - sinP * ry * uy + ccx
            fun mapY(ux: Double, uy: Double) = sinP * rx * ux + cosP * ry * uy + ccy
            val e1x = c1 - k * s1; val e1y = s1 + k * c1
            val e2x = c2 + k * s2; val e2y = s2 - k * c2
            val ex = if (i == segs - 1) x.toDouble() else mapX(c2, s2)
            val ey = if (i == segs - 1) y.toDouble() else mapY(c2, s2)
            s.cubicTo(mapX(e1x, e1y).toFloat(), mapY(e1x, e1y).toFloat(), mapX(e2x, e2y).toFloat(), mapY(e2x, e2y).toFloat(), ex.toFloat(), ey.toFloat())
            px = ex; py = ey
            th = th2
        }
    }

    private class Tokens(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun peek() = s[i]
        fun next() { i++ }
        fun skipSeparators() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun flag(): Boolean {
            skipSeparators()
            val c = s[i++]
            require(c == '0' || c == '1') { "bad arc flag '$c' in $s" }
            return c == '1'
        }
        fun num(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2; while (i < s.length && s[i].isDigit()) i++; break
                } else break
            }
            require(i > start) { "expected a number at $start in $s" }
            return s.substring(start, i).toFloat()
        }
    }
}
