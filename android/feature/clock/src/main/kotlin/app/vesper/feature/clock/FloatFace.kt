package app.vesper.feature.clock

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.observeWhileAttached
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * StandBy "Float" face, from design/Float.dc.html: giant two-tone bubble digits that overlap
 * (screen blend), each slightly tilted and lifted, with a gradient fill per glyph.
 *
 * Font: the design's Nunito 900 (stand-in for SF Pro Rounded) from this module's assets
 * ([FloatFont], OFL, licence next to it). Without that file it falls back to Inter 900 with a
 * round-joined outline stroke of [ROUNDING] px, which softens corners toward a rounded heavy look.
 */
class FloatFace(context: Context, services: VesperServices) : SpecFace(context, services) {

    private val nunito = FloatFont.get(context)
    private val digitText = SpecText(380f, 900).apply {
        if (nunito != null) {
            paint.typeface = nunito
            paint.fontVariationSettings = "'wght' 900" // Black
        } else {
            paint.style = Paint.Style.FILL_AND_STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = ROUNDING
        }
        paint.fontFeatureSettings = null // the design has proportional digits here
        paint.blendMode = BlendMode.SCREEN
    }
    /** Line-box metrics of the digit font (SpecText's own are Inter's). */
    private val digitAscent = -digitText.paint.fontMetrics.ascent
    private val digitDescent = digitText.paint.fontMetrics.descent
    private val alarmText = SpecText(18f, 700, -0.01f)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_24)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nextAlarm: NextAlarm? = null
    private var time = ""
    private var alarmLabel: String? = null

    // layout + shaders, rebuilt in update() when the time or colours change
    private class Glyph(val ch: String, val colon: Boolean, val left: Float, val w: Float, val tilt: Float, val lift: Float, val shader: Shader?)
    private var glyphs: List<Glyph> = emptyList()
    private var fillA = intArrayOf()
    private var fillB = intArrayOf()
    private var colonColor = Color.WHITE
    private var alarmColor = Color.WHITE

    init {
        observeWhileAttached(services.alarms.next) { nextAlarm = it; refresh(force = false) }
    }

    override fun onConfigChanged(old: FaceConfig) { time = "" }

    override fun update(): Boolean {
        val t = TimeText.time(cal, is24)
        val a = nextAlarm?.let { TimeText.clock(it.triggerAtMillis, is24) }
        if (t == time && a == alarmLabel) return false
        alarmLabel = a
        if (t != time) { time = t; layout() }
        return true
    }

    private fun layout() {
        val night = config.night
        val base = Colors.toHsl(if (night) Night.COLOR else config.color)
        val mono = base[1] < 8
        val shift = if (night || mono) 0f else 38f
        fillA = bubble(base, 0f)
        fillB = when {
            night -> bubble(floatArrayOf(base[0], base[1], base[2] - 12), 0f)
            mono -> bubble(floatArrayOf(0f, 0f, 62f), 0f)
            else -> bubble(base, shift)
        }
        colonColor = fillB[1]
        alarmColor = if (night) Night.COLOR else Colors.tone(base, shift, 8f)

        val em = digitText.size
        val boxH = LINE * em
        val items = ArrayList<Glyph>()
        var x = 0f
        var k = 0
        time.forEachIndexed { i, c ->
            if (c == ':') {
                x += -18f
                items += Glyph(":", true, x, 52f, 0f, 0f, null)
                x += 52f + 4f
            } else {
                val w = digitText.paint.measureText(c.toString())
                val colors = if (k % 2 == 0) fillA else fillB
                items += Glyph(c.toString(), false, x, w, TILTS[i % 5], LIFTS[i % 5], gradient(w, boxH, colors))
                x += w - 0.09f * em
                k++
            }
        }
        // centre the flex row (margins included) in 960
        val start = (W - x) / 2f
        glyphs = items.map { Glyph(it.ch, it.colon, it.left + start, it.w, it.tilt, it.lift, it.shader) }
    }

    /** linear-gradient(170deg, tone+22 8%, tone 48%, tone−14 100%) across a w×h box. */
    private fun gradient(w: Float, h: Float, colors: IntArray): Shader {
        val ang = Math.toRadians(170.0)
        val dx = sin(ang).toFloat(); val dy = -cos(ang).toFloat()
        val len = abs(w * dx) + abs(h * dy)
        val cx = w / 2; val cy = h / 2
        return LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
            colors, floatArrayOf(0.08f, 0.48f, 1f), Shader.TileMode.CLAMP)
    }

    private fun bubble(hsl: FloatArray, dh: Float) = intArrayOf(Colors.tone(hsl, dh, 22f), Colors.tone(hsl, dh, 0f), Colors.tone(hsl, dh, -14f))

    override fun drawSpec(canvas: Canvas) {
        val em = digitText.size
        val boxH = LINE * em
        val cy = 18f + (H - 18f) / 2f // padding-top 18, items centred
        val top = cy - boxH / 2
        val baseline = (boxH - (digitAscent + digitDescent)) / 2f + digitAscent
        for (g in glyphs) {
            if (g.colon) continue
            canvas.save()
            canvas.translate(g.left, top)
            canvas.rotate(g.tilt, g.w / 2, boxH / 2)
            canvas.translate(0f, g.lift)
            digitText.paint.shader = g.shader
            canvas.drawText(g.ch, 0f, baseline, digitText.paint)
            canvas.restore()
        }
        digitText.paint.shader = null
        // colon: two 52 px dots, gap 46, drawn above the digits (z-index 2, normal blend)
        dotPaint.color = colonColor
        for (g in glyphs) if (g.colon) {
            canvas.drawCircle(g.left + 26f, cy - 75f + 26f, 26f, dotPaint)
            canvas.drawCircle(g.left + 26f, cy + 75f - 26f, 26f, dotPaint)
        }

        alarmLabel?.let {
            val rowH = max(18f, alarmText.normalLineHeight)
            drawIcon(canvas, alarmIcon, 36f, 28f + (rowH - 18f) / 2, 18f, alarmColor)
            alarmText.draw(canvas, it, 36f + 18f + 7f, alarmText.baselineIn(28f, rowH), alarmColor)
        }
    }

    companion object {
        val DEFAULT_COLOR = 0xFFFF375F.toInt()
        val COLORS = listOf(0xFFFF375F, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFFFFFF).map { it.toInt() }
        private const val LINE = 0.78f
        const val ROUNDING = 14f
        private val TILTS = floatArrayOf(-4f, 3f, 0f, -3f, 4f)
        private val LIFTS = floatArrayOf(6f, -10f, 0f, 8f, -6f)
    }
}

/**
 * Nunito for the Float face, drawn at wght 900 (Black): the variable font Google Fonts publishes,
 * `ofl/nunito/Nunito[wght].ttf` from github.com/google/fonts, as `assets/fonts/Nunito-wght.ttf` in this
 * module (SIL Open Font License 1.1, `assets/fonts/OFL-Nunito.txt`). null when the file isn't bundled.
 */
object FloatFont {
    const val ASSET = "fonts/Nunito-wght.ttf"
    private var loaded = false
    private var typeface: Typeface? = null

    fun get(context: Context): Typeface? {
        if (!loaded) {
            loaded = true
            typeface = runCatching { Typeface.createFromAsset(context.assets, ASSET) }.getOrNull()
        }
        return typeface
    }
}
