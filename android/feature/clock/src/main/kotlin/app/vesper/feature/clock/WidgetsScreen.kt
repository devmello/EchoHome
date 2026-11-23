package app.vesper.feature.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import app.vesper.core.design.GlassPainter
import app.vesper.core.design.GlassTokens
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.design.PreBlur
import app.vesper.core.design.QuietWork
import app.vesper.core.design.withAlpha
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.observeWhileAttached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One Smart Stack: a rounded tile showing one widget at a time. A vertical swipe or flick moves
 * to the next (up) or previous (down) widget with a short spring slide, wrapping around; the
 * choice persists under [WidgetStackState.key].
 *
 * The glass itself is baked into the screen's wallpaper ([WidgetsScreen]); the stack only
 * clips its widgets to the tile's rounded outline. Every widget stays attached once built
 * (on idle moments), parked far outside the clip with its display list recorded, so a swipe
 * and its commit only change translationY: nothing is built, laid out or re-recorded
 * mid-gesture. Parked widgets don't redraw on data changes; they catch up when they slide in.
 */
@SuppressLint("ViewConstructor")
class WidgetStack(context: Context, private val services: VesperServices, val state: WidgetStackState) : FrameLayout(context) {
    /** The widget changed (a swipe or a setting): the screen redraws the page dots. */
    var onChanged: (() -> Unit)? = null

    private val built = HashMap<WidgetKind, StackWidget>()
    private var current: StackWidget
    private var incoming: StackWidget? = null
    private var incomingDir = 0
    private var shown = false
    private var night = false
    private var person: Profile? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var downX = 0f
    private var downY = 0f
    private var axis = StackGesture.Axis.UNDECIDED
    private var tracking = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var anim: ValueAnimator? = null
    private val nightOutline = GlassPainter().apply { this.night = true }

