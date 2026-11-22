package com.echobedside.feature.alarm.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.AlarmPhase
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The warm colour wash of the sunrise ramp, over whatever screen is showing (overlay z 250, below
 * the island). The same glow as the ringing screen (Alarm.dc.html's sun), fading in with the
 * ramp's progress so the alarm screen takes over seamlessly. Drawn once; only its alpha changes
 * (1 s linear, every 10 s tick). Never takes touches. In Night mode it still shows: the warm light
 * is the point of the ramp (nothing in the design or NOTES says to skip it).
 */
@SuppressLint("ViewConstructor")
class SunriseOverlay(context: Context, services: EchoServices) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val wash = Paint().apply { color = withAlpha(0xFFFF8C32.toInt(), 0.10f) }

    init {
        alpha = 0f
        visibility = INVISIBLE
        observeWhileAttached(services.alarms.phase) { p ->
            val target = if (p is AlarmPhase.Sunrise) 0.15f + 0.85f * p.progress.pow(1.3f) else 0f
            if (target > 0f) visibility = VISIBLE
            animate().alpha(target).setDuration(if (target > 0f) 1000 else 600)
                .withEndAction { if (target == 0f) visibility = INVISIBLE }.start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // The alarm board's sun box: 1440×900 at (-240, 100) for a 960×480 screen, scale .9.
        val sx = w / 960f
        val cx = w / 2f; val cy = (100f + 450f) * sx
        val rx = 720f * sqrt(2f) * sx * 0.9f; val ry = 450f * sqrt(2f) * sx * 0.9f
        glow.shader = RadialGradient(0f, 0f, rx,
            intArrayOf(0xF2FFC478.toInt(), withAlpha(0xFFFF8228.toInt(), 0.78f), withAlpha(0xFFD23E14.toInt(), 0.45f), withAlpha(0xFF5A141E.toInt(), 0.18f), 0),
            floatArrayOf(0f, 0.22f, 0.42f, 0.6f, 0.72f), Shader.TileMode.CLAMP)
            .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx); postTranslate(cx, cy) }) }
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}
