package com.echobedside.feature.photos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import com.echobedside.core.design.Night
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha

/**
 * Everything drawn over the photo that isn't a control (Photos.dc.html): the legibility scrim,
 * the time + date top-left and the memory caption bottom-left. Content, so no glass (NOTES §3.1).
 *
 * It sits in a hardware layer: re-rendered only when the minute or the caption changes, and
 * composited as one texture while the photos crossfade and zoom underneath.
 */
internal class FrameOverlay(context: Context) : View(context) {
    // .time  font-size 104, weight 600, line-height .95, letter-spacing -.045em, tabular-nums
    private val time = SpecText(104f, 600, -0.045f)
    // .date  20 / 600, rgba(255,255,255,.9), margin-top 6
    private val date = SpecText(20f, 600)
    // caption title 24 / 700, -.015em; subtitle 15 / 600, .82, margin-top 2
    private val title = SpecText(24f, 700, -0.015f)
    private val subtitle = SpecText(15f, 600)
    private val scrim = Paint()

    var timeText = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var dateText = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var captionTitle = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var captionSubtitle = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // text-shadow: 0 2px 18px rgba(0,0,0,.35) on the time group, 0 2px 12px .4 on the caption.
        // CSS blur radius = 2σ; Skia's shadow radius r gives σ ≈ 0.57735·r + 0.5.
        time.paint.setShadowLayer(shadowRadius(18f), 0f, 2f, withAlpha(Color.BLACK, 0.35f))
        date.paint.setShadowLayer(shadowRadius(18f), 0f, 2f, withAlpha(Color.BLACK, 0.35f))
        title.paint.setShadowLayer(shadowRadius(12f), 0f, 2f, withAlpha(Color.BLACK, 0.4f))
        subtitle.paint.setShadowLayer(shadowRadius(12f), 0f, 2f, withAlpha(Color.BLACK, 0.4f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // linear-gradient(180deg, rgba(0,0,0,.35) 0%, transparent 30%, transparent 62%, rgba(0,0,0,.5) 100%)
        scrim.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(withAlpha(Color.BLACK, 0.35f), Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(Color.BLACK, 0.5f)),
            floatArrayOf(0f, 0.30f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, scrim)
        val white = if (night) Night.COLOR else Color.WHITE

        // Time block: left 40, top 28
        val timeLine = 104f * 0.95f
        time.draw(canvas, timeText, 40f, time.baselineIn(28f, timeLine), white)
        date.draw(canvas, dateText, 40f, date.baselineIn(28f + timeLine + 6f), withAlpha(white, 0.9f))

        // Caption: left 40, bottom 30
        val subTop = h - 30f - subtitle.normalLineHeight
        val titleTop = subTop - 2f - title.normalLineHeight
        if (captionTitle.isNotEmpty()) title.draw(canvas, captionTitle, 40f, title.baselineIn(titleTop), white)
        if (captionSubtitle.isNotEmpty()) subtitle.draw(canvas, captionSubtitle, 40f, subtitle.baselineIn(subTop), withAlpha(white, 0.82f))
    }

    override fun invalidate() {
        super.invalidate()
        contentDescription = "$timeText, $dateText. $captionTitle, $captionSubtitle"
    }

    private fun shadowRadius(cssBlur: Float) = ((cssBlur / 2f) - 0.5f) / 0.57735f
}
