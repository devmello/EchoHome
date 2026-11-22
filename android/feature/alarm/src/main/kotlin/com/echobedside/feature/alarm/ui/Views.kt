package com.echobedside.feature.alarm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.echobedside.core.design.Motion
import com.echobedside.core.design.SpecText

/**
 * Absolute layout in design px: children are placed at their [Abs] rect, exactly like the
 * `position: absolute` boxes in the .dc.html boards (artboard 960×480 = device px).
 */
open class AbsLayout(context: Context) : ViewGroup(context) {
    class Abs(var x: Float, var y: Float, w: Int, h: Int) : LayoutParams(w, h)

    fun place(v: View, x: Float, y: Float, w: Int, h: Int) {
        val lp = v.layoutParams as? Abs
        if (v.parent !== this) addView(v, Abs(x, y, w, h))
        else if (lp != null && (lp.x != x || lp.y != y || lp.width != w || lp.height != h)) {
            lp.x = x; lp.y = y; lp.width = w; lp.height = h; v.requestLayout()
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as? Abs ?: continue
            c.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as? Abs ?: continue
            val x = Math.round(lp.x); val y = Math.round(lp.y)
            c.layout(x, y, x + lp.width, y + lp.height)
        }
    }

    override fun shouldDelayChildPressedState() = false
}

/** Press feedback for controls: a quick shrink, spring back (NOTES §3.5). */
@SuppressLint("ClickableViewAccessibility")
abstract class PressableView(context: Context) : View(context) {
    var onTap: (() -> Unit)? = null
    var pressScale = 0.95f
    var enabledLook = true
        set(v) { field = v; alpha = if (v) 1f else 0.45f }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (onTap == null || !enabledLook) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(pressScale).scaleY(pressScale).setDuration(90).setInterpolator(Motion.EASE).start()
            MotionEvent.ACTION_UP -> {
                release()
                if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    private fun release() = animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(Motion.SPRING).start()
}

/**
 * A glass capsule button: [glass] + an optional icon + a label, centred as a flex row with [gap].
 * Its width comes from [wrapWidth] when the design sizes it by padding.
 */
class GlassButton(context: Context, spec: GlassSpec, val text: SpecText, var label: String) : PressableView(context) {
    val glass = SpecGlassPainter(spec)
    var icon: Icon? = null
    var iconSize = 0f
    var iconStroke = 2.2f
    var gap = 0f
    var color = android.graphics.Color.WHITE
    var radius = -1f

    fun contentWidth() = (if (icon != null) iconSize + gap else 0f) + text.width(label)
    fun wrapWidth(padL: Float, padR: Float) = Math.ceil((padL + contentWidth() + padR).toDouble()).toInt()

    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Window position of the untransformed view (press scaling shouldn't slide the backdrop).
        var x = left.toFloat(); var y = top.toFloat()
        (parent as? View)?.let { it.getLocationInWindow(loc); x += loc[0]; y += loc[1] }
        glass.draw(c, w, h, radius, x, y)
        var cx = (w - contentWidth()) / 2
        icon?.let { Icons.draw(c, it, cx + iconSize / 2, h / 2, iconSize, color, iconStroke); cx += iconSize + gap }
        text.draw(c, label, cx, text.baselineIn(0f, h), color)
    }
}

/** A filled circle control (no glass: it sits on a glass card or a content card). */
class CircleButton(context: Context) : PressableView(context) {
    var fill = 0
    var ink = 0
    var icon = Icon.PAUSE
    var iconSize = 22f
    var iconStroke = 2.8f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { pressScale = 0.9f }

    fun set(fill: Int, ink: Int, icon: Icon) {
        if (fill == this.fill && ink == this.ink && icon == this.icon) return
        this.fill = fill; this.ink = ink; this.icon = icon; invalidate()
    }

    override fun onDraw(c: Canvas) {
        p.color = fill
        c.drawCircle(width / 2f, height / 2f, width / 2f, p)
        Icons.draw(c, icon, width / 2f, height / 2f, iconSize, ink, iconStroke)
    }
}
