package app.vesper.feature.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.vesper.core.design.Motion
import app.vesper.core.design.Night
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.FaceConfig
import app.vesper.core.model.FaceSpec
import app.vesper.core.model.FaceView
import app.vesper.core.model.NightMode
import app.vesper.core.model.Presence
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.core.model.observeWhileAttached
import kotlin.math.abs

/**
 * The StandBy screen: the selected face full screen, minute ticks forwarded to it.
 *
 *  - **Face switching:** swipe up / down (like iOS StandBy). The face follows the finger, the
 *    neighbour slides in, release past a quarter screen (or flick) to switch. Swipes starting in
 *    the top 40 px are left alone (Control Center's pull-down).
 *  - **Edit mode** (long-press, Customize.dc.html): the face zooms to 0.8 into a 34 px rounded
 *    card, with glass controls ([EditOverlay]): Night toggle, Done, page dots, colour capsule.
 *    Tapping the face toggles its complications (faces that have them).
 *    Choices persist in settings: `clock.face`, `clock.color.<id>`, `clock.complications.<id>`.
 *  - **Profiles**: while the camera has recognised someone (`services.profiles.active` is not
 *    shared), StandBy shows that person's `faceId` + `color`, and edits go into their profile.
 *  - **Night** (Night.dc.html): faces draw in #BE180E and the stage dims to 0.42 (1.6 s ease);
 *    a tap (or presence from the camera) wakes it to full for 6 s. The light sensor runs only
 *    while this screen is shown ([NightController]).
 *
 * Debug launch extras (`--es screen standby …`): `face <id>`, `edit true`, `palette open`,
 * `nightmode on|off|auto`, `wake true`, `color RRGGBB`, `glass 0..1`, `wallpaper Blue|Dusk|Graphite`,
 * `complications true|false`, and in debug builds `fakelux <lux>` (feeds the night state machine).
 */
