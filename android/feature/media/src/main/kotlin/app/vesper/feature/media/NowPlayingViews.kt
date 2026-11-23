package app.vesper.feature.media

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.GlassLayout
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

/** Layout of NowPlaying.dc.html at 960×480 (device px), derived from the CSS flex column. */
internal object NP {
    const val W = 960f
    const val H = 480f
    const val ART_L = 56f
    const val ART_T = 78f
    const val COL_L = 424f
    const val COL_R = 904f
    const val COL_W = COL_R - COL_L
    const val TITLE_T = 86f
    const val TITLE_LH = 44f            // 40px × line-height 1.1
    const val ARTIST_T = TITLE_T + TITLE_LH + 4f
    val artistLh get() = Text.artist.normalLineHeight
    val seekT get() = ARTIST_T + artistLh + 30f   // the 22 px seek button
    val labelsT get() = seekT + 22f + 4f
    val transportT get() = labelsT + Text.label.normalLineHeight + 14f
    const val TRANSPORT_H = 96f
    const val BAR_T = 392f             // 480 - 36 bottom - 52
    const val BAR_H = 52f
    const val ROUTE_L = COL_R - BAR_H
    const val VOLUME_W = ROUTE_L - 12f - COL_L
    const val POPOVER_W = 300f
    const val POPOVER_BOTTOM = 380f    // bottom: 100px
}

/** The design's text styles. Created lazily after Fonts.init. */
internal object Text {
    val source by lazy { SpecText(15f, 600, -0.01f) }
    val clock by lazy { SpecText(17f, 600, -0.01f) }
    val title by lazy { SpecText(40f, 700, -0.03f) }
    val artist by lazy { SpecText(22f, 500, -0.015f) }
    val label by lazy { SpecText(14f, 600) }
    val popHeader by lazy { SpecText(13f, 700, 0.06f) }
    val popName by lazy { SpecText(16f, 600) }
    val popSub by lazy { SpecText(13f, 500) }
    val chip by lazy { SpecText(12f, 600) }
    val button by lazy { SpecText(15f, 600) }
    val emptyTitle by lazy { SpecText(28f, 700, -0.02f) }
    val emptySub by lazy { SpecText(17f, 500, -0.01f) }

    fun ellipsize(t: SpecText, s: String, max: Float): String {
        if (t.width(s) <= max) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (t.width(s.substring(0, mid).trimEnd() + "…") <= max) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo).trimEnd() + "…"
    }
}

/** A tinted vector icon drawn at a fixed px size. */
internal class Icon(context: Context, res: Int) {
    private val d: Drawable = context.getDrawable(res)!!.mutate()
    fun draw(c: Canvas, x: Float, y: Float, size: Float, color: Int) {
        d.setBounds(0, 0, size.roundToInt(), size.roundToInt())
        d.setTint(color)
        c.save(); c.translate(x, y); d.draw(c); c.restore()
    }
}

/**
 * Content layer: the base colour, three pre-blurred colour blobs (only their translation
 * animates, NOTES §5.2) and the dark shade. Blob masks are ALPHA_8 at 1/8 scale, blurred once,
 * and coloured with the paint, so a palette change never re-blurs.
 */
internal class AmbientView(context: Context) : View(context) {
    private class Blob(val l: Float, val t: Float, val w: Float, val h: Float, val blur: Float, val opacity: Float) {
        val pad = blur * 3f
        val dst = RectF()
        var mask: Bitmap? = null
    }

    private val blobs = arrayOf(
        Blob(-160f, -220f, 680f, 680f, 80f, 0.85f),
        Blob(420f, 120f, 720f, 620f, 90f, 0.8f),
        Blob(300f, -260f, 520f, 460f, 80f, 0.6f),
    )
    var base = AmbientPalette.NEUTRAL.base
    val colors = intArrayOf(AmbientPalette.NEUTRAL.a, AmbientPalette.NEUTRAL.b, AmbientPalette.NEUTRAL.c)
    var night = false
        set(v) { field = v; invalidate() }
    /** Off: just the base colour (empty state). */
    var blobsVisible = true
        set(v) { field = v; invalidate() }

