package com.echobedside.feature.clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.echobedside.core.design.Night
import com.echobedside.core.design.PreBlur
import com.echobedside.core.design.SpecText
import com.echobedside.core.design.withAlpha
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FaceConfig
import com.echobedside.core.model.NextAlarm
import com.echobedside.core.model.observeWhileAttached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * StandBy "Liquid Glass" clock, from design/Glass.dc.html (the only face that uses clear glass,
 * NOTES §3.1 rule 7): the digits lens the wallpaper, with a soft shadow, a specular band and a rim.
 *
 * No live blur (NOTES §5.2). Per wallpaper / tint / Glass amount, two half-resolution bitmaps
 * are painted once off the main thread: the wallpaper, and the "lens" copy (the same paper with
 * every colour passed through the tint and toward white by 0.14 + 0.5·glass, then lightly
 * pre-blurred by the glass amount). The digits are drawn with a BitmapShader of the lens copy
 * under a 1.08× scale matrix about the screen centre (CSS background-size 108%), so they
 * magnify what's behind them. The drop shadow is a quarter-resolution blurred copy of the text,
 * rebuilt once a minute.
 *
 * Settings: `clock.glass.amount` 0–1 (clear → frosted, default 0.45), `clock.glass.wallpaper`
 * Blue | Dusk | Graphite. Tint = the face colour.
 */
class GlassFace(context: Context, services: EchoServices) : SpecFace(context, services) {

    private val dateText = SpecText(26f, 600, -0.01f).apply { paint.setShadowLayer(9.5f, 0f, 1f, withAlpha(Color.BLACK, 0.25f)) }
    private val timeLens = SpecText(300f, 700, -0.045f).apply { paint.isFilterBitmap = true }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    /** The time as one path: each glyph's overlapping contours merged (Inter variable keeps them),
     *  so the rim stroke shows only the outline, like -webkit-text-stroke on a static font. */
    private val timePath = Path()
    private val glyphPath = Path()
    private val emptyPath = Path()
    private var timeWidth = 0f
    private val alarmText = SpecText(17f, 600, -0.01f)
    private val alarmIcon = context.icon(R.drawable.ic_alarm_22)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val screenRect = RectF(0f, 0f, W, H)
    private val lensMatrix = Matrix()

    private var nextAlarm: NextAlarm? = null
    private var time = ""
    private var dateLine = ""
    private var alarmLabel: String? = null
    private var glass = 0.45f
    private var wallpaperName = "Blue"

    private var wallpaper: Bitmap? = null
    private var lens: Bitmap? = null
    private var lensShader: Shader? = null
    private var shadow: Bitmap? = null
    private val shadowRect = RectF()
    private var builtKey: String? = null
    private var job: Job? = null

    init {
        observeWhileAttached(services.alarms.next) { nextAlarm = it; refresh(force = false) }
        observeWhileAttached(services.settings.changes) { k -> if (k == KEY_GLASS || k == KEY_WALLPAPER) { readSettings(); time = ""; rebuild(); refresh(force = true) } }
        readSettings()
    }

    private fun readSettings() {
        glass = services.settings.getFloat(KEY_GLASS, 0.45f).coerceIn(0f, 1f)
        wallpaperName = services.settings.getString(KEY_WALLPAPER, "Blue").let { if (it in PALETTES) it else "Blue" }
    }

