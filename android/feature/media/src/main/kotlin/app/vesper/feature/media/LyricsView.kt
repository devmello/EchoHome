package app.vesper.feature.media

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Lyrics column (Apple Music style). Synced: the current line large and bright, upcoming lines
 * dimmer, past lines faded; moving to the next line is one eased scroll ([SCROLL_MS]) driven by a
 * fractional "current index", so line position and brightness animate together. Between lines
 * nothing redraws. Plain lyrics are static and drag-scrollable. Alpha is per line (no offscreen
 * layer for an edge mask).
 */
internal class LyricsView(context: Context) : View(context) {
    companion object {
        const val W = NP.COL_W
        const val H = 156f
        const val LINE_H = 36f
        const val GAP = 14f
        const val BLANK_H = 22f
        const val ANCHOR = 38f
        const val SCROLL_MS = 360L
        const val MASK_LIMIT = 16

        /** Brightness of a line `d` lines after the current one (d < 0: already sung). */
        fun alphaAt(d: Float): Float = when {
            d <= -1f -> 0.26f
            d < 0f -> 0.26f + 0.74f * (d + 1f)
            d < 1f -> 1f - 0.5f * d
            else -> maxOf(0.5f - 0.1f * (d - 1f), 0.3f)
        }
    }

    private val big by lazy { SpecText(28f, 700, -0.02f) }
    private val small by lazy { SpecText(20f, 500, -0.01f) }

    var night = false
        set(v) { if (v != field) { field = v; invalidate() } }

    /** Playback position now (ms); read once per frame while a line is being sung word by word. */
    var positionMs: () -> Long = { 0L }
    /** Word-by-word fill of the active line; off = the whole line is simply bright. */
    var wordMode = true
        set(v) { if (v != field) { field = v; actIdx = -2; invalidate() } }
    private var playing = false
    private var syncedLines: List<LyricLine>? = null

    // The active line's word layout, computed once when it becomes active (no allocation per frame).
    private var actIdx = -2
    private var actN = 0
    private var actStart = LongArray(0)
    private var actEnd = LongArray(0)
    private var actRow = IntArray(0)
    private var actX0 = FloatArray(0)
    private var actX1 = FloatArray(0)
    private var rowPx = FloatArray(0)
    private var actLineStart = 0L
    private var actLineEnd = 0L

    private var lines: List<Array<String>> = emptyList()
    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var synced = false
    private var message: String? = null