    private val from = FloatArray(6)
    private val to = FloatArray(6)
    private var frac = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, NP.H, withAlpha(Color.BLACK, 0.18f), withAlpha(Color.BLACK, 0.38f), Shader.TileMode.CLAMP)
    }

    fun setPalette(p: AmbientPalette) { base = p.base; colors[0] = p.a; colors[1] = p.b; colors[2] = p.c; invalidate() }

    /** The design's drift (ax = 40·sin(t/6) …) at wall time [t] seconds. */
    private fun offsets(t: Double, out: FloatArray) {
        out[0] = (40 * sin(t / 6)).toFloat(); out[1] = (30 * cos(t / 7)).toFloat()
        out[2] = (50 * cos(t / 8)).toFloat(); out[3] = (36 * sin(t / 5)).toFloat()
        out[4] = (60 * sin(t / 9 + 1)).toFloat(); out[5] = (40 * cos(t / 6 + 2)).toFloat()
    }

    /** Starts a 1 s linear step: from where the blobs are now to their position at [t] + 1 s. */
    fun step(tSeconds: Double) {
        for (i in 0 until 6) from[i] = from[i] + (to[i] - from[i]) * frac
        offsets(tSeconds + 1.0, to)
        frac = 0f
    }

    fun jumpTo(tSeconds: Double) { offsets(tSeconds, from); offsets(tSeconds, to); frac = 1f; invalidate() }

    fun setFraction(f: Float) { frac = f; invalidate() }

    private fun ensureMasks() {
        for (b in blobs) if (b.mask == null) {
            val s = 8f
            val bw = ceil((b.w + 2 * b.pad) / s).toInt(); val bh = ceil((b.h + 2 * b.pad) / s).toInt()
            val argb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            Canvas(argb).drawOval(b.pad / s, b.pad / s, (b.pad + b.w) / s, (b.pad + b.h) / s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            val px = IntArray(bw * bh)
            argb.getPixels(px, 0, bw, 0, 0, bw, bh)
            PreBlur.gaussian(px, bw, bh, b.blur / s)
            argb.setPixels(px, 0, bw, 0, 0, bw, bh)
            b.mask = argb.extractAlpha()
            argb.recycle()
        }
    }

    init {
        // The layer is blurred by ≥ 80 px anyway, so render it at 1/4 size into a hardware layer
        // and let the compositor upscale it: ~30k px of overdraw per frame instead of ~2M.
        pivotX = 0f; pivotY = 0f
        scaleX = SCALE; scaleY = SCALE
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.scale(1f / SCALE, 1f / SCALE)
        drawInto(canvas)
        canvas.restore()
    }

    companion object {
        const val SCALE = 4f
    }

    private fun drawInto(canvas: Canvas) {
        if (night) { canvas.drawColor(Color.BLACK); return }
        canvas.drawColor(base)
        if (blobsVisible) {
            ensureMasks()
            for ((i, b) in blobs.withIndex()) {
                val dx = from[2 * i] + (to[2 * i] - from[2 * i]) * frac
                val dy = from[2 * i + 1] + (to[2 * i + 1] - from[2 * i + 1]) * frac
                b.dst.set(b.l - b.pad + dx, b.t - b.pad + dy, b.l + b.w + b.pad + dx, b.t + b.h + b.pad + dy)
                paint.color = withAlpha(colors[i], b.opacity)
                canvas.drawBitmap(b.mask!!, null, b.dst, paint)
            }
        }
        canvas.drawRect(0f, 0f, NP.W, NP.H, shade)
    }

    /** A 1/4-size software render of the current background, for the glass backdrop. */
    fun snapshot(): Bitmap {
        val bmp = Bitmap.createBitmap((NP.W / 4).toInt(), (NP.H / 4).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(0.25f, 0.25f)
        drawInto(c)
        return bmp
    }
}

/** Album art (content: never glass), with its soft shadow; scales down when paused. */
internal class ArtView(context: Context) : View(context) {
    companion object { const val M = 120f; const val SIZE = 324f; const val R = 22f }

    var art: Bitmap? = null
        set(v) { if (field !== v) { field = v; shaderFor = null; invalidate() } }
    var night = false
        set(v) { field = v; invalidate() }

    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = withAlpha(Color.WHITE, 0.12f) }
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = withAlpha(Color.BLACK, 0.5f) }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = Icon(context, R_note)
    private var shaderFor: Bitmap? = null
    private val rect = RectF(M, M, M + SIZE, M + SIZE)
    private val shadowDst = RectF()
    private val shadow: Bitmap by lazy { softShadow(SIZE, SIZE, R, 30f) }
    private val nightFilter = ColorMatrixColorFilter(ColorMatrix().apply {
        // Luminance → Night red (#BE180E), so the art stays legible but monochrome.
        val r = Color.red(Night.COLOR) / 255f; val g = Color.green(Night.COLOR) / 255f; val b = Color.blue(Night.COLOR) / 255f
        set(floatArrayOf(
            0.3f * r, 0.59f * r, 0.11f * r, 0f, 0f,
            0.3f * g, 0.59f * g, 0.11f * g, 0f, 0f,
            0.3f * b, 0.59f * b, 0.11f * b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    })

    init {
        pivotX = M + SIZE / 2f
        pivotY = M + SIZE / 2f
    }

    override fun onDraw(canvas: Canvas) {
        if (!night) {
            // box-shadow: 0 24px 60px rgba(0,0,0,.5) → sigma 30, 3 sigma margin.
            val pad = 90f
            shadowDst.set(M - pad, M - pad + 24f, M + SIZE + pad, M + SIZE + pad + 24f)
            canvas.drawBitmap(shadow, null, shadowDst, shadowPaint)
        }
        val a = art
        if (a != null) {
            if (shaderFor !== a) {
                artPaint.shader = BitmapShader(a, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(SIZE / a.width, SIZE / a.height); postTranslate(M, M) })
                }
                shaderFor = a
            }
            artPaint.colorFilter = if (night) nightFilter else null
            canvas.drawRoundRect(rect, R, R, artPaint)
        } else {
            placeholder.shader = LinearGradient(0f, M, 0f, M + SIZE, 0xFF3A3A40.toInt(), 0xFF1C1C20.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, R, R, placeholder)
            note.draw(canvas, M + SIZE / 2 - 48f, M + SIZE / 2 - 48f, 96f, if (night) withAlpha(Night.COLOR, 0.5f) else withAlpha(Color.WHITE, 0.4f))
        }
        if (!night) {
            rect.inset(-0.25f, -0.25f)
            canvas.drawRoundRect(rect, R + 0.25f, R + 0.25f, ring)
            rect.inset(0.25f, 0.25f)
        }
    }
}

