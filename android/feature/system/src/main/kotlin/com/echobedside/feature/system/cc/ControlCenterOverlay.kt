package com.echobedside.feature.system.cc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.FrameLayout
import com.echobedside.core.design.Backdrop
import com.echobedside.core.model.observeWhileAttached
import com.echobedside.feature.system.SystemUi
import com.echobedside.feature.system.ui.EASE
import com.echobedside.feature.system.ui.Snapshot
import com.echobedside.feature.system.ui.bezier

/**
 * Control Center (ControlCenter.dc.html), overlay z 600.
 *
 * Closed it's invisible and only claims a downward swipe that starts in the top [EDGE_PX] px
 * (outside the island). Opening: a PixelCopy of the window at 1/4 size is blurred once into the
 * background (blur 24, saturate 1.3, 55 % over black, scale 1.04, the blue/purple tints) and
 * again for the tiles' glass (blur 20, saturate 1.8, brightness 1.1). The panel slides with
 * translationY (cubic-bezier(0.3, 1.15, 0.5, 1) 0.5 s), the background fades 0.45 s — both are
 * RenderNode properties, so the slide costs no redraws.
 */
@SuppressLint("ViewConstructor")
internal class ControlCenterOverlay(context: Context, private val ui: SystemUi) : FrameLayout(context) {
    private val bg = BackgroundView(context)
    private val panel = ControlCenterPanel(context, ui) { close() }
    private var open = false
    private var tracking = false
    private var startY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var snapshotPending = false

    private val back: () -> Boolean = { if (open) { close(); true } else false }

