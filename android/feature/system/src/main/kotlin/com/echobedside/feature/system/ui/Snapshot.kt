package com.echobedside.feature.system.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import com.echobedside.core.design.Backdrop
import com.echobedside.core.design.PreBlur
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pre-blurred backdrops for glass over whatever is on screen (NOTES §5.2/§5.4: never blur live).
 *
 *  - [region]: software-draws the views *below* an overlay (its earlier siblings) for a small
 *    region at 1/4 scale, then blurs. ~1–3 ms. Used by the island and the HUD capsule, re-taken
 *    only when the screen underneath changes.
 *  - [window]: PixelCopy of the whole window at 1/4 scale (async, GPU readback), for Control
 *    Center and the expanded banner, which cover the screen.
 */
object Snapshot {
    private const val TAG = "EchoSystem"
    const val SCALE = 4

    /**
     * Blurred backdrop of window region [r] (window px) under [self]. [blurPx]/[saturate]/[brightness]
     * are the CSS backdrop-filter values. Returns null if drawing fails (e.g. a hardware-only view).
     */
    fun region(self: View, r: Rect, blurPx: Float, saturate: Float, brightness: Float): Backdrop? {
        val parent = self.parent as? ViewGroup ?: return null
        val t0 = System.nanoTime()
        val idx = parent.indexOfChild(self)
        val w = max(1, r.width() / SCALE)
        val h = max(1, r.height() / SCALE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.BLACK)
            c.scale(1f / SCALE, 1f / SCALE)
            c.translate(-r.left.toFloat(), -r.top.toFloat())
            for (i in 0 until idx) {
                val v = parent.getChildAt(i)
                if (v.visibility != View.VISIBLE || v.alpha <= 0f) continue
                c.save()
                c.translate(v.left + v.translationX, v.top + v.translationY)
                v.draw(c)
                c.restore()
            }
        } catch (e: Exception) {
            Log.w(TAG, "region snapshot failed", e)
            bmp.recycle()
            return null
        }
        val t1 = System.nanoTime()
        val out = PreBlur.blur(bmp, blurPx / SCALE, downscale = 1, saturate = saturate, brightness = brightness)
        bmp.recycle()
        val drawMs = (t1 - t0) / 1_000_000; val blurMs = (System.nanoTime() - t1) / 1_000_000
        if (drawMs + blurMs >= 4) Log.d("EchoPerf", "region ${r.width()}x${r.height()}: draw $drawMs ms, blur $blurMs ms")
        return Backdrop(out, r.left.toFloat(), r.top.toFloat(), r.width().toFloat(), r.height().toFloat())
    }

    /** Whole-window copy at 1/[SCALE], delivered on the main thread (null on failure). */
    fun window(activity: Activity, done: (Bitmap?) -> Unit) {
        val dec = activity.window.decorView
        val w = max(1, dec.width / SCALE)
        val h = max(1, dec.height / SCALE)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, bmp, { res ->
                if (res == PixelCopy.SUCCESS) done(bmp) else { Log.w(TAG, "PixelCopy failed: $res"); bmp.recycle(); done(null) }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy", e)
            bmp.recycle(); done(null)
        }
    }

    /** One low-priority thread for the blurs, so a full-window snapshot never costs a UI frame. */
    private val worker: Handler by lazy {
        val t = android.os.HandlerThread("echo-blur", android.os.Process.THREAD_PRIORITY_BACKGROUND)
        t.start(); Handler(t.looper)
    }
    private val main = Handler(Looper.getMainLooper())

    /** Runs [work] on the blur thread, then [done] with its result on the main thread. */
    fun <T> offMain(work: () -> T, done: (T?) -> Unit) {
        worker.post {
            val r = try { work() } catch (e: Exception) { Log.w(TAG, "blur", e); null }
            main.post { done(r) }
        }
    }

    fun blurSmall(src: Bitmap, blurPx: Float, saturate: Float, brightness: Float): Bitmap =
        PreBlur.blur(src, blurPx / SCALE, downscale = 1, saturate = saturate, brightness = brightness)

    @Suppress("unused")
    fun px(v: Float) = v.roundToInt()
}