/** A pre-blurred rounded-rect shadow mask (ALPHA_8 at 1/4 scale), 3 sigma of margin on each side. */
internal fun softShadow(w: Float, h: Float, r: Float, sigma: Float): Bitmap {
    val s = 4f
    val pad = 3 * sigma
    val bw = ceil((w + 2 * pad) / s).toInt(); val bh = ceil((h + 2 * pad) / s).toInt()
    val argb = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    Canvas(argb).drawRoundRect(pad / s, pad / s, (pad + w) / s, (pad + h) / s, r / s, r / s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    val px = IntArray(bw * bh)
    argb.getPixels(px, 0, bw, 0, 0, bw, bh)
    PreBlur.gaussian(px, bw, bh, sigma / s)
    argb.setPixels(px, 0, bw, 0, 0, bw, bh)
    return argb.extractAlpha().also { argb.recycle() }
}

/** Top bar (source + clock), title and artist. Draw-only, never takes touches. */
internal class InfoView(context: Context) : View(context) {
    var sourceText = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var sourceIcon = R_airplay
        set(v) { if (v != field) { field = v; icon = Icon(context, v); invalidate() } }
    var clock = ""
        set(v) { if (v != field) { field = v; invalidate() } }
    var title = ""
        set(v) { if (v != field) { field = v; titleFit = null; invalidate() } }
    var subtitle = ""
        set(v) { if (v != field) { field = v; subFit = null; invalidate() } }
    var showTrack = true
        set(v) { if (v != field) { field = v; invalidate() } }
    /** 1 = title and artist shown; lyrics mode crossfades them to 0 as the lyrics take their place. */
    var titlesAlpha = 1f
        set(v) { if (v != field) { field = v; invalidate() } }
    var night = false
        set(v) { field = v; invalidate() }

    private var icon = Icon(context, R_airplay)
    private var titleFit: String? = null
    private var subFit: String? = null

    private fun col(c: Int) = Night.map(c, night)

    override fun onDraw(canvas: Canvas) {
        val rowH = maxOf(17f, Text.source.normalLineHeight, Text.clock.normalLineHeight)
        val top = 26f
        if (showTrack && sourceText.isNotEmpty()) {
            val c = col(withAlpha(Color.WHITE, 0.78f))
            icon.draw(canvas, 56f, top + (rowH - 17f) / 2f, 17f, c)
            Text.source.draw(canvas, sourceText, 56f + 17f + 8f, Text.source.baselineIn(top, rowH), c)
        }
        Text.clock.draw(canvas, clock, NP.COL_R - Text.clock.width(clock), Text.clock.baselineIn(top, rowH), col(withAlpha(Color.WHITE, 0.9f)))
        val ta = titlesAlpha
        if (!showTrack || ta <= 0.01f) return
        val dy = -8f * (1f - ta) // drift up a little as they fade into the lyrics
        val t = titleFit ?: Text.ellipsize(Text.title, title, NP.COL_W).also { titleFit = it }
        Text.title.draw(canvas, t, NP.COL_L, Text.title.baselineIn(NP.TITLE_T, NP.TITLE_LH) + dy, col(withAlpha(Color.WHITE, ta)))
        val s = subFit ?: Text.ellipsize(Text.artist, subtitle, NP.COL_W).also { subFit = it }
        Text.artist.draw(canvas, s, NP.COL_L, Text.artist.baselineIn(NP.ARTIST_T) + dy, col(withAlpha(Color.WHITE, 0.72f * ta)))
    }

    override fun onTouchEvent(event: MotionEvent?) = false
}

/** Scrubber + elapsed / remaining. Position is extrapolated every frame while playing. */
internal class ProgressView(context: Context) : View(context) {
    /** Bar top inside this view (the view starts 10 px above the seek button for a bigger target). */
    val barTop = 10f + 7f
    var position: () -> Long = { 0L }
    var duration = 0L
    var canSeek = true
    var onSeek: ((Long) -> Unit)? = null
    var night = false
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var scrub: Float? = null
    private var labelsFor = Long.MIN_VALUE
    private var labels = "" to ""
    private var drawnPx = -1
    private var drawnSec = Long.MIN_VALUE
    private var drawnLeft = Long.MIN_VALUE

    /**
     * Called on every ticker frame while playing: redraws only when the bar's pixel width or a
     * label would change (2–3 times a second for a typical track), not 60 times a second.
     */
    fun refreshIfChanged() {
        if (scrub != null) return
        val pos = position()
        if ((width * Format.fraction(pos, duration)).toInt() != drawnPx || pos / 1000 != drawnSec || (duration - pos) / 1000 != drawnLeft) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val pos = scrub?.let { (it * duration).toLong() } ?: position()
        val f = Format.fraction(pos, duration)
        drawnPx = (w * f).toInt(); drawnSec = pos / 1000; drawnLeft = (duration - pos) / 1000
        track.color = if (night) withAlpha(Night.COLOR, 0.3f) else withAlpha(Color.WHITE, 0.24f)
        fill.color = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.95f)
        rect.set(0f, barTop, w, barTop + 8f)
        canvas.drawRoundRect(rect, 4f, 4f, track)
        if (f > 0f) { rect.right = w * f; canvas.drawRoundRect(rect, 4f, 4f, fill) }
        val sec = pos / 1000
        if (sec != labelsFor) { labelsFor = sec; labels = Format.progressLabels(pos, duration) }
        val top = barTop + 8f + 7f + 4f
        val c = if (night) Night.COLOR else withAlpha(Color.WHITE, 0.66f)
        val base = Text.label.baselineIn(top)
        Text.label.draw(canvas, labels.first, 0f, base, c)
        Text.label.draw(canvas, labels.second, w - Text.label.width(labels.second), base, c)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!canSeek || duration <= 0) return false
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); scrub = f; invalidate() }
            MotionEvent.ACTION_MOVE -> { scrub = f; invalidate() }
            MotionEvent.ACTION_UP -> { onSeek?.invoke((f * duration).toLong()); scrub = null; invalidate() }
            MotionEvent.ACTION_CANCEL -> { scrub = null; invalidate() }
        }
        return true
    }
}

