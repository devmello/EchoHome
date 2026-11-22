package app.vesper.feature.alarm.ui

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** The SVG icons from Alarm.dc.html / Timers.dc.html, redrawn on a 24-unit viewBox. */
enum class Icon { ALARM, SNOOZE, CLOSE, PLUS, PAUSE, PLAY, REPEAT, BULB, SUN, CLOUD, MOON, RAIN }

object Icons {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val playFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; pathEffect = CornerPathEffect(2.2f) }
    private val p = Path()
    private val r = RectF()

    private val repeat: Path = Path().apply {
        moveTo(12f, 5f); lineTo(12f, 2f); lineTo(7f, 6f); lineTo(12f, 10f); lineTo(12f, 7f)
        arcTo(RectF(7f, 7f, 17f, 17f), -90f, 270f)   // a5 5 0 1 1 -5 5
        lineTo(5f, 12f)
        arcTo(RectF(5f, 5f, 19f, 19f), 180f, -270f)  // a7 7 0 1 0 7 -7 (back to the top, counter-clockwise)
        close()
        fillType = Path.FillType.WINDING
    }

    private val cloud: Path = Path().apply {
        addCircle(7.5f, 14f, 4f, Path.Direction.CW)
        op(Path().apply { addCircle(12.5f, 10.5f, 5.5f, Path.Direction.CW) }, Path.Op.UNION)
        op(Path().apply { addCircle(17.5f, 14.5f, 3.5f, Path.Direction.CW) }, Path.Op.UNION)
        op(Path().apply { addRect(7.5f, 13f, 17.5f, 18f, Path.Direction.CW) }, Path.Op.UNION)
    }

    private val bulb: Path = Path().apply {
        val head = Path().apply { addCircle(12f, 9f, 7f, Path.Direction.CW) }
        val neck = Path().apply { addRoundRect(RectF(8f, 11f, 16f, 19f), 2f, 2f, Path.Direction.CW) }
        op(head, neck, Path.Op.UNION)
        val base = Path().apply {
            moveTo(10f, 20f); lineTo(14f, 20f); lineTo(14f, 21f)
            arcTo(RectF(10f, 19f, 14f, 23f), 0f, 180f); close()
        }
        op(base, Path.Op.UNION)
    }

    /**
     * Draws [icon] centred at ([cx],[cy]) at [size] px. [strokeWidth] is in viewBox units, like the
     * SVG's stroke-width (it scales with the icon).
     */
    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, strokeWidth: Float = 2.2f) {
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        val s = size / 24f
        c.scale(s, s)
        stroke.color = color; stroke.strokeWidth = strokeWidth
        fill.color = color; playFill.color = color
        when (icon) {
            Icon.ALARM -> {
                c.drawCircle(12f, 13f, 8f, stroke)
                p.reset(); p.moveTo(12f, 9f); p.lineTo(12f, 13f); p.lineTo(14.5f, 15f)
                p.moveTo(5f, 3f); p.lineTo(2f, 6f); p.moveTo(22f, 6f); p.lineTo(19f, 3f)
                c.drawPath(p, stroke)
            }
            Icon.SNOOZE -> {
                p.reset()
                p.moveTo(4f, 5f); p.rLineTo(6f, 0f); p.rLineTo(-6f, 7f); p.rLineTo(6f, 0f)
                p.moveTo(13f, 10f); p.rLineTo(4f, 0f); p.rLineTo(-4f, 5f); p.rLineTo(4f, 0f)
                p.moveTo(18.5f, 3f); p.rLineTo(3f, 0f); p.rLineTo(-3f, 3.5f); p.rLineTo(3f, 0f)
                c.drawPath(p, stroke)
            }
            Icon.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, stroke); c.drawLine(18f, 6f, 6f, 18f, stroke) }
            Icon.PLUS -> { c.drawLine(12f, 5f, 12f, 19f, stroke); c.drawLine(5f, 12f, 19f, 12f, stroke) }
            Icon.PAUSE -> { c.drawRect(7f, 4.5f, 10.4f, 19.5f, fill); c.drawRect(13.6f, 4.5f, 17f, 19.5f, fill) }
            Icon.PLAY -> {
                // M8 5.3 … rounded triangle: (8,4)…(20.6,12)…(8,20) with r≈1.3 corners
                p.reset(); p.moveTo(7.7f, 3.6f); p.lineTo(20.9f, 12f); p.lineTo(7.7f, 20.4f); p.close()
                c.drawPath(p, playFill)
            }
            Icon.REPEAT -> c.drawPath(repeat, fill)
            Icon.BULB -> c.drawPath(bulb, fill)
            Icon.SUN -> {
                c.drawCircle(12f, 12f, 4.2f, stroke)
                p.reset()
                p.moveTo(12f, 2.5f); p.rLineTo(0f, 2.2f); p.moveTo(12f, 19.3f); p.rLineTo(0f, 2.2f)
                p.moveTo(2.5f, 12f); p.rLineTo(2.2f, 0f); p.moveTo(19.3f, 12f); p.rLineTo(2.2f, 0f)
                p.moveTo(5.3f, 5.3f); p.rLineTo(1.6f, 1.6f); p.moveTo(17.1f, 17.1f); p.rLineTo(1.6f, 1.6f)
                p.moveTo(5.3f, 18.7f); p.rLineTo(1.6f, -1.6f); p.moveTo(17.1f, 6.9f); p.rLineTo(1.6f, -1.6f)
                c.drawPath(p, stroke)
            }
            Icon.CLOUD, Icon.RAIN -> {
                c.drawPath(cloud, stroke)
                if (icon == Icon.RAIN) { c.drawLine(9f, 20.5f, 8f, 22.5f, stroke); c.drawLine(14f, 20.5f, 13f, 22.5f, stroke) }
            }
            Icon.MOON -> {
                p.reset()
                r.set(3.5f, 3.5f, 20.5f, 20.5f); p.addOval(r, Path.Direction.CW)
                val cut = Path().apply { addCircle(16.5f, 8f, 7f, Path.Direction.CW) }
                p.op(cut, Path.Op.DIFFERENCE)
                c.drawPath(p, stroke)
            }
        }
        c.restore()
    }
}