@SuppressLint("ViewConstructor")
class StandbyScreen(context: Context, private val services: VesperServices, private val night: NightController) :
    FrameLayout(context), VesperScreen, EditOverlay.Callbacks {

    private class Slot(val spec: FaceSpec, val face: FaceView)

    /** Holds the faces; scaled + clipped in edit mode, dimmed in Night. */
    private val stage = FrameLayout(context)
    private var current: Slot? = null
    private var neighbour: Slot? = null
    private var neighbourDir = 0
    /** Faces built during this StandBy visit, by id (see [obtain], [arrange]). */
    private val built = LinkedHashMap<String, Slot>()
    private var shown = false
    private var nightOn = false
    private var awakeUntil = 0L
    private val handler = Handler(Looper.getMainLooper())

    // gestures
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracking = false
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private var switchAnim: ValueAnimator? = null

    // edit mode
    private var editing = false
    private var editExp = 0
    private val cornerPath = Path()
    private val cornerOval = RectF()
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var editProgress = 0f
    private var editAnim: ValueAnimator? = null
    private var overlay: EditOverlay? = null
    private var dimAnim: ValueAnimator? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            for (b in built.values) if (b.face.view.visibility == VISIBLE) b.face.onTimeChanged()
        }
    }
    private val longPress = Runnable { longPressed = true; tracking = false; setEditing(true) }
    private val redim = Runnable { applyDim(animate = true) }

    init {
        // No background of our own: the window is black and every face paints black. On the
        // Mali-T720 each full-screen fill costs ~4 ms of GPU per frame, and this one was hidden.
        clipChildren = false
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Parked neighbours sit just outside the stage; clipChildren only clips each face to its
        // own (translated) bounds, so clip the stage itself (shows once it's zoomed out in edit mode).
        stage.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> v.clipBounds = android.graphics.Rect(0, 0, r - l, b - t) }
        observeWhileAttached(services.faces.faces) { list ->
            releaseBuilt(list.mapTo(HashSet()) { it.id })
            if (current == null || services.faces.byId(current!!.spec.id) == null) showFace(selectedFaceId(), persist = false) else { arrange(); syncOverlay() }
        }
        observeWhileAttached(services.profiles.active) { followProfile() }
        // the web settings page (or this screen) changed a choice: apply it live
        observeWhileAttached(services.settings.changes) { k -> if (ClockSettings.affectsStandby(k)) followProfile() }
        observeWhileAttached(services.profiles.profiles) { followProfile() }
        observeWhileAttached(services.system.state) { st ->
            val n = st.nightActive
            if (n != nightOn) {
                nightOn = n
                if (!n) awakeUntil = 0
                reconfigure()
                applyDim(animate = true)
            }
            syncOverlay()
        }
        var lastPresence = Presence.UNKNOWN
        observeWhileAttached(services.system.state) { st ->
            if (st.presence == Presence.PRESENT && lastPresence != Presence.PRESENT && nightOn) wake()
            lastPresence = st.presence
        }
    }

    // ------------------------------------------------------------------ faces

    private fun faces(): List<FaceSpec> = services.faces.faces.value
    /**
     * The recognised person's profile (camera, `services.profiles.active`), latest copy; null for
     * [Profile.SHARED], which means the user's own choice in settings.
     */
    private fun profile(): Profile? {
        val a = services.profiles.active.value
        if (a.id == Profile.SHARED_ID) return null
        return services.profiles.profiles.value.firstOrNull { it.id == a.id } ?: a
    }

    private fun selectedFaceId(): String = profile()?.faceId ?: services.settings.getString(ClockFeature.KEY_FACE, "digital")

    private fun colorFor(spec: FaceSpec): Int {
        val p = profile()
        if (p != null && p.faceId == spec.id && spec.colorOptions.isNotEmpty()) return p.color
        return services.settings.getColor(ClockSettings.colorKey(spec.id), spec.colorOptions.firstOrNull() ?: Color.WHITE)
    }

    /** A face / colour the user picked: into the active profile, or settings when shared. */
    private fun rememberFace(id: String) {
        val p = profile()
        if (p != null) { if (p.faceId != id) services.profiles.upsert(p.copy(faceId = id)) }
        else services.settings.putString(ClockFeature.KEY_FACE, id)
    }

    private fun rememberColor(spec: FaceSpec, color: Int) {
        val p = profile()
        if (p != null && p.faceId == spec.id) services.profiles.upsert(p.copy(color = color))
        else services.settings.putColor(ClockSettings.colorKey(spec.id), color)
    }

    /** Someone was recognised (or left): show their face and colour, animated when idle. */
    private fun followProfile() {
        val cur = current ?: return
        val want = selectedFaceId()
        if (want != cur.spec.id) {
            val i = indexOf(want)
            if (shown && !editing && !dragging && switchAnim == null && i >= 0) slideTo(i, persist = false)
            else showFace(want, persist = false)
        }
        reconfigure()
        syncOverlay()
    }
    private fun indexOf(id: String?) = faces().indexOfFirst { it.id == id }

    private fun configFor(spec: FaceSpec): FaceConfig {
        val color = colorFor(spec)
        val complications = services.settings.getBoolean(ClockSettings.complicationsKey(spec.id), true)
        return FaceConfig(color, complications, nightOn)
    }

    private fun reconfigure() {
        for (s in built.values) s.face.setConfig(configFor(s.spec))
    }

    private fun create(spec: FaceSpec): Slot {
        val t0 = SystemClock.uptimeMillis()
        val f = spec.factory(context)
        f.setConfig(configFor(spec))
        stage.addView(f.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        f.onTimeChanged()
        if (services.config.debug) Log.d("VesperClock", "created face ${spec.id} in ${SystemClock.uptimeMillis() - t0} ms")
        return Slot(spec, f).also { built[spec.id] = it }
    }

    /** The face's view, built once per StandBy visit (a face costs 25–50 ms to build on the Echo). */
    private fun obtain(spec: FaceSpec): Slot {
        val s = built[spec.id] ?: return create(spec)
        s.face.view.visibility = VISIBLE
        s.face.onTimeChanged()
        return s
    }

    /** Stops [slot]'s motion; [arrange] decides where it sits. */
    private fun park(slot: Slot?) {
        slot?.face?.onScreenHidden()
    }

    /**
     * Current face at 0; built neighbours visible just off screen, so their display lists stay
     * recorded and the first drag frame only moves them; every other built face invisible.
     */
    private fun arrange() {
        val cur = current ?: return
        val i = indexOf(cur.spec.id)
        val h = stage.height.toFloat()
        for ((id, s) in built) {
            val v = s.face.view
            val j = indexOf(id)
            val d = if (s === cur) 0 else if (j < 0) 9 else j - i
            if (d in -1..1) { v.translationY = d * h; v.visibility = VISIBLE } else v.visibility = INVISIBLE
        }
        syncFaceLayers()
    }

    /**
     * Edit mode draws each visible face from its own GPU layer (rendered once, re-rendered only
     * when that face redraws: colour, minute, Analog's second hand). Scaling the stage, the
     * overlay fades and face swipes then move textured quads instead of re-issuing every glyph,
     * which is what made edit mode render-thread bound on the Echo. While the edit transition
     * runs, only the current face has one (no extra layer renders mid-animation).
     */
    private fun syncFaceLayers() {
        val inEdit = (editing || editAnim != null) && (editExp and EXP_NO_FACE_LAYERS) == 0
        for (s in built.values) {
            val v = s.face.view
            val want = inEdit && v.visibility == VISIBLE && (s === current || editAnim == null)
            val type = if (want) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
            if (v.layerType != type) v.setLayerType(type, null)
        }
    }

    /** Builds the current face's missing neighbours one per idle moment, never mid-animation. */
    private val warm = MessageQueue.IdleHandler {
        val cur = current
        if (!shown || cur == null) return@IdleHandler false
        if (dragging || switchAnim != null || editAnim != null) return@IdleHandler true
        val i = indexOf(cur.spec.id)
        val next = listOfNotNull(faces().getOrNull(i + 1), faces().getOrNull(i - 1)).firstOrNull { f -> f.id !in built }
            ?: return@IdleHandler false
        create(next)
        arrange()
        true
    }

    private fun scheduleWarm() {
        Looper.myQueue().removeIdleHandler(warm)
        Looper.myQueue().addIdleHandler(warm)
    }

    /** Frees every face but the current one (StandBy hidden, or faces unregistered). */
    private fun releaseBuilt(keepIds: Set<String>) {
        val it = built.entries.iterator()
        while (it.hasNext()) {
            val (id, s) = it.next()
            if (s === current || id in keepIds) continue
            s.face.onScreenHidden()
            stage.removeView(s.face.view)
            it.remove()
        }
    }

    /** Shows face [id] immediately (no animation); [persist] = the user chose it. */
    fun showFace(id: String, persist: Boolean = true) {
        val spec = services.faces.byId(id) ?: faces().firstOrNull() ?: return
        if (spec.id == current?.spec?.id) return
        switchAnim?.cancel()
        park(neighbour); neighbour = null
        park(current)
        current = obtain(spec).also { if (shown) it.face.onScreenShown(null) }
        arrange()
        if (persist) rememberFace(spec.id)
        syncOverlay()
        if (shown) scheduleWarm()
    }

    /** Drag offset in screen px (negative = finger moved up = next face). */
    private fun dragTo(dyScreen: Float) {
        val cur = current ?: return
        val dy = dyScreen / stage.scaleY
        val dir = if (dy < 0) 1 else -1
        val target = indexOf(cur.spec.id) + dir
        if (dy == 0f || target !in faces().indices) {
            neighbour = null
            arrange()
            cur.face.view.translationY = dy / 3f // rubber band at the ends
            return
        }
        if (neighbour?.spec?.id != faces()[target].id) {
            neighbour = obtain(faces()[target])
            neighbourDir = dir
            arrange()
        }
        val h = stage.height.toFloat()
        cur.face.view.translationY = dy
        neighbour!!.face.view.translationY = dy + dir * h
    }

    private fun settle(vy: Float) {
        val cur = current ?: return
        val h = stage.height.toFloat()
        val dy = cur.face.view.translationY
        val n = neighbour
        val commit = n != null && (abs(dy) > h / 4 || (abs(vy) > minFling && (vy < 0) == (neighbourDir > 0)))
        val to = if (commit) -neighbourDir * h else 0f
        animateStage(dy, to) {
            if (commit && n != null) becomeCurrent(n, persist = true)
            else { neighbour = null; arrange() }
        }
    }

    private fun becomeCurrent(n: Slot, persist: Boolean) {
        park(current)
        current = n; neighbour = null
        arrange()
        if (shown) n.face.onScreenShown(null)
        if (persist) rememberFace(n.spec.id)
        syncOverlay()
        scheduleWarm()
    }

    private fun animateStage(from: Float, to: Float, end: () -> Unit) {
        switchAnim?.cancel()
        val cur = current ?: return
        val h = stage.height.toFloat()
        switchAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = 380
            interpolator = Motion.EASE
            addUpdateListener {
                val y = it.animatedValue as Float
                cur.face.view.translationY = y
                neighbour?.face?.view?.translationY = y + neighbourDir * h
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (switchAnim === a) switchAnim = null; if (!cancelled) end() }
            })
            start()
        }
    }

    /** Switches to face [index] with the slide animation (page dots, profiles). */
    private fun slideTo(index: Int, persist: Boolean = true) {
        val cur = current ?: return
        val from = indexOf(cur.spec.id)
        if (index == from || index !in faces().indices || switchAnim != null) return
        neighbourDir = if (index > from) 1 else -1
        neighbour = obtain(faces()[index])
        neighbour!!.face.view.translationY = neighbourDir * stage.height.toFloat()
        animateStage(0f, -neighbourDir * stage.height.toFloat()) { neighbour?.let { becomeCurrent(it, persist) } }
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
        val target = if (nightOn && !awake && !editing) Night.DIM else 1f
        dimAnim?.cancel()
        if (!animate || stage.alpha == target) { stage.alpha = target; return }
        dimAnim = ValueAnimator.ofFloat(stage.alpha, target).apply {
            duration = 1600
            interpolator = Motion.EASE
            addUpdateListener { stage.alpha = it.animatedValue as Float }
            start()
        }
    }

    // ------------------------------------------------------------------ edit mode

    private fun setEditing(on: Boolean, animate: Boolean = true) {
        if (on == editing && editAnim == null) return
        editing = on
        if (on && overlay == null) {
            overlay = EditOverlay(context, this).also { o ->
                addView(o, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                o.alpha = 0f
            }
            syncOverlay()
        }
        stage.pivotX = EDIT_PIVOT_X
        stage.pivotY = EDIT_PIVOT_Y
        applyDim(animate = false)
        editAnim?.cancel()
        val target = if (on) 1f else 0f
        val apply = { p: Float ->
            editProgress = p
            val s = 1f - (1f - EDIT_SCALE) * p
            stage.scaleX = s; stage.scaleY = s
            invalidate() // corner masks
            overlay?.alpha = p.coerceIn(0f, 1f)
        }
        if (!animate) { apply(target); finishEdit(on); return }
        // the overlay doesn't change while it fades: one layer render, then alpha on a quad
        if ((editExp and EXP_NO_OVERLAY_LAYER) == 0) overlay?.setLayerType(LAYER_TYPE_HARDWARE, null)
        editAnim = ValueAnimator.ofFloat(editProgress, target).apply {
            duration = if (on) 520 else 420
            interpolator = if (on) Motion.SPRING_SOFT else Motion.EASE
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (editAnim === a) editAnim = null; if (!cancelled) finishEdit(editing) }
            })
            start()
        }
        syncFaceLayers()
    }

    private fun finishEdit(on: Boolean) {
        // at rest the overlay animates on its own (palette spring): a layer would re-render each frame
        overlay?.setLayerType(LAYER_TYPE_NONE, null)
        syncFaceLayers()
        invalidate()
        if (on) return
        overlay?.let { removeView(it) }
        overlay = null
        applyDim(animate = true)
    }

    /**
     * The zoomed face's rounded corners (Customize.dc.html: 34 px). Painted as black corner
     * pieces over the stage (StandBy's background is black) rather than `clipToOutline`: on the
     * Echo, an outline clip whose radius changes every frame made the whole edit transition
     * GPU-bound (21 ms frames); four small paths cost nothing.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val r = super.drawChild(canvas, child, drawingTime)
        if (child === stage && editProgress > 0f) drawCorners(canvas)
        return r
    }

    private fun drawCorners(canvas: Canvas) {
        val sc = stage.scaleX
        val l = EDIT_PIVOT_X * (1f - sc)
        val t = EDIT_PIVOT_Y * (1f - sc)
        val rt = EDIT_PIVOT_X + (stage.width - EDIT_PIVOT_X) * sc
        val b = EDIT_PIVOT_Y + (stage.height - EDIT_PIVOT_Y) * sc
        val rad = EDIT_RADIUS / EDIT_SCALE * editProgress.coerceIn(0f, 1.2f) * sc
        if (rad < 0.5f) return
        val p = cornerPath
        p.rewind()
        corner(p, l, t, rad, 180f)        // top-left
        corner(p, rt - rad, t, rad, 270f)  // top-right
        corner(p, rt - rad, b - rad, rad, 0f)
        corner(p, l, b - rad, rad, 90f)
        canvas.drawPath(p, cornerPaint)
    }

    /** The piece of the [r]×[r] square at ([x],[y]) outside the quarter circle facing [startDeg]. */
    private fun corner(p: Path, x: Float, y: Float, r: Float, startDeg: Float) {
        val cx = if (startDeg == 180f || startDeg == 90f) x + r else x
        val cy = if (startDeg == 180f || startDeg == 270f) y + r else y
        val ox = if (startDeg == 180f || startDeg == 90f) x else x + r // the square's outer corner
        val oy = if (startDeg == 180f || startDeg == 270f) y else y + r
        cornerOval.set(cx - r, cy - r, cx + r, cy + r)
        p.moveTo(ox, oy)
        p.arcTo(cornerOval, startDeg, 90f, false)
        p.close()
    }

    private fun syncOverlay() {
        val o = overlay ?: return
        val cur = current ?: return
        val list = faces()
        o.bind(cur.spec.name, list.size, indexOf(cur.spec.id), cur.spec.colorOptions, configFor(cur.spec).color,
            services.system.state.value.nightMode == NightMode.ON)
    }

    override fun onDone() = setEditing(false)

    /** Night pill: forces Night on, or back to automatic (light sensor). */
    override fun onToggleNight() {
        val mode = if (services.system.state.value.nightMode == NightMode.ON) NightMode.AUTO else NightMode.ON
        services.systemControls.setNightMode(mode)
        syncOverlay()
    }

    override fun onPickFace(index: Int) { overlay?.colors?.setOpen(false); slideTo(index) }

    override fun onPickColor(color: Int) {
        val cur = current ?: return
        rememberColor(cur.spec, color)
        reconfigure()
        syncOverlay()
    }

    override fun onPreviewTap() {
        val cur = current ?: return
        if (!cur.spec.supportsComplications) return
        val key = ClockSettings.complicationsKey(cur.spec.id)
        services.settings.putBoolean(key, !services.settings.getBoolean(key, true))
        reconfigure()
    }

    // ------------------------------------------------------------------ touch

    // A DOWN always reaches onInterceptTouchEvent first; later events come here only while a
    // child (an edit-mode control) has the gesture, and stop once we intercept a drag.
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        track(e, intercepting = true)
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) track(e, intercepting = false)
        return true
    }

    private fun track(e: MotionEvent, intercepting: Boolean) {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity!!.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false; longPressed = false
                tracking = e.y > TOP_EDGE && switchAnim == null
                if (!editing) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> { handler.removeCallbacks(longPress); if (!dragging) tracking = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop || abs(dy) > slop) handler.removeCallbacks(longPress)
                if (!dragging && tracking && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f && faces().size > 1) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    overlay?.colors?.setOpen(false)
                }
                if (dragging) dragTo(dy - (if (dy > 0) slop else -slop))
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    velocity!!.computeCurrentVelocity(1000)
                    settle(velocity!!.yVelocity)
                } else {
                    if (!intercepting && !longPressed && abs(e.x - downX) < slop && abs(e.y - downY) < slop) onTap(e.x, e.y)
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                if (dragging) settle(0f)
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
    }

    private fun onTap(x: Float, y: Float) {
        val o = overlay
        if (editing && o != null) {
            if (o.colors.open) { o.colors.setOpen(false); return }
            if (o.previewHit(x, y)) o.previewTap()
            return
        }
        wake()
    }

    // ------------------------------------------------------------------ screen lifecycle

    override fun onScreenShown(args: Bundle?) {
        shown = true
        handleArgs(args)
        if (current == null) showFace(selectedFaceId(), persist = false)
        current?.face?.onTimeChanged()
        current?.face?.onScreenShown(args)
        night.start()
        applyDim(animate = false)
        scheduleWarm()
    }

    override fun onScreenHidden() {
        shown = false
        current?.face?.onScreenHidden()
        switchAnim?.cancel()
        neighbour = null
        Looper.myQueue().removeIdleHandler(warm)
        releaseBuilt(emptySet()) // only the current face stays built while other screens show
        arrange()
        night.stop()
        handler.removeCallbacks(longPress)
        if (editing) setEditing(false, animate = false)
    }

    private fun handleArgs(args: Bundle?) {
        // null = resume / home: keep edit mode (it ends on Done or when the screen is hidden)
        if (args == null) return
        fun str(k: String): String? = args.get(k)?.toString()
        str("profile")?.let { services.profiles.switchTo(it, SwitchReason.MANUAL) }
        str("face")?.let { showFace(it) }
        if (services.config.debug) str("editexp")?.toIntOrNull()?.let { editExp = it }
        str("nightstart")?.let { services.settings.putString(ClockSettings.KEY_NIGHT_START, it) }
        str("nightend")?.let { services.settings.putString(ClockSettings.KEY_NIGHT_END, it) }
        // same values as the settings page: auto | schedule | on | off
        str("nightmode")?.let { m -> services.settings.putString(ClockSettings.KEY_NIGHT_MODE, m.lowercase()) }
        val cur = current
        if (cur != null) {
            str("color")?.let { c -> parseHexColor(c)?.let { rememberColor(cur.spec, it) } }
            str("complications")?.let { services.settings.putBoolean(ClockSettings.complicationsKey(cur.spec.id), it.toBoolean()) }
            str("glass")?.toFloatOrNull()?.let { services.settings.putFloat(GlassFace.KEY_GLASS, it) }
            str("wallpaper")?.let { services.settings.putString(GlassFace.KEY_WALLPAPER, it) }
            reconfigure()
        }
        if (services.config.debug) str("fakelux")?.toFloatOrNull()?.let { Log.i("VesperClock", "fake lux $it"); night.onLux(it) }
        when (str("edit")) {
            "true" -> setEditing(true, animate = false)
            "false" -> setEditing(false, animate = false)
        }
        if (str("palette") == "open") overlay?.colors?.setOpen(true, animate = false)
        if (str("wake") == "true") wake()
        syncOverlay()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        current?.face?.onTimeChanged()
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(timeReceiver)
        handler.removeCallbacksAndMessages(null)
        Looper.myQueue().removeIdleHandler(warm)
        night.stop()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val EDIT_SCALE = 0.8f
        private const val EDIT_RADIUS = 34f
        /** Pivot so the 0.8× face lands at (96, 40), as in Customize.dc.html. */
        private const val EDIT_PIVOT_X = 96f / (1f - EDIT_SCALE)
        private const val EDIT_PIVOT_Y = 40f / (1f - EDIT_SCALE)
        private const val TOP_EDGE = 40f
        // debug launch extra `editexp` (bits) to measure the edit-mode drawing choices on the Echo
        private const val EXP_NO_OVERLAY_LAYER = 2
        private const val EXP_NO_FACE_LAYERS = 4
    }
}