/** Glass that is invisible at rest (transport "lift"). Alpha without an offscreen layer. */
internal class LiftGlass(context: Context) : GlassLayout(context) {
    init {
        tint = withAlpha(Color.WHITE, 0.14f)
        alpha = 0f
        scaleX = 0.7f; scaleY = 0.7f
    }
    override fun hasOverlappingRendering() = false
}

/**
 * Transport button: a plain glyph at rest that lifts into Liquid Glass while touched
 * (rule 3: quiet at rest). Opacity 0.18 s ease, scale 0.7 → 1 with the bouncy spring.
 */
@SuppressLint("ViewConstructor")
internal class TransportButton(context: Context, iconRes: Int, private val iconPx: Float) : View(context) {
    val lift = LiftGlass(context)
    var icon = Icon(context, iconRes); private set
    var iconDx = 0f
    var night = false
        set(v) { field = v; lift.night = v; invalidate() }
    var onTap: (() -> Unit)? = null
    /** false: the source can't do this (canSkip / canPause): dimmed, no lift, no tap. */
    var available = true
        set(v) { if (v != field) { field = v; if (!v) press(false); invalidate() } }
    private var pressed = false
    private var anim: AnimatorSet? = null

    fun setIcon(res: Int, dx: Float = 0f) { icon = Icon(context, res); iconDx = dx; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = iconPx
        val c = if (night) Night.COLOR else Color.WHITE
        icon.draw(canvas, (width - s) / 2f + iconDx, (height - s) / 2f, s, if (available) c else withAlpha(c, 0.3f))
    }

