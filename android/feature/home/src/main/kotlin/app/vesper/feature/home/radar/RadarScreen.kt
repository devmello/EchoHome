package app.vesper.feature.home.radar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.vesper.core.design.Backdrop
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.SpecText
import app.vesper.core.design.VesperColors
import app.vesper.core.design.withAlpha
import app.vesper.core.model.HomeLocation
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import java.io.IOException
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Weather radar (`--es screen radar`; opened from the Weather face and the Weather widget):
 * RainViewer's last hour of radar looping over a drawn map (Natural Earth land, lakes, borders,
 * states; no map tiles fetched), centred on services.location with a blue dot.
 *  - Top: back + the place; the frame's time ("9:40 PM · 20 min ago" / "Now").
 *  - Right: zoom − / + (3…7; RainViewer's free tier stops at 7).
 *  - Bottom: play / pause and the timeline (tap a step to hold it); the attribution.
 * The loop steps every [STEP_MS] (holds the newest frame [HOLD_MS]); paused, it draws nothing.
 * Night: black map with red coastlines, red radar, red outlines, no animation (the newest frame).
 * Fetches only while shown ([RadarFrames]); memory dropped [TRIM_MS] after it's hidden.
 *
 * Args: `zoom 3..7`, `at "lat,lon"` (look somewhere else), `frame -1|0..6` (hold a frame).
 */
@SuppressLint("ViewConstructor")
class RadarScreen(context: Context, private val services: VesperServices) : View(context), VesperScreen {
    private val frames = RadarFrames(RainViewerClient(), services.scope, sampleWhenOffline = services.config.demoData)

    private val tPlace = SpecText(20f, 700, -0.01f)
    private val tSub = SpecText(13f, 700, 0.04f)
    private val tTime = SpecText(20f, 700, -0.01f)
    private val tRel = SpecText(14f, 600)
    private val tSmall = SpecText(12f, 600)
    private val tStatus = SpecText(16f, 700)
    private val tZoom = SpecText(26f, 500)

    private val glass = GlassPainter()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val radarPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val mapPaint = Paint()
    private val frameMatrix = Matrix().apply { setScale(RadarFrames.SCALE.toFloat(), RadarFrames.SCALE.toFloat()) }
    private val rect = RectF()
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val handler = Handler(Looper.getMainLooper())

    private var map: RadarMap? = null
    private var base: Bitmap? = null
    private var baseKey: String? = null
    private var baseJob: Job? = null
    private var list: List<RadarFrames.Frame> = emptyList()
    private var status = RadarFrames.Status.LOADING
    private var index = -1
    private var playing = true
    private var shown = false
    private var night = false
    private var zoom = DEFAULT_ZOOM
    private var override: HomeLocation? = null
    private var location: HomeLocation = services.location.value

    private val icBack = path("m14.5 6-6 6 6 6")
    private val icPlay = path("M8 5.5v13l10.5-6.5z")
    private val icPause = path("M8 5.5v13M16 5.5v13")

    private val step = object : Runnable {
        override fun run() {
            if (!animating()) return
            index = if (index < 0 || index >= list.size - 1) 0 else index + 1
            invalidate()
            handler.postDelayed(this, if (index == list.size - 1) HOLD_MS else STEP_MS)
        }
    }
    private val trim = Runnable { frames.trim(); map = null; base = null; baseKey = null; list = emptyList() }

    init {
        setBackgroundColor(Color.BLACK)
        observeWhileAttached(frames.frames) { f ->
            val wasNewest = index < 0 || index >= list.size - 1
            list = f
            if (index >= list.size || wasNewest) index = list.size - 1
            invalidate()
            restartLoop()
        }
        observeWhileAttached(frames.status) { status = it; invalidate() }
        observeWhileAttached(services.location) { l -> if (l != location) { location = l; if (shown) reload() } }
        observeWhileAttached(services.system.state) { s ->
            if (s.nightActive != night) { night = s.nightActive; glass.night = night; baseKey = null; if (shown) { renderBase(); restartLoop() }; invalidate() }
        }
    }

    private fun place() = override ?: location

    // ------------------------------------------------------------------ lifecycle

    override fun onScreenShown(args: Bundle?) {
        shown = true
        handler.removeCallbacks(trim)
        args?.let(::handleArgs)
        reload()
    }

    override fun onScreenHidden() {
        shown = false
        handler.removeCallbacks(step)
        frames.hide()
        baseJob?.cancel()
        handler.postDelayed(trim, TRIM_MS)
    }

    private fun handleArgs(a: Bundle) {
        a.getString("zoom")?.toIntOrNull()?.let { zoom = it.coerceIn(MIN_ZOOM, RainViewer.MAX_ZOOM) }
        a.getString("at")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let {
            override = HomeLocation(a.getString("place") ?: "%.2f, %.2f".format(it[0], it[1]), it[0], it[1], HomeLocation.Source.MANUAL)
        }
        a.getString("frame")?.toIntOrNull()?.let { f -> playing = false; index = if (f < 0) Int.MAX_VALUE else f }
    }

    private fun reload() {
        val p = place()
        frames.show(RadarFrames.Target(p.lat, p.lon, zoom))
        renderBase()
        restartLoop()
        invalidate()
    }

    private fun animating() = shown && playing && !night && list.size > 1

    private fun restartLoop() {
        handler.removeCallbacks(step)
        if (index >= list.size) index = list.size - 1
        if (animating()) handler.postDelayed(step, if (index == list.size - 1) HOLD_MS else STEP_MS)
    }

    /** The base map for the current view, drawn once off the main thread (and its glass backdrop). */
    private fun renderBase() {
        val p = place()
        val key = "${p.lat},${p.lon},$zoom,$night"
        if (key == baseKey && base != null) return
        baseKey = key
        baseJob?.cancel()
        baseJob = services.scope.launch {
            try {
                val (bmp, bd) = withContext(Dispatchers.Default) {
                    val m = map ?: RadarMap.parse(context.assets.open(RadarMap.ASSET).use { it.readBytes() })
                    map = m
                    val (ox, oy) = TileMath.viewOrigin(p.lat, p.lon, zoom, W, H)
                    val b = m.render(ox, oy, zoom, W, H, if (night) RadarMap.NIGHT else RadarMap.DAY)
                    b to (if (night) null else Backdrop(PreBlur.blur(b, 8f, 4), 0f, 0f, W.toFloat(), H.toFloat()))
                }
                if (baseKey == key) { base = bmp; glass.backdrop = bd; invalidate() }
            } catch (e: IOException) {
                Log.w(RadarFrames.TAG, "base map: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        hits.clear()
        val b = base
        if (b != null) c.drawBitmap(b, 0f, 0f, mapPaint) else c.drawColor(if (night) Color.BLACK else RadarMap.DAY.ocean)
        val f = list.getOrNull(index.coerceAtMost(list.size - 1))
        if (f != null) {
            radarPaint.alpha = if (night) 200 else 225
            radarPaint.colorFilter = if (night) PorterDuffColorFilter(Night.COLOR, PorterDuff.Mode.SRC_IN) else null
            c.drawBitmap(f.bitmap, frameMatrix, radarPaint)
        }
        drawMarker(c)
        drawTop(c, f)
        drawZoom(c)
        drawBottom(c)
        drawStatus(c)
    }

    private fun drawMarker(c: Canvas) {
        val cx = W / 2f; val cy = H / 2f
        paint.color = ink(withAlpha(VesperColors.BLUE, 0.22f))
        c.drawCircle(cx, cy, 20f, paint)
        paint.color = ink(Color.WHITE)
        c.drawCircle(cx, cy, 9f, paint)
        paint.color = ink(VesperColors.BLUE)
        c.drawCircle(cx, cy, 6.5f, paint)
    }

    private fun drawTop(c: Canvas, f: RadarFrames.Frame?) {
        // back + place
        val name = place().name.ifBlank { "Here" }
        val w = 64f + maxOf(tPlace.width(name), tSub.width("RADAR")) + 22f
        glass.draw(c, 20f, 20f, 20f + w, 76f, -1f)
        drawIcon(c, icBack, 46f, 48f, 22f, ink(Color.WHITE), 2.6f)
        tSub.draw(c, "RADAR", 72f, 41f, ink(withAlpha(Color.WHITE, 0.62f)))
        tPlace.draw(c, name, 72f, 65f, ink(Color.WHITE))
        hit(20f, 14f, 20f + w, 82f) { services.navigator.home() }

        // time of the frame
        if (f != null) {
            val time = DateFormat.getTimeFormat(context).format(Date(f.time * 1000))
            val ago = ((System.currentTimeMillis() / 1000 - f.time) / 60).toInt()
            val rel = if (index == list.size - 1 && ago < 15) "Latest" else if (ago < 60) "$ago min ago" else "${ago / 60} h ${ago % 60} min ago"
            val tw = maxOf(tTime.width(time), tRel.width(rel)) + 40f
            glass.draw(c, W - 20f - tw, 20f, W - 20f, 76f, 28f)
            tTime.draw(c, time, W - 40f - tTime.width(time), 47f, ink(Color.WHITE))
            tRel.draw(c, rel, W - 40f - tRel.width(rel), 66f, ink(withAlpha(Color.WHITE, 0.7f)))
        }
    }

    private fun drawZoom(c: Canvas) {
        val l = W - 20f - 52f; val t = 168f; val r = W - 20f; val b = t + 112f
        glass.draw(c, l, t, r, b, 26f)
        val cx = (l + r) / 2
        val canIn = zoom < RainViewer.MAX_ZOOM; val canOut = zoom > MIN_ZOOM
        stroke.strokeWidth = 2.6f
        stroke.color = ink(withAlpha(Color.WHITE, if (canIn) 1f else 0.3f))
        c.drawLine(cx - 9f, t + 28f, cx + 9f, t + 28f, stroke); c.drawLine(cx, t + 19f, cx, t + 37f, stroke)
        stroke.color = ink(withAlpha(Color.WHITE, if (canOut) 1f else 0.3f))
        c.drawLine(cx - 9f, b - 28f, cx + 9f, b - 28f, stroke)
        paint.color = ink(withAlpha(Color.WHITE, 0.14f))
        c.drawRect(l + 12f, t + 55.5f, r - 12f, t + 56.5f, paint)
        if (canIn) hit(l - 8f, t - 6f, r + 8f, t + 56f) { setZoom(zoom + 1) }
        if (canOut) hit(l - 8f, t + 56f, r + 8f, b + 6f) { setZoom(zoom - 1) }
    }

    private fun drawBottom(c: Canvas) {
        val l = 20f; val t = H - 76f; val r = W - 20f; val b = H - 20f
        glass.draw(c, l, t, r, b, -1f)
        val cy = (t + b) / 2
        // play / pause
        val px = l + 30f
        paint.color = ink(Color.WHITE)
        c.drawCircle(px, cy, 20f, paint)
        val glyphColor = if (night) Color.BLACK else 0xFF0B1622.toInt()
        if (playing && !night) drawIcon(c, icPause, px, cy, 18f, glyphColor, 3f) else fillIcon(c, icPlay, px + 1.5f, cy, 18f, glyphColor)
        hit(l, t - 6f, px + 30f, b + 6f) { togglePlay() }
        // timeline
        val tl = px + 44f
        val attribution = RainViewer.ATTRIBUTION + " · Map: Natural Earth"
        val aw = tSmall.width(attribution)
        val tr = r - 28f - aw - 24f
        val n = list.size.coerceAtLeast(RadarFrames.MAX_FRAMES)
        val gap = 6f
        val segW = (tr - tl - gap * (n - 1)) / n
        tSmall.draw(c, "−1 h", tl, cy - 10f, ink(withAlpha(Color.WHITE, 0.6f)))
        val now = "Now"
        tSmall.draw(c, now, tr - tSmall.width(now), cy - 10f, ink(withAlpha(Color.WHITE, 0.6f)))
        for (i in 0 until n) {
            val sl = tl + i * (segW + gap)
            rect.set(sl, cy + 2f, sl + segW, cy + 10f)
            val available = i < list.size
            paint.color = ink(when {
                available && i == index.coerceAtMost(list.size - 1) -> Color.WHITE
                available -> withAlpha(Color.WHITE, 0.32f)
                else -> withAlpha(Color.WHITE, 0.1f)
            })
            c.drawRoundRect(rect, 4f, 4f, paint)
            if (available) { val k = i; hit(sl - gap / 2, t, sl + segW + gap / 2, b) { hold(k) } }
        }
        tSmall.draw(c, attribution, r - 28f - aw, cy + tSmall.ascent / 2 - 1f, ink(withAlpha(Color.WHITE, 0.72f)))
    }

    private fun drawStatus(c: Canvas) {
        val s = when {
            list.isEmpty() && status == RadarFrames.Status.LOADING -> "Loading radar…"
            list.isEmpty() && status == RadarFrames.Status.OFFLINE -> "Radar unavailable · check the Wi-Fi"
            status == RadarFrames.Status.SAMPLE -> "Sample radar (offline)"
            else -> return
        }
        val w = tStatus.width(s) + 40f
        glass.draw(c, W / 2f - w / 2, 96f, W / 2f + w / 2, 136f, -1f)
        tStatus.draw(c, s, W / 2f - tStatus.width(s) / 2, 122f, ink(Color.WHITE))
    }

    private fun ink(color: Int) = Night.map(color, night)

    private fun hit(l: Float, t: Float, r: Float, b: Float, action: () -> Unit) { hits += RectF(l, t, r, b) to action }

    private fun path(d: String): Path = app.vesper.feature.home.ui.SvgPath.path(d)

    private fun drawIcon(c: Canvas, p: Path, cx: Float, cy: Float, size: Float, color: Int, strokeW: Float) {
        val s = size / 24f
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(s, s)
        stroke.color = color; stroke.strokeWidth = strokeW / s
        c.drawPath(p, stroke)
        c.restore()
    }

    private fun fillIcon(c: Canvas, p: Path, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        c.save(); c.translate(cx - size / 2f, cy - size / 2f); c.scale(s, s)
        paint.color = color
        c.drawPath(p, paint)
        c.restore()
    }

    // ------------------------------------------------------------------ actions

    private fun setZoom(z: Int) {
        val nz = z.coerceIn(MIN_ZOOM, RainViewer.MAX_ZOOM)
        if (nz == zoom) return
        zoom = nz
        reload()
    }

    private fun togglePlay() {
        playing = !playing
        if (playing && index >= list.size - 1) index = -1
        restartLoop()
        invalidate()
    }

    private fun hold(i: Int) {
        playing = false
        index = i
        restartLoop()
        invalidate()
    }

    // ------------------------------------------------------------------ touch

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) hits.firstOrNull { it.first.contains(e.x, e.y) }?.second?.invoke()
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(e)
    }

    companion object {
        const val SCREEN_ID = "radar"
        const val W = RadarFrames.VIEW_W
        const val H = RadarFrames.VIEW_H
        const val DEFAULT_ZOOM = 6
        const val MIN_ZOOM = 3
        const val STEP_MS = 500L
        const val HOLD_MS = 1_800L
        const val TRIM_MS = 3 * 60_000L

        /** km across the screen at this zoom, for docs / tests. */
        fun kmAcross(lat: Double, zoom: Int) = TileMath.kmAcross(lat, zoom, W).roundToInt()
    }
}