    init {
        addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        bg.visibility = GONE; panel.visibility = GONE
        panel.translationY = -480f
        observeWhileAttached(ui.ccRequests) { if (it) openNow() else close() }
        observeWhileAttached(ui.debug) { b -> when (b?.getString("cc")) { "open" -> openNow(); "close" -> close() } }
        panel.onDragClose = { dy, done, fling -> dragClose(dy, done, fling) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ui.backHandlers += back
        if (ui.debugBuild && (context as? Activity)?.intent?.getStringExtra("cc") == "open") post { openNow() }
    }

    override fun onDetachedFromWindow() { ui.backHandlers -= back; super.onDetachedFromWindow() }

    // --- the edge pull (closed) ---

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (open && !tracking) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y > EDGE_PX || ui.islandHitAreas.any { it.contains(e.x, e.y) }) return false
                tracking = true; dragging = false; startY = e.y
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                takeSnapshot()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocity?.addMovement(e)
                val dy = e.y - startY
                if (!dragging && dy > 8f) { dragging = true; beginReveal() }
                if (dragging) setProgress((dy / 300f).coerceIn(0f, 1f), follow = dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!tracking) return false
                tracking = false
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                velocity?.recycle(); velocity = null
                val dy = e.y - startY
                if (dragging && e.actionMasked == MotionEvent.ACTION_UP && (dy > 110f || vy > 700f)) settle(true) else if (dragging) settle(false)
                return true
            }
        }
        return tracking
    }

    private fun takeSnapshot(then: (() -> Unit)? = null) {
        val act = context as? Activity ?: run { then?.invoke(); return }
        if (snapshotPending) { then?.let { post(it) }; return }
        snapshotPending = true
        Snapshot.window(act) { bmp ->
            snapshotPending = false
            if (bmp != null) applySnapshot(bmp)
            then?.invoke()
        }
    }

    private fun applySnapshot(shot: Bitmap) {
        // background: blur(24px) saturate(1.3), opacity .55 over black, scale(1.04), + two radial tints
        val blurred = Snapshot.blurSmall(shot, 24f, 1.3f, 1f)
        shot.recycle()
        val w = blurred.width; val h = blurred.height
        val comp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(comp)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        p.alpha = (255 * 0.55f).toInt()
        c.save(); c.scale(1.04f, 1.04f, w / 2f, h / 2f); c.drawBitmap(blurred, 0f, 0f, p); c.restore()
        blurred.recycle()
        p.alpha = 255
        // radial-gradient(ellipse 70% 60% at 30% 20%, rgba(10,132,255,.35), transparent 70%)
        radial(c, p, w * 0.3f, h * 0.2f, w * 0.7f, h * 0.6f, Color.argb(89, 10, 132, 255))
        // radial-gradient(ellipse 60% 60% at 85% 90%, rgba(191,90,242,.28), transparent 70%)
        radial(c, p, w * 0.85f, h * 0.9f, w * 0.6f, h * 0.6f, Color.argb(71, 191, 90, 242))
        bg.setBitmap(comp)
        // the tiles' glass samples the background: blur(20px) saturate(1.8) brightness(1.1)
        val glass = Snapshot.blurSmall(comp, 20f, 1.8f, 1.1f)
        panel.setBackdrop(Backdrop(glass, 0f, 0f, width.toFloat().coerceAtLeast(960f), height.toFloat().coerceAtLeast(480f)))
    }

    /** CSS `ellipse RX RY at X Y` with a stop at 70 %: a radial gradient scaled to an ellipse. */
    private fun radial(c: Canvas, p: Paint, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(color, Color.TRANSPARENT), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
        val m = Matrix(); m.setScale(rx, ry); m.postTranslate(cx, cy); g.setLocalMatrix(m)
        p.shader = g
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), p)
        p.shader = null
    }

    private fun beginReveal() {
        bg.visibility = VISIBLE; panel.visibility = VISIBLE
        panel.onOpening()
        ui.ccOpen.value = true
    }

    private fun setProgress(p: Float, follow: Float) {
        bg.alpha = p
        panel.alpha = (p * 1.6f).coerceAtMost(1f)
        panel.translationY = (-480f + follow * 1.6f).coerceAtMost(0f)
    }

    private fun settle(toOpen: Boolean) {
        open = toOpen
        if (toOpen) {
            panel.animate().translationY(0f).alpha(1f).setDuration(500).setInterpolator(PANEL_SPRING).withEndAction(null).start()
            bg.animate().alpha(1f).setDuration(450).setInterpolator(EASE).start()
            panel.announceForAccessibility("Control Center")
        } else {
            panel.animate().translationY(-480f).alpha(0f).setDuration(380).setInterpolator(EASE).withEndAction { finishClose() }.start()
            bg.animate().alpha(0f).setDuration(380).setInterpolator(EASE).start()
        }
    }

    private fun openNow() {
        if (open) return
        open = true
        // copy the screen first, so the copy can't contain the panel itself
        takeSnapshot {
            panel.alpha = 0f; bg.alpha = 0f; panel.translationY = -480f
            beginReveal()
            settle(true)
        }
    }

    fun close() {
        if (!open && panel.visibility != VISIBLE) return
        settle(false)
    }

    /** The panel dragged up by [dy] (negative), or released. */
    private fun dragClose(dy: Float, done: Boolean, fling: Boolean) {
        if (!done) {
            panel.animate().cancel(); bg.animate().cancel()
            panel.translationY = dy.coerceAtMost(0f)
            bg.alpha = (1f + dy / 400f).coerceIn(0f, 1f)
            return
        }
        settle(!(dy < -90f || fling))
    }

    private fun finishClose() {
        bg.visibility = GONE; panel.visibility = GONE
        ui.ccOpen.value = false
        panel.onClosed()
        bg.setBitmap(null)
    }

    /** The blurred screen behind the panel, drawn scaled up 4× (it's a blur, so 1/4 size is enough). */
    private class BackgroundView(context: Context) : View(context) {
        private var bmp: Bitmap? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val m = Matrix()
        fun setBitmap(b: Bitmap?) { val old = bmp; bmp = b; if (old !== b) old?.recycle(); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val b = bmp
            if (b == null) { canvas.drawColor(Color.argb(140, 0, 0, 0)); return }
            m.setScale(width.toFloat() / b.width, height.toFloat() / b.height)
            canvas.drawBitmap(b, m, p)
        }
    }

    companion object {
        /** Swipes starting this close to the top edge open Control Center. */
        const val EDGE_PX = 24f
        /** cubic-bezier(0.3, 1.15, 0.5, 1) */
        val PANEL_SPRING = bezier(0.3f, 1.15f, 0.5f, 1f)
        @Suppress("unused") fun settingsIntent() = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