    private fun press(on: Boolean) {
        if (on == pressed) return
        pressed = on
        anim?.cancel()
        val target = if (on) 1f else 0.7f
        anim = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(lift, View.ALPHA, if (on) 1f else 0f).setDuration(180).apply { interpolator = Motion.EASE },
                ObjectAnimator.ofFloat(lift, View.SCALE_X, target).setDuration(300).apply { interpolator = Motion.SPRING_BOUNCY },
                ObjectAnimator.ofFloat(lift, View.SCALE_Y, target).setDuration(300).apply { interpolator = Motion.SPRING_BOUNCY },
            )
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inside = e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()
        if (!available) return e.actionMasked == MotionEvent.ACTION_DOWN
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_MOVE -> if (!inside) press(false)
            MotionEvent.ACTION_UP -> { if (pressed && inside) onTap?.invoke(); press(false) }
            MotionEvent.ACTION_CANCEL -> press(false)
        }
        return true
    }
}

/** The glass volume capsule: speaker icons around a white bar; tap or drag to set. */
internal class VolumeCapsule(context: Context) : GlassLayout(context) {
    var value = 0.5f; private set
    var onChange: ((Float) -> Unit)? = null
    private var shown = 0.5f
    private var dragging = false
    private var anim: ObjectAnimator? = null
    private val low = Icon(context, R_vol_low)
    private val high = Icon(context, R_vol_high)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val barL = 18f + 18f + 12f
    private val barR get() = width - 18f - 22f - 12f

    init { cornerRadius = 26f }

    @Suppress("unused") // ObjectAnimator target
    fun setShown(v: Float) { shown = v; invalidate() }

