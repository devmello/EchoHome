package com.echobedside.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The UI typeface: Inter v4.1 variable (assets/fonts/InterVariable.ttf, SIL OFL 1.1), axes
 * wght 100–900 and opsz 14–32. Loaded once and stored uncompressed in the APK so it's mmapped;
 * each [SpecText] picks its weight and optical size with Paint variation settings, which share
 * the one font buffer.
 */
object Fonts {
    private var inter: Typeface? = null

    fun init(context: Context) {
        if (inter == null) inter = Typeface.createFromAsset(context.assets, "fonts/InterVariable.ttf")
    }

    val sans: Typeface get() = inter ?: Typeface.SANS_SERIF

    /** CSS `font-optical-sizing: auto`: opsz follows the px size, clamped to the font's range. */
    fun variation(weight: Int, sizePx: Float): String =
        "'wght' $weight, 'opsz' ${sizePx.coerceIn(14f, 32f)}"
}

/**
 * One text style from the HTML designs, laid out the way the browser does it, so px values from
 * the design files (design/NAME.dc.html) can be used 1:1 (artboards are 960×480 device pixels):
 *  - `letter-spacing` (in em) is added after every character, including the last, as in Chrome.
 *  - Tabular numerals (`font-variant-numeric: tabular-nums`).
 *  - Vertical placement uses CSS line boxes: [baselineIn] centres the font's ascent+descent in a
 *    line box of the given height (half-leading), and [normalLineHeight] is `line-height: normal`.
 */
class SpecText(sizePx: Float, weight: Int, private val trackingEm: Float = 0f) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = sizePx
        typeface = Fonts.sans
        fontVariationSettings = Fonts.variation(weight, sizePx)
        fontFeatureSettings = "'tnum'"
    }
    private val tracking = trackingEm * sizePx
    private val fm = paint.fontMetrics
    /** Ascent above the baseline, positive px. */
    val ascent = -fm.ascent
    /** Descent below the baseline, positive px. */
    val descent = fm.descent
    val normalLineHeight = ascent + descent
    val size = sizePx

    private var widths = FloatArray(16)

    fun width(s: String): Float {
        ensure(s.length)
        paint.getTextWidths(s, widths)
        var w = 0f
        for (i in s.indices) w += widths[i] + tracking
        return w
    }

    /** Baseline y for text in a CSS line box that starts at [top] and is [lineHeight] tall. */
    fun baselineIn(top: Float, lineHeight: Float = normalLineHeight): Float =
        top + (lineHeight - normalLineHeight) / 2f + ascent

    /** Draws [s] starting at [x] (left edge) on [baseline]; returns the x after the last character. */
    fun draw(canvas: Canvas, s: String, x: Float, baseline: Float, color: Int): Float {
        paint.color = color
        ensure(s.length)
        paint.getTextWidths(s, widths)
        var cx = x
        for (i in s.indices) {
            canvas.drawText(s, i, i + 1, cx, baseline, paint)
            cx += widths[i] + tracking
        }
        return cx
    }

    private fun ensure(n: Int) {
        if (widths.size < n) widths = FloatArray(n * 2)
    }
}