    private var cur = -1f
    private var target = -1
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SCROLL_MS
        interpolator = Motion.EASE
    }
    private var animFrom = -1f
    private var plainScroll = 0f
    private var plainMax = 0f

    init {
        anim.addUpdateListener { cur = animFrom + (target - animFrom) * it.animatedFraction; invalidate() }
    }

    fun showMessage(m: String?) {
        stop(); clearMasks()
        message = m; lines = emptyList(); synced = false; syncedLines = null; actIdx = -2; invalidate()
    }

    fun setLyrics(l: Lyrics) {
        stop(); clearMasks()
        message = null
        when (l) {
            is Lyrics.Synced -> {
                synced = true
                syncedLines = l.lines
                layoutLines(l.lines.map { it.text }, big, 0f)
            }
            is Lyrics.Plain -> {
                synced = false
                layoutLines(l.lines, small, 4f)
                plainScroll = 0f
                plainMax = maxOf(0f, (tops.lastOrNull() ?: 0f) + (heights.lastOrNull() ?: 0f) - H + 20f)
            }
            Lyrics.None -> { synced = false; lines = emptyList(); message = "No lyrics" }
        }
        cur = -1f; target = -1; actIdx = -2
        invalidate()
    }

    private fun layoutLines(src: List<String>, style: SpecText, gap: Float) {
        val lh = if (style === big) LINE_H else 28f
        val g = if (style === big) GAP else gap
        lines = src.map { t -> Lrc.wrap(t, W - 8f) { style.width(it) }.toTypedArray() }
        tops = FloatArray(lines.size); heights = FloatArray(lines.size)
        var y = 0f
        for (i in lines.indices) {
            val blank = src[i].isEmpty()
            val h = if (blank) (if (style === big) BLANK_H else 14f) else lines[i].size * lh + g
            tops[i] = y; heights[i] = h; y += h
        }
    }

    /** Moves the highlight to line [index] (-1 = before the first line); eased unless [animate] is false. */
    fun setIndex(index: Int, animate: Boolean) {
        if (!synced) return
        if (index == target) { invalidate(); return }
        anim.cancel()
        target = index
        actIdx = -2
        if (!animate || !isShown || kotlin.math.abs(index - cur) > 12) { cur = index.toFloat(); invalidate(); return }
        animFrom = cur
        anim.start()
    }

    /** Stops the scroll animation and the word sweep (screen hidden). */
    fun stop() { anim.cancel(); playing = false; if (synced) cur = target.toFloat() }

    /** The sweep runs only while playing; paused shows the line as it is. Call again after a seek. */
    fun setPlaying(p: Boolean) { playing = p; invalidate() }

    private fun activate(i: Int) {
        actIdx = i
        actN = 0
        val ls = syncedLines ?: return
        if (i < 0 || i >= ls.size || i >= lines.size) return
        val line = ls[i]
        if (line.text.isEmpty()) return
        val next = if (i + 1 < ls.size) ls[i + 1].timeMs else line.timeMs + 6000L
        val words = line.words ?: WordTiming.estimate(line.text, line.timeMs, next)
        if (words.isEmpty()) return
        val sungEnd = if (line.words != null) minOf(maxOf(next, words.last().startMs + 400L), words.last().startMs + 1500L)
        else WordTiming.sungEnd(line.timeMs, next, line.text)
        val n = words.size
        actStart = LongArray(n); actEnd = LongArray(n); actRow = IntArray(n); actX0 = FloatArray(n); actX1 = FloatArray(n)
        val rows = lines[i]
        rowPx = FloatArray(rows.size)
        var rs = 0
        val rowStarts = IntArray(rows.size)
        for (r in rows.indices) { rowStarts[r] = rs; rs += rows[r].length + 1 }
        for (k in 0 until n) {
            val w = words[k]
            var r = rows.size - 1
            while (r > 0 && w.from < rowStarts[r]) r--
            val row = rows[r]
            val a = (w.from - rowStarts[r]).coerceIn(0, row.length)
            val b = (w.to - rowStarts[r]).coerceIn(a, row.length)
            actStart[k] = w.startMs
            actEnd[k] = if (k + 1 < n) words[k + 1].startMs else sungEnd
            actRow[k] = r
            actX0[k] = big.width(row.substring(0, a)); actX1[k] = big.width(row.substring(0, b))
        }
        actN = n
        actLineStart = words[0].startMs
        actLineEnd = sungEnd
    }

    private fun scrollFor(f: Float): Float {
        if (tops.isEmpty()) return 0f
        val ff = f.coerceIn(0f, (tops.size - 1).toFloat())
        val i = floor(ff).toInt()
        val frac = ff - i
        val next = if (i + 1 < tops.size) tops[i + 1] else tops[i] + heights[i]
        return tops[i] + (next - tops[i]) * frac
    }

    private fun edge(yTop: Float, h: Float): Float {
        val c = yTop + minOf(h, LINE_H) / 2f
        val top = (c / 30f).coerceIn(0f, 1f)
        val bottom = ((H - c) / 26f).coerceIn(0f, 1f)
        return minOf(top, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        val white = Night.map(Color.WHITE, night)
        message?.let {
            small.draw(canvas, it, 0f, small.baselineIn(ANCHOR, LINE_H), withAlpha(white, 0.6f)); return
        }
        if (lines.isEmpty()) return
        if (synced) {
            val scroll = scrollFor(cur)
            for (i in lines.indices) {
                val y = ANCHOR + tops[i] - scroll
                if (y + heights[i] < 0f || y > H) continue
                val a = alphaAt(i - cur) * edge(y, heights[i])
                if (a <= 0.01f) continue
                if (wordMode && i == target) {
                    if (actIdx != i) activate(i)
                    if (actN > 0) {
                        val t = positionMs()
                        if (t < actLineEnd) {
                            // Still to be sung (or being sung): redraw just this line each frame while playing.
                            if (playing) postInvalidateOnAnimation(0, y.toInt() - 2, W.toInt(), (y + heights[i]).toInt() + 2)
                            if (t >= actLineStart) { sweep(canvas, i, y, t, a, white); continue }
                        }
                    }
                }
                blit(canvas, i, y, big, LINE_H, withAlpha(white, a))
            }
        } else {
            for (i in lines.indices) {
                val y = tops[i] - plainScroll
                if (y + heights[i] < 0f || y > H) continue
                val a = 0.82f * edge(y, 28f)
                if (a <= 0.01f) continue
                blit(canvas, i, y, small, 28f, withAlpha(white, a))
            }
        }
    }

    // A line's text is rasterised once into a small ALPHA_8 mask and tinted per frame, so a scroll
    // is a few bitmap quads instead of per-glyph text drawing every frame.
    private val masks = HashMap<Int, Bitmap>()
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun blit(canvas: Canvas, i: Int, y: Float, style: SpecText, lh: Float, color: Int) {
        if (lines[i].all { it.isEmpty() }) return
        maskPaint.color = color
        canvas.drawBitmap(maskFor(i, style, lh), 0f, y, maskPaint)
    }

    private fun maskFor(i: Int, style: SpecText, lh: Float): Bitmap {
        val rows = lines[i]
        return masks[i] ?: run {
            if (masks.size >= MASK_LIMIT) {
                val keep = cur.toInt()
                masks.keys.filter { abs(it - keep) > 4 && it != i }.forEach { masks.remove(it)?.recycle() }
            }
            val bmp = Bitmap.createBitmap(W.toInt(), ceil(rows.size * lh).toInt(), Bitmap.Config.ALPHA_8)
            val c = Canvas(bmp)
            for ((k, t) in rows.withIndex()) if (t.isNotEmpty()) style.draw(c, t, 0f, style.baselineIn(k * lh, lh), Color.WHITE)
            masks[i] = bmp
            bmp
        }
    }

    /**
     * The active line twice: dim, then bright clipped to the sweep. Each word fills left to right
     * between its start and the next word's start; words already sung are fully lit.
     */
    private fun sweep(canvas: Canvas, i: Int, y: Float, t: Long, a: Float, white: Int) {
        val rows = lines[i]
        java.util.Arrays.fill(rowPx, 0f)
        for (k in 0 until actN) {
            if (t < actStart[k]) break
            rowPx[actRow[k]] = if (t >= actEnd[k]) actX1[k]
            else actX0[k] + (actX1[k] - actX0[k]) * (t - actStart[k]).toFloat() / maxOf(1L, actEnd[k] - actStart[k])
        }
        val m = maskFor(i, big, LINE_H)
        maskPaint.color = withAlpha(white, minOf(a, 0.5f * edge(y, heights[i])))
        canvas.drawBitmap(m, 0f, y, maskPaint)
        maskPaint.color = withAlpha(white, a)
        for (r in rows.indices) {
            val px = rowPx[r]
            if (px <= 0f) continue
            canvas.save()
            canvas.clipRect(0f, y + r * LINE_H, px, y + (r + 1) * LINE_H)
            canvas.drawBitmap(m, 0f, y, maskPaint)
            canvas.restore()
        }
    }

    private fun clearMasks() { masks.values.forEach { it.recycle() }; masks.clear() }

    private var lastY = 0f
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (synced || plainMax <= 0f || lines.isEmpty()) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                plainScroll = (plainScroll - (e.y - lastY)).coerceIn(0f, plainMax)
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun onDetachedFromWindow() { anim.cancel(); clearMasks(); super.onDetachedFromWindow() }
}
