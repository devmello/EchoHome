package app.vesper.feature.settings.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText

/** Small drawing helpers shared by the pairing sheet and the code overlay. */
internal object Draw {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun centered(c: Canvas, t: SpecText, s: String, cx: Float, baseline: Float, color: Int) {
        t.draw(c, s, cx - t.width(s) / 2f, baseline, color)
    }

    fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
        fill.color = color
        fill.style = Paint.Style.FILL
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, fill)
    }

    /** Greedy word wrap into lines no wider than [maxWidth]. */
    fun wrap(t: SpecText, s: String, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in s.split(' ')) {
            val next = if (line.isEmpty()) w else "$line $w"
            if (line.isNotEmpty() && t.width(next) > maxWidth) { out += line; line = w } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** Draws [qr] dark modules in a [px]-wide square at ([l],[t]), [dark] coloured (light = transparent). */
    fun qr(c: Canvas, qr: Qr, l: Float, t: Float, px: Float, dark: Int) {
        val n = qr.size
        val m = px / n
        fill.color = dark
        fill.style = Paint.Style.FILL
        fill.isAntiAlias = false
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!qr[x, y]) { x++; continue }
                val start = x
                while (x < n && qr[x, y]) x++
                // Whole-pixel edges so neighbouring modules never leave hairline gaps.
                c.drawRect(Math.round(l + start * m).toFloat(), Math.round(t + y * m).toFloat(),
                    Math.round(l + x * m).toFloat(), Math.round(t + (y + 1) * m).toFloat(), fill)
            }
        }
        fill.isAntiAlias = true
    }

    fun color(c: Int, night: Boolean) = Night.map(c, night)
}
