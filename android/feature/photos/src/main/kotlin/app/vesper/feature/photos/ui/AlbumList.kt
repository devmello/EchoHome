package app.vesper.feature.photos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.feature.photos.data.Album
import kotlin.math.abs
import kotlin.math.min

/**
 * The album picker's contents, drawn on the glass sheet with plain fills (no glass on glass):
 * an "Albums" heading, one 48 px row per album (check, name, photo count; the current album on a
 * rgba(255,255,255,.14) fill like the album button) and a quiet status line. Scrolls past 6 rows.
 *
 * There is no artboard for this sheet; it follows the system: 26 px outer radius with 8 px
 * padding, so the 18 px row radius is concentric (NOTES §3.2).
 */
@SuppressLint("ViewConstructor")
internal class AlbumList(context: Context) : View(context) {
    var albums: List<Album> = emptyList()
        set(v) { field = v; scroll = 0f; requestLayout(); invalidate() }
    var selectedId: String? = null
        set(v) { field = v; invalidate() }
    /** "Loading albums…", "Offline · cached albums", "Can't reach Immich". */
    var status: String? = null
        set(v) { if (v != field) { field = v; requestLayout(); invalidate() } }
    /** Glass over a bright part of the photo: dark glyphs, as Liquid Glass does (white would vanish). */
    var onLight = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var night = false
        set(v) { field = v; invalidate() }
    var onPick: ((Album) -> Unit)? = null

    private val heading = SpecText(15f, 700)
    private val name = SpecText(17f, 600)
    private val count = SpecText(15f, 500)
    private val note = SpecText(13f, 500)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var scroll = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var pressed = -1
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val visibleRows get() = min(albums.size, MAX_ROWS)
    private val listTop get() = PAD + HEADER
    private val listHeight get() = visibleRows * ROW
    private val maxScroll get() = (albums.size * ROW - listHeight).coerceAtLeast(0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = PAD + HEADER + listHeight + (if (status != null) STATUS else 0f) + PAD
        setMeasuredDimension(WIDTH, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val white = if (night) Night.COLOR else if (onLight) ON_LIGHT else Color.WHITE
        val w = width.toFloat()
        heading.draw(canvas, "Albums", PAD + 12f, heading.baselineIn(PAD + (HEADER - heading.normalLineHeight) / 2f), withAlpha(white, 0.6f))

        canvas.save()
        canvas.clipRect(0f, listTop, w, listTop + listHeight)
        albums.forEachIndexed { i, a ->
            val top = listTop + i * ROW - scroll
            if (top + ROW < listTop || top > listTop + listHeight) return@forEachIndexed
            val sel = a.id == selectedId
            if (sel || i == pressed) {
                rect.set(PAD, top, w - PAD, top + ROW)
                paint.color = withAlpha(white, if (i == pressed) 0.22f else 0.14f)
                canvas.drawRoundRect(rect, ROW_RADIUS, ROW_RADIUS, paint)
            }
            val cy = top + ROW / 2f
            if (sel) Icons.draw(canvas, Icons.check, 24f, 18f, PAD + 12f + 9f, cy, white, paint, strokeWidth = 2.8f)
            val countText = a.assetCount.toString()
            val countW = count.width(countText)
            count.draw(canvas, countText, w - PAD - 14f - countW, count.baselineIn(cy - count.normalLineHeight / 2f), withAlpha(white, 0.6f))
            val nameX = PAD + 40f
            val nameText = ellipsize(a.name, w - PAD - 14f - countW - 12f - nameX)
            name.draw(canvas, nameText, nameX, name.baselineIn(cy - name.normalLineHeight / 2f), white)
        }
        canvas.restore()

        status?.let {
            val top = listTop + listHeight
            note.draw(canvas, ellipsize(it, w - 2 * (PAD + 12f), note), PAD + 12f, note.baselineIn(top + (STATUS - note.normalLineHeight) / 2f), withAlpha(white, 0.6f))
        }
    }

    private fun ellipsize(s: String, maxW: Float, t: SpecText = name): String {
        if (t.width(s) <= maxW) return s
        var n = s.length
        while (n > 1 && t.width(s.substring(0, n) + "…") > maxW) n--
        return s.substring(0, n).trimEnd() + "…"
    }

    private fun rowAt(y: Float): Int {
        if (y < listTop || y > listTop + listHeight) return -1
        val i = ((y - listTop + scroll) / ROW).toInt()
        return if (i in albums.indices) i else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downY = e.y; downScroll = scroll; dragging = false
                pressed = rowAt(e.y); invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop && maxScroll > 0) { dragging = true; pressed = -1 }
                if (dragging) { scroll = (downScroll - (e.y - downY)).coerceIn(0f, maxScroll); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = -1; invalidate()
                if (!dragging && p >= 0 && p == rowAt(e.y)) onPick?.invoke(albums[p])
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }

    companion object {
        const val WIDTH = 300
        const val RADIUS = 26f
        private const val PAD = 8f
        private const val HEADER = 40f
        private const val ROW = 48f
        private const val ROW_RADIUS = 18f
        private const val STATUS = 34f
        private const val MAX_ROWS = 6
    }
}