    init {
        clipChildren = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, RADIUS)
        }
        clipToOutline = true
        setWillNotDraw(false)
        current = obtain(state.current)
        arrange()
    }

    override fun hasOverlappingRendering() = false

    private val pitch: Float get() = height + GAP

    private fun obtain(kind: WidgetKind): StackWidget = built.getOrPut(kind) {
        createWidget(kind, context, services).also { w ->
            w.night = night; w.person = person
            park(w)
            addView(w, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    /** Off the clip, still attached and recorded; data refreshes wait until it slides in. */
    private fun park(w: StackWidget) {
        w.translationY = PARK
        w.parked = true
        w.active = false
    }

    private fun unpark(w: StackWidget, y: Float) {
        w.translationY = y
        w.parked = false
        w.active = shown
    }

    /** The current widget at rest, everything else parked. Only view properties change. */
    private fun arrange() {
        for (w in built.values) if (w === current) unpark(w, 0f) else park(w)
        incoming = null; incomingDir = 0
    }

    fun setShown(on: Boolean) {
        shown = on
        if (!on) { anim?.end(); if (dragging) { dragging = false; settle(0, animate = false) } }
        current.active = on
        if (on) for (w in built.values) w.refresh() // parked ones only note that they are stale
    }

    /**
     * Builds the widgets not built yet, one per quiet moment ([QuietWork]: no finger down, no
     * frame for ~3 frames) from the time the page is attached, also before it first shows (the
     * pager builds it ahead, next to StandBy). They're attached and parked, so their display
     * lists get recorded off screen too. Building one costs ~10-30 ms on the Echo (cold), and its
     * first record up to ~18 ms: done on the first slide onto it, that dropped frames (swipe p99
     * 85 ms); done on plain idle moments right after the page showed, it dropped the frames of
     * the pager's own slide. Built widgets are kept for the life of the page (16 small views).
     */
    private val warm = QuietWork {
        if (dragging || anim != null) return@QuietWork true
        val k = WidgetStacks.ORDER.firstOrNull { it !in built } ?: return@QuietWork false
        obtain(k)
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (built.size < WidgetStacks.ORDER.size) warm.start()
    }

    fun setNight(on: Boolean) {
        if (on == night) return
        night = on
        for (w in built.values) w.night = on
        invalidate()
    }

    fun setPerson(p: Profile?) {
        person = p
        for (w in built.values) w.person = p
    }

    /** The minute tick / a day change: shown widgets recompute (redrawing only if changed), parked ones go stale. */
    fun onMinute() { for (w in built.values) w.refresh() }

    /** The setting changed (web settings page, a launch extra): show the stored widget. */
    fun reload(animate: Boolean) {
        if (!state.reload()) return
        val target = state.current
        if (target == current.kind) return
        anim?.end()
        val dir = when (target) {
            WidgetStacks.step(current.kind, 1) -> 1
            WidgetStacks.step(current.kind, -1) -> -1
            else -> 0
        }
        if (animate && shown && dir != 0 && !dragging) {
            prepareIncoming(dir)
            commit(dir, persist = false)
        } else {
            current.active = false
            current = obtain(target)
            arrange()
            onChanged?.invoke()
        }
    }

    // ------------------------------------------------------------------ drag

    private fun prepareIncoming(dir: Int) {
        if (incomingDir == dir && incoming != null) return
        incoming?.let { park(it) }
        val n = obtain(WidgetStacks.step(current.kind, dir))
        unpark(n, current.translationY + dir * pitch)
        incoming = n; incomingDir = dir
    }

    /** Finger offset [dy] (negative = up, which brings the next widget up from below). */
    private fun dragTo(dy: Float) {
        if (dy == 0f) { current.translationY = 0f; incoming?.let { park(it) }; incoming = null; incomingDir = 0; return }
        val dir = if (dy < 0) 1 else -1
        prepareIncoming(dir)
        current.translationY = dy
        incoming!!.translationY = dy + dir * pitch
    }

    private fun settle(result: Int, animate: Boolean = true) {
        if (result == 0 || incoming == null) {
            val from = current.translationY
            if (!animate || from == 0f) { arrange(); return }
            run(from, 0f, Motion.EASE, 260L) { arrange() }
            return
        }
        commit(result, persist = true, animate = animate)
    }

    private fun commit(dir: Int, persist: Boolean, animate: Boolean = true) {
        val n = incoming ?: return
        val end: () -> Unit = {
            current.active = false
            current = n
            if (persist) state.move(dir)
            arrange()
            onChanged?.invoke()
        }
        if (!animate) { end(); return }
        run(current.translationY, -dir * pitch, SLIDE, SLIDE_MS, end)
    }

    /** Animates the current widget's offset [from] → [to]; the incoming one follows a pitch behind. */
    private fun run(from: Float, to: Float, interp: android.view.animation.Interpolator, ms: Long, end: () -> Unit) {
        anim?.cancel()
        val n = incoming
        val dir = incomingDir
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                val y = it.animatedValue as Float
                current.translationY = y
                n?.translationY = y + dir * pitch
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (anim === a) anim = null; if (!cancelled) end() }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                anim?.end() // a swipe during the slide: finish it, then follow the finger
                downX = e.x; downY = e.y
                axis = StackGesture.Axis.UNDECIDED
                dragging = false
                // swipes from the top edge belong to Control Center's pull-down
                tracking = e.rawY > TOP_EDGE
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!dragging) tracking = false
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (axis == StackGesture.Axis.UNDECIDED) {
                    axis = StackGesture.decide(dx, dy, slop)
                    if (axis == StackGesture.Axis.VERTICAL && tracking) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) dragTo(dy - (if (dy > 0) slop else -slop))
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    settle(StackGesture.settle(current.translationY, velocity!!.yVelocity, height.toFloat(), minFling))
                } else if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    current.onTap(e.x, e.y - StackWidget.DY)
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(0)
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        // day: the glass is in the wallpaper; Night: no glass, a dim red outline (GlassPainter.night)
        if (night) nightOutline.draw(canvas, 0f, 0f, width.toFloat(), height.toFloat(), RADIUS)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        warm.stop()
        super.onDetachedFromWindow()
    }

    companion object {
        const val RADIUS = 34f
        /** Space between two widgets while they slide (hidden by the clip, it reads as a gap). */
        private const val GAP = 40f
        private const val TOP_EDGE = 40f
        /** Where parked widgets sit: far below the clip (the render thread rejects them). */
        private const val PARK = 4000f
        private const val SLIDE_MS = 420L
        /** A short spring: a few px of overshoot, settles quickly. */
        private val SLIDE = PathInterpolator(0.3f, 1.12f, 0.5f, 1f)
    }
}

/**
 * StandBy's widgets page (like iOS StandBy's two Smart Stacks): two glass tiles side by side,
 * each a [WidgetStack]. Sits right after StandBy in the pager (order 5).
 *
 *  - The wallpaper (`clock.widgets.wallpaper`: Blue / Dusk / Graphite from Liquid Glass, or Black)
 *    and both tiles' glass, with their lift shadows, are painted once into one bitmap off the
 *    main thread; per frame the page is one bitmap plus the widgets' recorded text.
 *  - Stacks persist as `clock.widgets.left` / `.right` and follow the settings page live.
 *  - Night: black, the tiles become dim red outlines, every widget draws in #BE180E and the page
 *    dims to 0.42 like StandBy; a touch wakes it for 6 s. The light sensor runs while shown.
 *  - A recognised person: the calendar and alarm widgets show their items plus shared ones, on
 *    their own wallpaper ([Profile.wallpaper]) when they picked one.
 *
 * Debug launch extras (`--es screen widgets …`): `left <id>`, `right <id>`, `wallpaper <name>`,
 * `profile <id>`, and in debug builds `timer <seconds>` (starts a timer).
 */
