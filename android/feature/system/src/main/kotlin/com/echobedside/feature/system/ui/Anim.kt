package com.echobedside.feature.system.ui

import android.animation.TimeInterpolator
import android.graphics.Canvas
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.echobedside.core.design.SpecText

/** CSS `ease` = cubic-bezier(0.25, 0.1, 0.25, 1). */
val EASE: TimeInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
val LINEAR: TimeInterpolator = LinearInterpolator()
fun bezier(x1: Float, y1: Float, x2: Float, y2: Float): TimeInterpolator = PathInterpolator(x1, y1, x2, y2)

/**
 * One animated value, CSS-transition style: [to] starts a transition from wherever it is now.
 * Views call [step] in onDraw and keep drawing (postInvalidateOnAnimation) while any is running,
 * so nothing draws at rest.
 */
class Tween(initial: Float) {
    var value = initial; private set
    var target = initial; private set
    private var from = initial
    private var start = 0L
    private var duration = 0L
    private var interp: TimeInterpolator = LINEAR
    val running get() = duration > 0

    fun to(v: Float, durationMs: Long, interpolator: TimeInterpolator, delayMs: Long = 0, now: Long = now()) {
        if (v == target && (running || value == v)) return
        if (durationMs <= 0) { snap(v); return }
        from = value; target = v; start = now + delayMs; duration = durationMs; interp = interpolator
    }

    fun snap(v: Float) { value = v; target = v; from = v; duration = 0 }

    /** Advances to [now]; returns true while still running. */
    fun step(now: Long = now()): Boolean {
        if (duration <= 0) return false
        val t = ((now - start).toFloat() / duration)
        if (t >= 1f) { value = target; duration = 0; return false }
        value = if (t <= 0f) from else from + (target - from) * interp.getInterpolation(t)
        return true
    }

    companion object { fun now() = AnimationUtils.currentAnimationTimeMillis() }
}

/** Colour transition (ARGB lerp), e.g. the HUD pill's background 0.3 s ease. */
class ColorTween(initial: Int) {
    private val t = Tween(1f)
    private var from = initial
    private var to = initial
    var value = initial; private set
    val running get() = t.running
    fun to(c: Int, durationMs: Long, interp: TimeInterpolator = EASE) {
        if (c == to) return
        from = value; to = c
        t.snap(0f); t.to(1f, durationMs, interp)
    }
    fun snap(c: Int) { from = c; to = c; value = c; t.snap(1f) }
    fun step(now: Long = Tween.now()): Boolean {
        val r = t.step(now)
        value = lerpColor(from, to, t.value.coerceIn(0f, 1f))
        return r
    }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

fun lerpColor(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a ushr s) and 255) + (((b ushr s) and 255) - ((a ushr s) and 255)) * t).toInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Keeps a view animating only while [running] says so. */
fun View.keepAnimating(running: Boolean) { if (running) postInvalidateOnAnimation() }

// --- text helpers ---

/** CSS `text-overflow: ellipsis` within [maxWidth]. */
fun SpecText.ellipsize(s: String, maxWidth: Float): String {
    if (width(s) <= maxWidth) return s
    var n = s.length
    while (n > 0 && width(s.substring(0, n).trimEnd() + "…") > maxWidth) n--
    return if (n == 0) "" else s.substring(0, n).trimEnd() + "…"
}

fun SpecText.drawRight(canvas: Canvas, s: String, right: Float, baseline: Float, color: Int) =
    draw(canvas, s, right - width(s), baseline, color)

fun SpecText.drawCentered(canvas: Canvas, s: String, cx: Float, baseline: Float, color: Int) =
    draw(canvas, s, cx - width(s) / 2f, baseline, color)

/** Baseline for a single line of text vertically centred on [cy] (flex align-items: center). */
fun SpecText.baselineCentered(cy: Float, lineHeight: Float = normalLineHeight) = baselineIn(cy - lineHeight / 2f, lineHeight)
