package app.vesper

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import app.vesper.core.design.Fonts
import app.vesper.core.design.Night
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha

/**
 * A small "EchoHome" pill at the bottom of the screen while another app (Android Settings, the
 * Wi-Fi panel) is in front and the Echo has no navigation bar, i.e. with lean SystemUI
 * (`device/overlays/systemui-lean`, `scripts/echo-systemui-lean.sh`). Without it there'd be no
 * Back/Home button to get out of Android Settings. A tap brings EchoHome back.
 *
 * Needs the SYSTEM_ALERT_WINDOW app-op (`cmd appops set app.vesper SYSTEM_ALERT_WINDOW allow`,
 * done by `scripts/echo-system-install.sh` and `echo-systemui-lean.sh on`); without it nothing shows.
 */
internal class HomeButton(private val context: Context, private val night: () -> Boolean) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    /** Called when EchoHome stops: shows the pill if another app came to the front. */
    fun onEchoHomeStopped(activityWindow: WindowManager) {
        if (view != null) return
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!interactive) return // the screen went off, nothing else is in front
        if (hasNavigationBar(activityWindow)) return
        if (!Settings.canDrawOverlays(context)) { Log.i(TAG, "no navigation bar and no overlay permission: no Home pill"); return }
        val v = Pill(context, night())
        val lp = WindowManager.LayoutParams(W, H, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = MARGIN
            title = "EchoHome:home"
        }
        try { wm.addView(v, lp); view = v } catch (e: Exception) { Log.w(TAG, "Home pill: $e") }
    }

    /** Called when EchoHome is in front again. */
    fun hide() {
        val v = view ?: return
        view = null
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    private fun hasNavigationBar(activityWindow: WindowManager): Boolean = try {
        activityWindow.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).let { it.bottom + it.left + it.right > 0 }
    } catch (_: Exception) { true }

    private inner class Pill(context: Context, private val night: Boolean) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0xFF1C1C1E.toInt(), 0.92f) }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = withAlpha(0xFFFFFFFF.toInt(), 0.22f)
        }
        private val ink = Night.map(0xFFFFFFFF.toInt(), night)
        private val text = run { Fonts.init(context); SpecText(19f, 600) }.also { it.paint.color = ink }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = ink
        }
        private val rect = RectF()
        private val chevron = Path()
        private var pressed = false

        override fun onDraw(c: Canvas) {
            rect.set(0.5f, 0.5f, width - 0.5f, height - 0.5f)
            val r = height / 2f
            fill.alpha = if (pressed) 255 else (0.92f * 255).toInt()
            c.drawRoundRect(rect, r, r, fill)
            c.drawRoundRect(rect, r, r, border)
            val label = "EchoHome"
            val tw = text.width(label)
            val gw = 10f
            val x0 = (width - (gw + 10f + tw)) / 2f
            val cy = height / 2f
            chevron.reset()
            chevron.moveTo(x0 + gw, cy - 8f); chevron.lineTo(x0 + 2f, cy); chevron.lineTo(x0 + gw, cy + 8f)
            c.drawPath(chevron, glyph)
            c.drawText(label, x0 + gw + 10f, cy + (text.ascent - text.descent) / 2f, text.paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
                MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
                MotionEvent.ACTION_UP -> {
                    pressed = false; invalidate()
                    context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    hide()
                }
            }
            return true
        }
    }

    private companion object {
        const val TAG = "VesperSystem"
        const val W = 168
        const val H = 48
        const val MARGIN = 14
    }
}