    override fun onConfigChanged(old: FaceConfig) { time = ""; rebuild() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); rebuild() }

    override fun onDetachedFromWindow() {
        job?.cancel(); job = null
        wallpaper?.recycle(); wallpaper = null
        lens?.recycle(); lens = null; lensShader = null
        shadow?.recycle(); shadow = null
        builtKey = null
        super.onDetachedFromWindow()
    }

    private fun rebuild() {
        if (!isAttachedToWindow) return
        val night = config.night
        val key = if (night) "night" else "$wallpaperName/${config.color}/$glass"
        if (key == builtKey) return
        builtKey = key
        if (night) {
            wallpaper?.recycle(); wallpaper = null
            lens?.recycle(); lens = null
            lensShader = LinearGradient(0f, 0f, 0f, H, Colors.argb(0.55f, 190, 24, 14), Colors.argb(0.35f, 120, 12, 6), Shader.TileMode.CLAMP)
            invalidate()
            return
        }
        val pal = PALETTES.getValue(wallpaperName)
        val tint = config.color
        val g = glass
        job?.cancel()
        job = services.scope.launch(Dispatchers.Default) {
            val wp = paintPaper(pal) { c, a -> withAlpha(c, a) }
            val tAmt = if (Colors.isWhite(tint)) 0f else 0.55f
            val ln = paintPaper(pal) { c, a -> Colors.pass(c, tint, tAmt, 0.14f + 0.5f * g, minOf(1f, a * (0.55f + 0.45f * g))) }
            frost(ln, 1f + 5f * g) // pre-blur: half-res px, so 2–12 screen px from clear to frosted
            withContext(Dispatchers.Main) {
                if (builtKey == key && isAttachedToWindow) {
                    wallpaper?.recycle(); lens?.recycle()
                    wallpaper = wp; lens = ln
                    lensShader = BitmapShader(ln, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    invalidate()
                } else { wp.recycle(); ln.recycle() }
            }
        }
    }

    override fun update(): Boolean {
        val t = TimeText.time(cal, is24)
        val d = TimeText.dateLine(cal)
        val a = nextAlarm?.let { TimeText.clock(it.triggerAtMillis, is24) }
        if (t == time && d == dateLine && a == alarmLabel) return false
        if (t != time || shadow == null) { time = t; buildPath(); buildShadow() }
        dateLine = d; alarmLabel = a
        return true
    }

    private fun buildPath() {
        timePath.reset()
        val p = timeLens.paint
        val widths = FloatArray(time.length)
        p.getTextWidths(time, widths)
        val tracking = -0.045f * timeLens.size
        timeWidth = widths.sum() + tracking * time.length
        var x = (W - timeWidth) / 2f
        val baseline = timeLens.baselineIn(TEXT_TOP, 300f)
        for (i in time.indices) {
            glyphPath.reset()
            p.getTextPath(time, i, i + 1, x, baseline, glyphPath)
            glyphPath.op(emptyPath, Path.Op.UNION)
            timePath.addPath(glyphPath)
            x += widths[i] + tracking
        }
    }

    /** text-shadow-like drop shadow: the time, blurred 16 px, at quarter resolution. */
    private fun buildShadow() {
        shadow?.recycle(); shadow = null
        if (config.night || time.isEmpty()) return
        val q = 4f
        val tw = timeWidth
        val pad = 3 * 16f
        val w = ((tw + 2 * pad) / q).toInt() + 1
        val h = ((300f + 2 * pad) / q).toInt() + 1
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(1 / q, 1 / q)
        val left = (W - tw) / 2f - pad
        c.translate(-left, -(TEXT_TOP - pad))
        rimPaint.style = Paint.Style.FILL
        rimPaint.color = Colors.argb(0.18f + 0.2f * glass, 0, 8, 30)
        c.drawPath(timePath, rimPaint)
        rimPaint.style = Paint.Style.STROKE
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        PreBlur.gaussian(px, w, h, 16f / q)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        shadow = bmp
        shadowRect.set(left, TEXT_TOP + 12f - pad, left + w * q, TEXT_TOP + 12f - pad + h * q)
    }

    override fun drawSpec(canvas: Canvas) {
        val night = config.night
        wallpaper?.let { canvas.drawBitmap(it, null, screenRect, bmpPaint) }
        val dateColor = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.86f)

        // date, centred at top 46
        dateText.paint.clearShadowLayer()
        if (!night) dateText.paint.setShadowLayer(9.5f, 0f, 1f, withAlpha(Color.BLACK, 0.25f))
        dateText.drawCentered(canvas, dateLine, W / 2, dateText.baselineIn(46f), dateColor)

        if (time.isNotEmpty()) {
            val x = (W - timeWidth) / 2f
            val baseline = timeLens.baselineIn(TEXT_TOP, 300f)
            // 1. shadow
            shadow?.let { canvas.drawBitmap(it, null, shadowRect, bmpPaint) }
            // 2. lensed body
            val ls = lensShader
            if (ls != null) {
                if (ls is BitmapShader) {
                    lensMatrix.setScale(2f * LENS, 2f * LENS)
                    lensMatrix.postTranslate(-(LENS - 1f) * W / 2f, -(LENS - 1f) * H / 2f)
                    ls.setLocalMatrix(lensMatrix)
                }
                timeLens.paint.shader = ls
                timeLens.draw(canvas, time, x, baseline, Color.WHITE)
            }
            // 3. specular band (text fills keep the counters), then the rim from the merged path
            timeLens.paint.shader = specular(night)
            timeLens.draw(canvas, time, x, baseline, Color.WHITE)
            timeLens.paint.shader = null
            rimPaint.color = if (night) Colors.argb(0.9f, 190, 24, 14) else withAlpha(Color.WHITE, 0.32f + 0.25f * glass)
            canvas.drawPath(timePath, rimPaint)
        }

        // next alarm, centred, bottom 34
        alarmLabel?.let {
            val rowH = max(17f, alarmText.normalLineHeight)
            val top = H - 34f - rowH
            val w = 17f + 8f + alarmText.width(it)
            val x0 = (W - w) / 2f
            drawIcon(canvas, alarmIcon, x0, top + (rowH - 17f) / 2, 17f, dateColor)
            alarmText.draw(canvas, it, x0 + 25f, alarmText.baselineIn(top, rowH), dateColor)
        }
    }

    private var specShader: Shader? = null
    private var specKey: String? = null

    private fun specular(night: Boolean): Shader {
        val key = if (night) "n" else "d$glass"
        specShader?.let { if (key == specKey) return it }
        val s = if (night)
            LinearGradient(0f, 0f, 0f, H, intArrayOf(Colors.argb(0f, 220, 40, 24), Colors.argb(0f, 220, 40, 24), Colors.argb(0.7f, 220, 40, 24), Colors.argb(0f, 190, 24, 14)),
                floatArrayOf(0f, 0.27f, 0.31f, 0.48f), Shader.TileMode.CLAMP)
        else {
            val w = Color.WHITE
            LinearGradient(0f, 0f, 0f, H,
                intArrayOf(withAlpha(w, 0f), withAlpha(w, 0f), withAlpha(w, 0.5f + 0.3f * glass), withAlpha(w, 0.08f), withAlpha(w, 0f), withAlpha(w, 0.18f), withAlpha(w, 0f)),
                floatArrayOf(0f, 0.27f, 0.31f, 0.44f, 0.58f, 0.71f, 0.76f), Shader.TileMode.CLAMP)
        }
        specShader = s; specKey = key
        return s
    }

    companion object {
        const val KEY_GLASS = "clock.glass.amount"
        const val KEY_WALLPAPER = "clock.glass.wallpaper"
        val DEFAULT_COLOR = Color.WHITE
        val COLORS = listOf(0xFFFFFFFF, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF, 0xFF30D158).map { it.toInt() }
        /** Line box top of the 300 px time: content 34..480, line 300 centred. */
        private const val TEXT_TOP = 34f + (480f - 34f - 300f) / 2f
        private const val LENS = 1.08f
        private const val SCALE = 0.5f

        val PALETTES: Map<String, IntArray> = mapOf(
            "Blue" to intArrayOf(0xFF0B2A6B.toInt(), 0xFF2F74FF.toInt(), 0xFF62C6FF.toInt(), 0xFF1840B8.toInt(), 0xFF06112E.toInt()),
            "Dusk" to intArrayOf(0xFF2A1240.toInt(), 0xFFFF6A5C.toInt(), 0xFFB05CFF.toInt(), 0xFF5A2C9E.toInt(), 0xFF12081F.toInt()),
            "Graphite" to intArrayOf(0xFF2A2C31.toInt(), 0xFF6B7380.toInt(), 0xFFA9B2BF.toInt(), 0xFF3E434C.toInt(), 0xFF0E0F12.toInt()),
        )

        /** Glass.dc.html → paper(pal, f), painted bottom layer first, at half resolution. */
        fun paintPaper(pal: IntArray, f: (Int, Float) -> Int): Bitmap {
            val bmp = Bitmap.createBitmap((960 * SCALE).toInt(), (480 * SCALE).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(SCALE, SCALE)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
            val w = 960f; val h = 480f
            p.shader = LinearGradient(0f, 0f, 0f, h, f(pal[0], 1f), f(pal[4], 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            fun ellipse(cxp: Float, cyp: Float, rxp: Float, ryp: Float, color: Int, end: Float) {
                val cx = cxp * w; val cy = cyp * h; val rx = rxp * w; val ry = ryp * h
                p.shader = RadialGradient(cx, cy, rx, intArrayOf(color, withAlpha(color, 0f)), floatArrayOf(0f, end), Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) }) }
                c.drawRect(0f, 0f, w, h, p)
            }
            ellipse(0.75f, 0.92f, 0.6f, 0.7f, f(pal[3], 0.9f), 0.72f)
            ellipse(0.82f, 0.18f, 0.5f, 0.65f, f(pal[2], 0.85f), 0.7f)
            ellipse(0.18f, 0.22f, 0.55f, 0.7f, f(pal[1], 0.9f), 0.7f)
            fun band(color: Int, s0: Float, s1: Float, s2: Float) {
                val ang = Math.toRadians(118.0)
                val dx = sin(ang).toFloat(); val dy = -cos(ang).toFloat()
                val len = abs(w * dx) + abs(h * dy)
                p.shader = LinearGradient(w / 2 - dx * len / 2, h / 2 - dy * len / 2, w / 2 + dx * len / 2, h / 2 + dy * len / 2,
                    intArrayOf(withAlpha(color, 0f), color, withAlpha(color, 0f)), floatArrayOf(s0, s1, s2), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
            }
            band(f(pal[2], 0.5f), 0.5f, 0.6f, 0.71f)
            band(f(pal[1], 0.55f), 0.26f, 0.36f, 0.47f)
            return bmp
        }

        /** Light pre-blur of the lens copy ("frosted"), in place. */
        fun frost(bmp: Bitmap, sigma: Float) {
            val w = bmp.width; val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            PreBlur.gaussian(px, w, h, sigma)
            bmp.setPixels(px, 0, w, 0, 0, w, h)
        }
    }
}
