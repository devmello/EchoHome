package com.echobedside.feature.clock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.echobedside.core.design.Motion
import com.echobedside.core.design.Night
import com.echobedside.core.model.EchoScreen
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.FaceConfig
import com.echobedside.core.model.FaceSpec
import com.echobedside.core.model.FaceView
import com.echobedside.core.model.NightMode
import com.echobedside.core.model.Presence
import com.echobedside.core.model.observeWhileAttached
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
 *  - **Night** (Night.dc.html): faces draw in #BE180E and the stage dims to 0.42 (1.6 s ease);
 *    a tap (or presence from the camera) wakes it to full for 6 s. The light sensor runs only
 *    while this screen is shown ([NightController]).
 *
 * Debug launch extras (`--es screen standby …`): `face <id>`, `edit true`, `palette open`,
 * `nightmode on|off|auto`, `wake true`, `color #RRGGBB`, `glass 0..1`, `wallpaper Blue|Dusk|Graphite`,
 * `complications true|false`, and in debug builds `fakelux <lux>` (feeds the night state machine).
 */
@SuppressLint("ViewConstructor")
class StandbyScreen(context: Context, private val services: EchoServices, private val night: NightController) :
    FrameLayout(context), EchoScreen, EditOverlay.Callbacks {

    private class Slot(val spec: FaceSpec, val face: FaceView)

    /** Holds the faces; scaled + clipped in edit mode, dimmed in Night. */
    private val stage = FrameLayout(context)
    private var current: Slot? = null
    private var neighbour: Slot? = null
    private var neighbourDir = 0
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
    private var editProgress = 0f
    private var editAnim: ValueAnimator? = null
    private var overlay: EditOverlay? = null
    private var dimAnim: ValueAnimator? = null

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { current?.face?.onTimeChanged(); neighbour?.face?.onTimeChanged() }
    }
    private val longPress = Runnable { longPressed = true; tracking = false; setEditing(true) }
    private val redim = Runnable { applyDim(animate = true) }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, EDIT_RADIUS / EDIT_SCALE * editProgress)
        }
        observeWhileAttached(services.faces.faces) { if (current == null || services.faces.byId(current!!.spec.id) == null) showFace(selectedFaceId()) else syncOverlay() }
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
    private fun selectedFaceId(): String = services.settings.getString(ClockFeature.KEY_FACE, "digital")
    private fun indexOf(id: String?) = faces().indexOfFirst { it.id == id }

    private fun configFor(spec: FaceSpec): FaceConfig {
        val color = services.settings.getInt("clock.color.${spec.id}", spec.colorOptions.firstOrNull() ?: Color.WHITE)
        val complications = services.settings.getBoolean("clock.complications.${spec.id}", true)
        return FaceConfig(color, complications, nightOn)
    }

    private fun reconfigure() {
        current?.let { it.face.setConfig(configFor(it.spec)) }
        neighbour?.let { it.face.setConfig(configFor(it.spec)) }
    }

    private fun create(spec: FaceSpec): Slot {
        val f = spec.factory(context)
        f.setConfig(configFor(spec))
        stage.addView(f.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        f.onTimeChanged()
        return Slot(spec, f)
    }

    private fun remove(slot: Slot?) {
        slot ?: return
        slot.face.onScreenHidden()
        stage.removeView(slot.face.view)
    }

    /** Shows face [id] immediately (no animation). */
    fun showFace(id: String) {
        val spec = services.faces.byId(id) ?: faces().firstOrNull() ?: return
        if (spec.id == current?.spec?.id) return
        switchAnim?.cancel()
        remove(neighbour); neighbour = null
        remove(current)
        current = create(spec).also { it.face.view.translationY = 0f; if (shown) it.face.onScreenShown(null) }
        services.settings.putString(ClockFeature.KEY_FACE, spec.id)
        syncOverlay()
    }

    /** Drag offset in screen px (negative = finger moved up = next face). */
    private fun dragTo(dyScreen: Float) {
        val cur = current ?: return
        val dy = dyScreen / stage.scaleY
        val dir = if (dy < 0) 1 else -1
        val target = indexOf(cur.spec.id) + dir
        if (dy == 0f || target !in faces().indices) {
            remove(neighbour); neighbour = null
            cur.face.view.translationY = dy / 3f // rubber band at the ends
            return
        }
        if (neighbour?.spec?.id != faces()[target].id) {
            remove(neighbour)
            neighbour = create(faces()[target])
            neighbourDir = dir
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
            if (commit && n != null) {
                remove(cur)
                current = n; neighbour = null
                n.face.view.translationY = 0f
                if (shown) n.face.onScreenShown(null)
                services.settings.putString(ClockFeature.KEY_FACE, n.spec.id)
                syncOverlay()
            } else {
                remove(neighbour); neighbour = null
                cur.face.view.translationY = 0f
            }
        }
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

    /** Switches to face [index] with the slide animation (page dots). */
    private fun slideTo(index: Int) {
        val cur = current ?: return
        val from = indexOf(cur.spec.id)
        if (index == from || index !in faces().indices || switchAnim != null) return
        remove(neighbour)
        neighbourDir = if (index > from) 1 else -1
        neighbour = create(faces()[index])
        neighbour!!.face.view.translationY = neighbourDir * stage.height.toFloat()
        settleTo()
    }

    private fun settleTo() {
        val cur = current ?: return
        val n = neighbour ?: return
        animateStage(0f, -neighbourDir * stage.height.toFloat()) {
            remove(cur)
            current = n; neighbour = null
            n.face.view.translationY = 0f
            if (shown) n.face.onScreenShown(null)
            services.settings.putString(ClockFeature.KEY_FACE, n.spec.id)
            syncOverlay()
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
        stage.clipToOutline = true
        applyDim(animate = false)
        editAnim?.cancel()
        val target = if (on) 1f else 0f
        val apply = { p: Float ->
            editProgress = p
            val s = 1f - (1f - EDIT_SCALE) * p
            stage.scaleX = s; stage.scaleY = s
            stage.invalidateOutline()
            overlay?.alpha = p.coerceIn(0f, 1f)
        }
        if (!animate) { apply(target); finishEdit(on); return }
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
    }

    private fun finishEdit(on: Boolean) {
        if (on) return
        stage.clipToOutline = false
        overlay?.let { removeView(it) }
        overlay = null
        applyDim(animate = true)
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
        services.settings.putInt("clock.color.${cur.spec.id}", color)
        reconfigure()
        syncOverlay()
    }

    override fun onPreviewTap() {
        val cur = current ?: return
        if (!cur.spec.supportsComplications) return
        val key = "clock.complications.${cur.spec.id}"
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
                } else if (!intercepting && !longPressed && abs(e.x - downX) < slop && abs(e.y - downY) < slop) {
                    onTap(e.x, e.y)
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
        if (current == null) showFace(selectedFaceId())
        current?.face?.onTimeChanged()
        current?.face?.onScreenShown(args)
        night.start()
        applyDim(animate = false)
    }

    override fun onScreenHidden() {
        shown = false
        current?.face?.onScreenHidden()
        neighbour?.face?.onScreenHidden()
        night.stop()
        handler.removeCallbacks(longPress)
        if (editing) setEditing(false, animate = false)
    }

    private fun handleArgs(args: Bundle?) {
        if (args == null) { if (editing) setEditing(false); return }
        fun str(k: String): String? = args.get(k)?.toString()
        str("face")?.let { showFace(it) }
        str("nightmode")?.let { m -> NightMode.entries.firstOrNull { it.name.equals(m, true) }?.let { services.systemControls.setNightMode(it) } }
        val cur = current
        if (cur != null) {
            str("color")?.let { c -> runCatching { Color.parseColor(c) }.getOrNull()?.let { services.settings.putInt("clock.color.${cur.spec.id}", it) } }
            str("complications")?.let { services.settings.putBoolean("clock.complications.${cur.spec.id}", it.toBoolean()) }
            str("glass")?.toFloatOrNull()?.let { services.settings.putFloat(GlassFace.KEY_GLASS, it) }
            str("wallpaper")?.let { services.settings.putString(GlassFace.KEY_WALLPAPER, it) }
            reconfigure()
        }
        if (services.config.debug) str("fakelux")?.toFloatOrNull()?.let { Log.i("EchoClock", "fake lux $it"); night.onLux(it) }
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
    }
}