    /** External value (state / hardware keys): 0.25 s ease to it, as in the design. */
    fun setValue(v: Float, animate: Boolean) {
        value = v.coerceIn(0f, 1f)
        if (dragging) return
        anim?.cancel()
        if (!animate) { shown = value; invalidate(); return }
        anim = ObjectAnimator.ofFloat(this, "shown", shown, value).setDuration(250).apply { interpolator = Motion.EASE; start() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = if (night) Night.COLOR else Color.WHITE
        low.draw(canvas, 18f, (height - 18f) / 2f, 18f, c)
        high.draw(canvas, width - 18f - 22f, (height - 22f) / 2f, 22f, c)
        val cy = height / 2f
        track.color = if (night) withAlpha(Night.COLOR, 0.3f) else withAlpha(Color.WHITE, 0.22f)
        fill.color = c
        rect.set(barL, cy - 4f, barR, cy + 4f)
        canvas.drawRoundRect(rect, 4f, 4f, track)
        if (shown > 0f) { rect.right = barL + (barR - barL) * shown; canvas.drawRoundRect(rect, 4f, 4f, fill) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val v = ((e.x - barL) / (barR - barL)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; anim?.cancel(); set(v) }
            MotionEvent.ACTION_MOVE -> set(v)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun set(v: Float) { value = v; shown = v; invalidate(); onChange?.invoke(v) }
}

/** The AirPlay button at the end of the control bar; white with a blue glyph while its popover is open. */
internal class RouteButton(context: Context) : GlassLayout(context) {
    var open = false
        set(v) { field = v; tint = if (v) withAlpha(Color.WHITE, 0.88f) else withAlpha(Color.WHITE, 0.1f); invalidate() }
    /** The route glyph: AirPlay, or Bluetooth for a phone streaming over Bluetooth. */
    var icon = R_airplay
        set(v) { if (v != field) { field = v; glyph = Icon(context, v); invalidate() } }
    private var glyph = Icon(context, R_airplay)

    init { open = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = when { night -> Night.COLOR; open -> 0xFF0A84FF.toInt(); else -> Color.WHITE }
        glyph.draw(canvas, (width - 24f) / 2f, (height - 24f) / 2f, 24f, c)
    }
}

/**
 * The AirPlay popover (pops out of [RouteButton]). One glass surface; everything on it is a
 * plain fill (no glass on glass).
 */
internal class RoutePopover(context: Context) : GlassLayout(context) {
    var header = "AIRPLAY RECEIVER"
    var name = "iPhone"
    var subtitle = "Streaming to Echo Show"
    var chips: List<String> = emptyList()
    var buttonLabel = "Disconnect"
    var deviceIcon = R_phone
        set(v) { if (v != field) { field = v; icon = Icon(context, v) } }
    var onButton: (() -> Unit)? = null
    private var icon = Icon(context, R_phone)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val buttonRect = RectF()
    private var pressedButton = false

    init {
        cornerRadius = 26f
        tint = Color.argb(97, 30, 30, 36) // rgba(30,30,36,.38)
    }

    private val padX = 20f
    private val padY = 18f
    private val gap = 12f
    private val chipH get() = Text.chip.normalLineHeight + 10f

    private fun chipRows(): List<List<Pair<String, Float>>> {
        val rows = ArrayList<MutableList<Pair<String, Float>>>()
        var x = 0f
        val maxW = NP.POPOVER_W - 2 * padX
        for (c in chips) {
            val w = Text.chip.width(c) + 20f
            if (rows.isEmpty() || x + w > maxW) { rows += mutableListOf<Pair<String, Float>>(); x = 0f }
            rows.last() += c to w
            x += w + 8f
        }
        return rows
    }

    /** Height for the current content (the CSS column: header, device row, chips, button). */
    fun contentHeight(): Float {
        val rows = chipRows().size
        var h = padY + Text.popHeader.normalLineHeight + gap + 40f
        if (rows > 0) h += gap + rows * chipH + (rows - 1) * 8f
        return h + gap + 44f + padY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dim = { c: Int -> Night.map(c, night) }
        var y = padY
        Text.popHeader.draw(canvas, header, padX, Text.popHeader.baselineIn(y), dim(withAlpha(Color.WHITE, 0.6f)))
        y += Text.popHeader.normalLineHeight + gap
        // Device row.
        fillPaint.color = dim(withAlpha(Color.WHITE, 0.14f))
        rect.set(padX, y, padX + 40f, y + 40f)
        canvas.drawRoundRect(rect, 12f, 12f, fillPaint)
        icon.draw(canvas, padX + 10f, y + 10f, 20f, dim(Color.WHITE))
        val textH = Text.popName.normalLineHeight + Text.popSub.normalLineHeight
        val ty = y + (40f - textH) / 2f
        val tx = padX + 40f + 12f
        val maxW = NP.POPOVER_W - padX - tx
        Text.popName.draw(canvas, Text.ellipsize(Text.popName, name, maxW), tx, Text.popName.baselineIn(ty), dim(Color.WHITE))
        Text.popSub.draw(canvas, Text.ellipsize(Text.popSub, subtitle, maxW), tx, Text.popSub.baselineIn(ty + Text.popName.normalLineHeight), dim(withAlpha(Color.WHITE, 0.62f)))
        y += 40f
        // Chips.
        val rows = chipRows()
        if (rows.isNotEmpty()) {
            y += gap
            for (row in rows) {
                var x = padX
                for ((label, w) in row) {
                    fillPaint.color = dim(withAlpha(Color.WHITE, 0.12f))
                    rect.set(x, y, x + w, y + chipH)
                    canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
                    Text.chip.draw(canvas, label, x + 10f, Text.chip.baselineIn(y + 5f), dim(Color.WHITE))
                    x += w + 8f
                }
                y += chipH + 8f
            }
            y -= 8f
        }
        // Button.
        y += gap
        buttonRect.set(padX, y, width - padX, y + 44f)
        fillPaint.color = dim(withAlpha(Color.WHITE, if (pressedButton) 0.2f else 0.1f))
        canvas.drawRoundRect(buttonRect, 22f, 22f, fillPaint)
        val bw = Text.button.width(buttonLabel)
        Text.button.draw(canvas, buttonLabel, buttonRect.centerX() - bw / 2f, Text.button.baselineIn(y, 44f), if (night) Night.COLOR else 0xFFFF6961.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val inButton = buttonRect.contains(e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedButton = inButton; invalidate() }
            MotionEvent.ACTION_MOVE -> if (pressedButton && !inButton) { pressedButton = false; invalidate() }
            MotionEvent.ACTION_UP -> { if (pressedButton && inButton) onButton?.invoke(); pressedButton = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { pressedButton = false; invalidate() }
        }
        return true
    }
}

/** Nothing playing: quiet, static, on black (no motion, no frames). */
internal class EmptyView(context: Context) : View(context) {
    var night = false
        set(v) { field = v; invalidate() }
    private val icon = Icon(context, R_airplay)
    private val title = "Nothing playing"
    /** [app.vesper.core.model.DeviceName], set live by the screen. */
    var deviceName = "Echo Show"
        set(v) { if (v != field) { field = v; invalidate() } }
    private val sub get() = "AirPlay to $deviceName from your iPhone, iPad or Mac"

    override fun onDraw(canvas: Canvas) {
        val col = { c: Int -> Night.map(c, night) }
        val total = 44f + 20f + Text.emptyTitle.normalLineHeight + 6f + Text.emptySub.normalLineHeight
        var y = (NP.H - total) / 2f
        icon.draw(canvas, (NP.W - 44f) / 2f, y, 44f, col(withAlpha(Color.WHITE, 0.4f)))
        y += 44f + 20f
        Text.emptyTitle.draw(canvas, title, (NP.W - Text.emptyTitle.width(title)) / 2f, Text.emptyTitle.baselineIn(y), col(Color.WHITE))
        y += Text.emptyTitle.normalLineHeight + 6f
        Text.emptySub.draw(canvas, sub, (NP.W - Text.emptySub.width(sub)) / 2f, Text.emptySub.baselineIn(y), col(withAlpha(Color.WHITE, 0.6f)))
    }

    override fun onTouchEvent(event: MotionEvent?) = false
}

internal val R_airplay = R.drawable.media_ic_airplay
internal val R_note = R.drawable.media_ic_note
internal val R_phone = R.drawable.media_ic_phone
internal val R_bluetooth = R.drawable.media_ic_bluetooth
internal val R_vol_low = R.drawable.media_ic_vol_low
internal val R_vol_high = R.drawable.media_ic_vol_high

internal val R_lyrics = R.drawable.media_ic_lyrics