@SuppressLint("ViewConstructor")
class WidgetsScreen(context: Context, private val services: VesperServices, private val nightCtl: NightController) :
    FrameLayout(context), VesperScreen {

    private val left = WidgetStack(context, services, WidgetStackState(services.settings, WidgetStacks.KEY_LEFT, WidgetStacks.DEFAULT_LEFT))
    private val right = WidgetStack(context, services, WidgetStackState(services.settings, WidgetStacks.KEY_RIGHT, WidgetStacks.DEFAULT_RIGHT))
    private val stacks = listOf(left, right)

    private var wallpaper: Bitmap? = null
    private var bakedFor: String? = null
    private var bakeJob: Job? = null
    private var shown = false
    private var nightOn = false
    private var awakeUntil = 0L
    private var dimAnim: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val redim = Runnable { applyDim(animate = true) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (shown) stacks.forEach { it.onMinute() } }
    }

    init {
        setWillNotDraw(false)
        addView(left, LayoutParams(TILE_W, TILE_H).apply { leftMargin = MARGIN; topMargin = MARGIN })
        addView(right, LayoutParams(TILE_W, TILE_H).apply { leftMargin = MARGIN + TILE_W + GAP; topMargin = MARGIN })
        stacks.forEach { it.onChanged = { invalidate() } }

        observeWhileAttached(services.settings.changes) { k ->
            when (k) {
                WidgetStacks.KEY_LEFT -> left.reload(animate = shown)
                WidgetStacks.KEY_RIGHT -> right.reload(animate = shown)
                WidgetStacks.KEY_WALLPAPER -> bake()
            }
        }
        observeWhileAttached(services.system.state) { st ->
            if (st.nightActive != nightOn) {
                nightOn = st.nightActive
                if (!nightOn) awakeUntil = 0
                stacks.forEach { it.setNight(nightOn) }
                applyDim(animate = shown)
                invalidate()
            }
        }
        observeWhileAttached(services.profiles.active) { followPerson() }
        observeWhileAttached(services.profiles.profiles) { followPerson() }
    }

    override fun hasOverlappingRendering() = false

    private fun person(): Profile? = PersonPrefs.person(services.profiles.active.value, services.profiles.profiles.value)

    private fun followPerson() {
        val p = person()
        stacks.forEach { it.setPerson(p) }
        bake() // their own wallpaper (a no-op when it's the same)
    }

    // ------------------------------------------------------------------ wallpaper + glass, baked

    /** The recognised person's wallpaper, else `clock.widgets.wallpaper`. */
    private fun wallpaperName(): String =
        PersonPrefs.widgetsWallpaper(person(), services.settings.getString(WidgetStacks.KEY_WALLPAPER, WidgetStacks.DEFAULT_WALLPAPER))

    private fun bake() {
        if (!isAttachedToWindow) return
        val name = wallpaperName()
        if (name == bakedFor) return
        bakedFor = name
        bakeJob?.cancel()
        bakeJob = services.scope.launch(Dispatchers.Default) {
            val t0 = SystemClock.uptimeMillis()
            val bmp = paint(name)
            android.util.Log.i(TAG, "baked $name in ${SystemClock.uptimeMillis() - t0} ms")
            withContext(Dispatchers.Main) {
                if (bakedFor == name && isAttachedToWindow) {
                    wallpaper = bmp // the old one is left to the GC (a display list may hold it)
                    // upload it to the GPU now (RenderThread, async), not in the first frame that shows the page
                    bmp.prepareToDraw()
                    invalidate()
                }
                else bmp.recycle()
            }
        }
    }

    /** The wallpaper, darkened a little for the widgets' text, with both tiles' glass and shadows. */
    private fun paint(name: String): Bitmap {
        val out = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val pal = GlassFace.PALETTES[name]
        val glass = GlassPainter().apply { shadow = false }
        if (pal == null) c.drawColor(Color.BLACK)
        else {
            val paper = GlassFace.paintPaper(pal) { col, a -> withAlpha(col, a) }
            c.drawBitmap(paper, null, RectF(0f, 0f, SCREEN_W.toFloat(), SCREEN_H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
            paper.recycle()
            c.drawColor(withAlpha(Color.BLACK, SCRIM))
            glass.backdrop = PreBlur.backdrop(out, SCREEN_W.toFloat(), SCREEN_H.toFloat())
            // lift shadows outside the tiles (CSS 0 10px 30px .45; blur B → σ = B/2)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                setShadowLayer(((GlassTokens.SHADOW_BLUR / 2f) - 0.5f) / 0.57735f, 0f, GlassTokens.SHADOW_DY, withAlpha(Color.BLACK, GlassTokens.SHADOW_ALPHA))
            }
            val path = Path()
            for (r in tileRects()) {
                path.reset(); path.addRoundRect(r, WidgetStack.RADIUS, WidgetStack.RADIUS, Path.Direction.CW)
                c.save(); c.clipOutPath(path); c.drawPath(path, p); c.restore()
            }
        }
        for (r in tileRects()) glass.draw(c, r.left, r.top, r.right, r.bottom, WidgetStack.RADIUS)
        glass.backdrop?.bitmap?.recycle()
        // immutable: HWUI uploads it once and never re-checks it for changes
        return out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle() }
    }

    private fun tileRects(): List<RectF> = List(2) { i ->
        val l = (MARGIN + i * (TILE_W + GAP)).toFloat()
        RectF(l, MARGIN.toFloat(), l + TILE_W, (MARGIN + TILE_H).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        if (!nightOn) wallpaper?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // page dots in the outer margins, one column per stack
        drawDots(canvas, MARGIN / 2f, left.state.current)
        drawDots(canvas, SCREEN_W - MARGIN / 2f, right.state.current)
    }

    private fun drawDots(canvas: Canvas, x: Float, current: WidgetKind) {
        val n = WidgetStacks.ORDER.size
        val i0 = WidgetStacks.indexOf(current)
        val top = SCREEN_H / 2f - (n - 1) * DOT_PITCH / 2f
        for (i in 0 until n) {
            dotPaint.color = Night.map(withAlpha(Color.WHITE, if (i == i0) 0.9f else 0.28f), nightOn)
            canvas.drawCircle(x, top + i * DOT_PITCH, if (i == i0) 3.5f else 3f, dotPaint)
        }
    }

    // ------------------------------------------------------------------ night

    private fun wake() {
        if (!nightOn) return
        awakeUntil = SystemClock.uptimeMillis() + Night.WAKE_SECONDS * 1000L
        applyDim(animate = true)
        handler.removeCallbacks(redim)
        handler.postAtTime(redim, awakeUntil + 10)
    }

    private fun applyDim(animate: Boolean) {
        val awake = SystemClock.uptimeMillis() < awakeUntil
        val target = if (nightOn && !awake) Night.DIM else 1f
        dimAnim?.cancel()
        if (!animate || alpha == target) { alpha = target; return }
        dimAnim = ValueAnimator.ofFloat(alpha, target).apply {
            duration = 1600
            interpolator = Motion.EASE
            addUpdateListener { alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return super.dispatchTouchEvent(e)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onScreenShown(args: Bundle?) {
        shown = true
        handleArgs(args)
        bake()
        stacks.forEach { it.setShown(true) }
        nightCtl.start()
        applyDim(animate = false)
    }

    override fun onScreenHidden() {
        shown = false
        stacks.forEach { it.setShown(false) }
        nightCtl.stop()
        dimAnim?.cancel(); dimAnim = null
        handler.removeCallbacks(redim)
        awakeUntil = 0
        applyDim(animate = false)
    }

    private fun handleArgs(args: Bundle?) {
        if (args == null) return
        fun str(k: String): String? = args.get(k)?.toString()
        str("left")?.let { v -> WidgetKind.byId(v)?.let { services.settings.putString(WidgetStacks.KEY_LEFT, it.id) } }
        str("right")?.let { v -> WidgetKind.byId(v)?.let { services.settings.putString(WidgetStacks.KEY_RIGHT, it.id) } }
        str("wallpaper")?.let { services.settings.putString(WidgetStacks.KEY_WALLPAPER, it) }
        str("profile")?.let { services.profiles.switchTo(it, SwitchReason.MANUAL) }
        if (services.config.debug) str("timer")?.toLongOrNull()?.let { s -> services.timers.start(s * 1000L, "Pasta") }
        // the settings flow applies them; also apply now so a launch shot shows them at once
        left.reload(animate = false); right.reload(animate = false)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        bake()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(timeReceiver)
        handler.removeCallbacksAndMessages(null)
        bakeJob?.cancel(); bakeJob = null
        bakedFor = null
        nightCtl.stop()
        super.onDetachedFromWindow()
    }

    companion object {
        const val ID = "widgets"
        private const val TAG = "VesperWidgets"
        private const val SCREEN_W = 960
        private const val SCREEN_H = 480
        private const val MARGIN = 24
        private const val GAP = 16
        const val TILE_W = (SCREEN_W - 2 * MARGIN - GAP) / 2 // 448
        const val TILE_H = SCREEN_H - 2 * MARGIN // 432
        private const val DOT_PITCH = 12f
        /** Black over the wallpaper so white text keeps its contrast through the glass. */
        private const val SCRIM = 0.28f
    }
}
