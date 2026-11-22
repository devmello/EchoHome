package app.vesper.feature.photos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha

/**
 * What's drawn over the photo that isn't a control (Photos.dc.html): the legibility scrim, the
 * time + date top-left and the memory caption bottom-left. Content, so no glass (NOTES §3.1).
 *
 * Two bands, each in its own hardware layer: [Part.TOP] (y 0–200: top scrim, time, date) and
 * [Part.BOTTOM] (y 298–480: bottom scrim, caption). They're re-rendered only when the minute or
 * the caption changes and composited as textures while the photos crossfade and zoom; the
 * scrim's transparent middle (30–62 %) isn't composited at all.
 */
@SuppressLint("ViewConstructor")
internal class FrameOverlay(context: Context, private val part: Part) : View(context) {
    enum class Part(val top: Int, val height: Int) { TOP(0, 200), BOTTOM(SCRIM_CLEAR_TO, SCREEN_H - SCRIM_CLEAR_TO) }

    // time 104 / 600, line-height .95, letter-spacing -.045em, tabular-nums
    private val time = SpecText(104f, 600, -0.045f)
    // date 20 / 600, rgba(255,255,255,.9), margin-top 6
    private val date = SpecText(20f, 600)
    // caption title 24 / 700, -.015em; subtitle 15 / 600, .82, margin-top 2
    private val title = SpecText(24f, 700, -0.015f)
    private val subtitle = SpecText(15f, 600)
    private val scrim = Paint()

    var timeText = ""
        set(v) { if (v != field) { field = v; changed() } }
    var dateText = ""
        set(v) { if (v != field) { field = v; changed() } }
    var captionTitle = ""
        set(v) { if (v != field) { field = v; changed() } }
    var captionSubtitle = ""
        set(v) { if (v != field) { field = v; changed() } }
    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // text-shadow: 0 2px 18px rgba(0,0,0,.35) on the time group, 0 2px 12px .4 on the caption.
        // CSS blur radius = 2σ; Skia's shadow radius r gives σ ≈ 0.57735·r + 0.5.
        time.paint.setShadowLayer(shadowRadius(18f), 0f, 2f, withAlpha(Color.BLACK, 0.35f))
        date.paint.setShadowLayer(shadowRadius(18f), 0f, 2f, withAlpha(Color.BLACK, 0.35f))
        title.paint.setShadowLayer(shadowRadius(12f), 0f, 2f, withAlpha(Color.BLACK, 0.4f))
        subtitle.paint.setShadowLayer(shadowRadius(12f), 0f, 2f, withAlpha(Color.BLACK, 0.4f))
        // linear-gradient(180deg, rgba(0,0,0,.35) 0%, transparent 30%, transparent 62%, rgba(0,0,0,.5) 100%)
        scrim.shader = when (part) {
            Part.TOP -> LinearGradient(0f, 0f, 0f, SCREEN_H * 0.30f, withAlpha(Color.BLACK, 0.35f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            Part.BOTTOM -> LinearGradient(0f, 0f, 0f, part.height.toFloat(), Color.TRANSPARENT, withAlpha(Color.BLACK, 0.5f), Shader.TileMode.CLAMP)
        }
    }

    private fun changed() {
        invalidate()
        contentDescription = if (part == Part.TOP) "$timeText, $dateText" else "$captionTitle, $captionSubtitle"
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val white = if (night) Night.COLOR else Color.WHITE
        when (part) {
            Part.TOP -> {
                canvas.drawRect(0f, 0f, w, SCREEN_H * 0.30f, scrim)
                // left 40, top 28
                val timeLine = 104f * 0.95f
                time.draw(canvas, timeText, 40f, time.baselineIn(28f, timeLine), white)
                date.draw(canvas, dateText, 40f, date.baselineIn(28f + timeLine + 6f), withAlpha(white, 0.9f))
            }
            Part.BOTTOM -> {
                canvas.drawRect(0f, 0f, w, height.toFloat(), scrim)
                // left 40, bottom 30 (in screen px; this view starts at y = part.top)
                val subTop = SCREEN_H - 30f - subtitle.normalLineHeight - part.top
                val titleTop = subTop - 2f - title.normalLineHeight
                if (captionTitle.isNotEmpty()) title.draw(canvas, captionTitle, 40f, title.baselineIn(titleTop), white)
                if (captionSubtitle.isNotEmpty()) subtitle.draw(canvas, captionSubtitle, 40f, subtitle.baselineIn(subTop), withAlpha(white, 0.82f))
            }
        }
    }

    private fun shadowRadius(cssBlur: Float) = ((cssBlur / 2f) - 0.5f) / 0.57735f

    companion object {
        const val SCREEN_H = 480
        /** The scrim is clear from 30 % to 62 % of the height; the bottom band starts there. */
        const val SCRIM_CLEAR_TO = 298 // 0.62 × 480 = 297.6
    }
}
