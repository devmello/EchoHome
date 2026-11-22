package app.vesper.feature.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.NextAlarm
import app.vesper.core.model.observeWhileAttached
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

/**
 * StandBy "Solar" face, from design/Solar.dc.html: light-weight time beside a glowing sun rising
 * off the lower-right corner, in the chosen colour.
 *
 * The background (radial sky, corona, horizon streak, the disc and its two glows) only changes
 * with the colour or Night, so it's painted once into a half-resolution bitmap off the main
 * thread (all soft gradients, so half resolution is invisible) and drawn scaled each frame.
 */
class SolarFace(context: Context, services: VesperServices) : SpecFace(context, services) {

    private val dateText = SpecText(24f, 600, -0.01f)
    private val timeText = SpecText(196f, 400, -0.04f)
    private val alarmText = SpecText(18f, 600, -0.015f)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_22)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bgRect = RectF(0f, 0f, W, H)

    private var nextAlarm: NextAlarm? = null
    private var time = ""
    private var dateLine = ""
    private var alarmLabel: String? = null
    private var background: Bitmap? = null
    private var backgroundKey: Pair<Int, Boolean>? = null
    private var job: kotlinx.coroutines.Job? = null

    init {
        observeWhileAttached(services.alarms.next) { nextAlarm = it; refresh(force = false) }
    }

    override fun onConfigChanged(old: FaceConfig) = rebuildBackground()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rebuildBackground()
    }

    override fun onDetachedFromWindow() {
        job?.cancel(); job = null
        background?.recycle(); background = null; backgroundKey = null
        super.onDetachedFromWindow()
    }

    private fun rebuildBackground() {
        if (!isAttachedToWindow) return
        val key = config.color to config.night
        if (key == backgroundKey && background != null) return
        backgroundKey = key
        job?.cancel()
        job = services.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val bmp = paintBackground(key.first, key.second)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (backgroundKey == key && isAttachedToWindow) {
                    background?.recycle(); background = bmp; invalidate()
                } else bmp.recycle()
            }
        }
    }

    override fun update(): Boolean {
        val t = TimeText.time(cal, is24)
        val d = TimeText.dateLine(cal)
        val a = nextAlarm?.let { TimeText.clock(it.triggerAtMillis, is24) }
        if (t == time && d == dateLine && a == alarmLabel) return false
        time = t; dateLine = d; alarmLabel = a
        return true
    }

    override fun drawSpec(canvas: Canvas) {
        background?.let { canvas.drawBitmap(it, null, bgRect, bmpPaint) }
        val night = config.night
        val ac = if (night) NIGHT_AC else config.color
        val fg = if (night) Night.COLOR else Color.WHITE
        val fg2 = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.72f)
        val dateColor = if (night) Night.COLOR else Colors.mix(ac, true, 0.35f)

        // Column at left 56, centred vertically, gap 10; the alarm row has margin-top 10
        val dateH = dateText.normalLineHeight
        val timeH = 0.82f * timeText.size
        val alarmH = max(18f, alarmText.normalLineHeight)
        val label = alarmLabel
        val total = dateH + 10f + timeH + (if (label != null) 20f + alarmH else 0f)
        var y = (H - total) / 2f
        dateText.draw(canvas, dateLine, 56f, dateText.baselineIn(y), dateColor)
        y += dateH + 10f
        timeText.draw(canvas, time, 56f - 8f, timeText.baselineIn(y, timeH), fg)
        y += timeH + 20f
        if (label != null) {
            drawIcon(canvas, alarmIcon, 56f, y + (alarmH - 18f) / 2, 18f, fg2)
            alarmText.draw(canvas, label, 56f + 18f + 8f, alarmText.baselineIn(y, alarmH), fg2)
        }
    }

    companion object {
        val DEFAULT_COLOR = 0xFFFF9F0A.toInt()
        val COLORS = listOf(0xFFFF9F0A, 0xFFFF453A, 0xFFFF375F, 0xFFBF5AF2, 0xFF0A84FF, 0xFF64D2FF, 0xFF30D158).map { it.toInt() }
        /** Night uses a deep red for the sun so the red text stays the brightest thing. */
        val NIGHT_AC = 0xFF7A0D07.toInt()
        private const val SCALE = 0.5f

        /** CSS blur sigma → Skia BlurMaskFilter radius (sigma = 0.57735·r + 0.5). */
        private fun radiusFor(sigma: Float) = (sigma - 0.5f) / 0.57735f

        /** Paints the static background at half resolution (any thread). */
        fun paintBackground(color: Int, night: Boolean): Bitmap {
            val ac = if (night) NIGHT_AC else color
            val bmp = Bitmap.createBitmap((960 * SCALE).toInt(), (480 * SCALE).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(SCALE, SCALE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

            // bg: radial-gradient(ellipse 80% 90% at 85% 100%, mix(ac, black, .72) 0%, #000 70%)
            c.drawColor(Color.BLACK)
            val rx = 0.8f * 960f; val ry = 0.9f * 480f
            p.shader = RadialGradient(816f, 480f, rx, intArrayOf(Colors.mix(ac, false, 0.72f), Color.BLACK), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, 816f, 480f) }) }
            c.drawRect(0f, 0f, 960f, 480f, p)

            // corona: 1220 px circle at (190, -120), gradient to the farthest corner (610·√2)
            val coronaR = 610f * sqrt(2f)
            val c0 = Colors.mix(ac, true, 0.1f, if (night) 0.35f else 0.55f)
            val c1 = Colors.mix(ac, false, 0.2f, 0.22f)
            val c2 = Colors.mix(ac, false, 0.4f, 0.06f)
            p.shader = RadialGradient(800f, 490f, coronaR, intArrayOf(c0, c1, c2, withAlpha(c2, 0f)), floatArrayOf(0f, 0.22f, 0.42f, 0.6f), Shader.TileMode.CLAMP)
            c.drawCircle(800f, 490f, 610f, p)

            // streak along the horizon: 1000×6 at (260, 452), blur(3px)
            val sc = Colors.mix(ac, true, 0.6f, if (night) 0.25f else 0.7f)
            p.shader = LinearGradient(260f, 0f, 1260f, 0f, intArrayOf(withAlpha(sc, 0f), sc, withAlpha(sc, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            p.maskFilter = BlurMaskFilter(radiusFor(3f), BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(260f, 452f, 1260f, 458f), 3f, 3f, p)

            // disc glows: box-shadow 0 0 160px 40px c2 (sigma 80), then 0 0 60px 10px c1 (sigma 30)
            p.shader = null
            p.color = Colors.mix(ac, false, 0f, 0.35f)
            p.maskFilter = BlurMaskFilter(radiusFor(80f), BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(800f, 480f, 230f + 40f, p)
            p.color = Colors.mix(ac, true, 0.2f, if (night) 0.3f else 0.55f)
            p.maskFilter = BlurMaskFilter(radiusFor(30f), BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(800f, 480f, 230f + 10f, p)
            p.maskFilter = null

            // the disc: 460 px at (570, 250), radial gradient to the farthest corner (230·√2)
            val d0 = if (night) Colors.mix(ac, true, 0.15f) else Colors.mix(ac, true, 0.85f)
            val d1 = Colors.mix(ac, true, if (night) 0.05f else 0.45f)
            val d2 = Colors.mix(ac, false, 0f)
            val d3 = Colors.mix(ac, false, 0.25f)
            p.shader = RadialGradient(800f, 480f, 230f * sqrt(2f), intArrayOf(d0, d1, d2, d3), floatArrayOf(0f, 0.3f, 0.62f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(800f, 480f, 230f, p)
            return bmp
        }
    }
}
