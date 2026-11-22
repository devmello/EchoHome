package app.vesper.feature.home.ui

import android.graphics.Matrix
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * SVG path data (the `d` attributes in the design files) → absolute segments → android Path.
 * No AndroidX, so no PathParser: this handles M L H V C S Q T A Z, relative forms and implicit
 * repeats. Arcs become cubic Béziers. The segment list is plain Kotlin so it's unit-testable.
 */
object SvgPath {
    sealed interface Seg {
        data class Move(val x: Float, val y: Float) : Seg
        data class Line(val x: Float, val y: Float) : Seg
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
        data class Quad(val x1: Float, val y1: Float, val x: Float, val y: Float) : Seg
        data object Close : Seg
    }

    fun parse(d: String): List<Seg> {
        val out = ArrayList<Seg>()
        val t = Tokens(d)
        var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
        var lastCtrlX = 0f; var lastCtrlY = 0f; var lastCmd = ' '
        var cmd = ' '
        while (true) {
            t.skipSep()
            if (t.atEnd()) break
            if (t.peekIsCommand()) cmd = t.nextChar() else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + t.num(); cy = oy + t.num(); sx = cx; sy = cy
                    out += Seg.Move(cx, cy)
                    cmd = if (rel) 'l' else 'L' // implicit lineto after the first pair
                }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); out += Seg.Line(cx, cy) }
                'H' -> { cx = ox + t.num(); out += Seg.Line(cx, cy) }
                'V' -> { cy = (if (rel) cy else 0f) + t.num(); out += Seg.Line(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num(); val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    out += Seg.Cubic(x1, y1, x2, y2, cx, cy); lastCtrlX = x2; lastCtrlY = y2
                }
                'S' -> {
                    val reflect = lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S'
                    val x1 = if (reflect) 2 * cx - lastCtrlX else cx
                    val y1 = if (reflect) 2 * cy - lastCtrlY else cy
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    out += Seg.Cubic(x1, y1, x2, y2, cx, cy); lastCtrlX = x2; lastCtrlY = y2
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    out += Seg.Quad(x1, y1, cx, cy); lastCtrlX = x1; lastCtrlY = y1
                }
                'T' -> {
                    val reflect = lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T'
                    val x1 = if (reflect) 2 * cx - lastCtrlX else cx
                    val y1 = if (reflect) 2 * cy - lastCtrlY else cy
                    cx = ox + t.num(); cy = oy + t.num()
                    out += Seg.Quad(x1, y1, cx, cy); lastCtrlX = x1; lastCtrlY = y1
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    arcToCubics(cx, cy, rx, ry, rot, large, sweep, x, y, out)
                    cx = x; cy = y
                }
                'Z' -> { out += Seg.Close; cx = sx; cy = sy }
                else -> break
            }
            lastCmd = cmd
        }
        return out
    }

    /** Builds a Path, optionally scaled (e.g. a 24-unit icon drawn at 22 px). */
    fun path(d: String, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f): Path {
        val p = Path()
        for (s in parse(d)) when (s) {
            is Seg.Move -> p.moveTo(s.x, s.y)
            is Seg.Line -> p.lineTo(s.x, s.y)
            is Seg.Cubic -> p.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
            is Seg.Quad -> p.quadTo(s.x1, s.y1, s.x, s.y)
            Seg.Close -> p.close()
        }
        if (scale != 1f || dx != 0f || dy != 0f) p.transform(Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) })
        return p
    }

    /** SVG endpoint arc → centre parameterisation → ≤90° cubic pieces (W3C SVG implementation notes F.6). */
    private fun arcToCubics(x1: Float, y1: Float, rxIn: Float, ryIn: Float, angleDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float, out: MutableList<Seg>) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0) { out += Seg.Line(x2, y2); return }
        val phi = angleDeg * PI / 180
        val cosP = cos(phi); val sinP = sin(phi)
        val dx2 = (x1 - x2) / 2.0; val dy2 = (y1 - y2) / 2.0
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val theta1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= 2 * PI
        if (sweep && dTheta < 0) dTheta += 2 * PI
        val n = ceil(abs(dTheta) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val step = dTheta / n
        val k = 4.0 / 3.0 * tan(step / 4)
        var th = theta1
        for (i in 0 until n) {
            val c1 = cos(th); val s1 = sin(th)
            val th2 = th + step
            val c2 = cos(th2); val s2 = sin(th2)
            // Unit-circle control points, then scale by radii, rotate by phi, translate to centre.
            fun px(ux: Double, uy: Double) = (cx + rx * ux * cosP - ry * uy * sinP).toFloat()
            fun py(ux: Double, uy: Double) = (cy + rx * ux * sinP + ry * uy * cosP).toFloat()
            val e1x = c1 - k * s1; val e1y = s1 + k * c1
            val e2x = c2 + k * s2; val e2y = s2 - k * c2
            val endX = if (i == n - 1) x2 else px(c2, s2)
            val endY = if (i == n - 1) y2 else py(c2, s2)
            out += Seg.Cubic(px(e1x, e1y), py(e1x, e1y), px(e2x, e2y), py(e2x, e2y), endX, endY)
            th = th2
        }
    }

    private class Tokens(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipSep() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun peekIsCommand(): Boolean = i < s.length && s[i].isLetter() && s[i] != 'e' && s[i] != 'E'
        fun nextChar(): Char = s[i++]
        fun flag(): Boolean { skipSep(); val c = s[i++]; return c == '1' }
        fun num(): Float {
            skipSep()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++; if (s[i] == '-' || s[i] == '+') i++
                } else break
            }
            if (start == i) throw IllegalArgumentException("Bad SVG path number at $i in '$s'")
            return s.substring(start, i).toFloat()
        }
    }
}
