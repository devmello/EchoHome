package app.vesper.feature.camera.capture

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import app.vesper.core.design.SpecText
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Debug screen "camera-probe" (the capture gate, NOTES §6.5 / §7): opens the front camera with
 * Camera2, then Camera1, for [RUN_MS] each, and reports frames, fps, preview size, luma stats and
 * our process CPU. Nothing is stored; results go to logcat (tag VesperCamProbe) and the screen.
 *
 * `adb shell am start -n app.vesper/.MainActivity --es screen camera-probe
 *    [--es api camera2|camera1] [--es interval MS] [--es flash true]`
 *
 * `flash`: light test for a dark room. The screen goes white at full window brightness for the
 * middle third of the run. A live sensor sees the reflected light (mean luma rises); a dead
 * pipeline doesn't.
 */
class CaptureProbeView(context: Context, private val services: VesperServices) : View(context), VesperScreen {
    private val main = Handler(Looper.getMainLooper())
    private val lines = mutableListOf("Camera capture probe")
    private val text = SpecText(18f, 500)
    private var source: FrameSource? = null
    private var queue = mutableListOf<String>()
    private var flash = false
    private var white = false

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val lux = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { report("light sensor: ${e.values[0]} lux") }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onScreenShown(args: Bundle?) {
        // The shell also calls this from onResume with null args: keep a run that's going.
        if (args == null && source != null) return
        main.removeCallbacksAndMessages(null); source?.stop(); source = null
        app.vesper.feature.camera.CameraFeature.pipeline.externalUser = true
        lines.clear(); lines += "Camera capture probe"
        val api = args?.getString("api")
        queue = if (api != null) mutableListOf(api) else mutableListOf("camera2", "camera1")
        val interval = args?.getString("interval")?.toLongOrNull() ?: 0L
        flash = args?.getString("flash") == "true"
        next(interval)
    }

    override fun onScreenHidden() {
        main.removeCallbacksAndMessages(null)
        if (white) setWhite(false)
        source?.stop(); source = null
        setInUse(false)
        app.vesper.feature.camera.CameraFeature.pipeline.externalUser = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(lux, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onDetachedFromWindow() { sensors?.unregisterListener(lux); super.onDetachedFromWindow() }

    private fun setWhite(on: Boolean) {
        white = on
        (context as? Activity)?.window?.let { w ->
            w.attributes = w.attributes.apply { screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
        invalidate()
    }

    private fun next(interval: Long) {
        val api = queue.removeFirstOrNull() ?: run { report("done"); setInUse(false); return }
        if (services.system.state.value.cameraBlocked) { report("camera blocked (button/toggle/shutter): skipped"); return }
        val src: FrameSource = if (api == "camera1") Camera1Source(minIntervalMs = interval) else Camera2Source(context, minIntervalMs = interval)
        source = src
        var delivered = 0
        var lumaSum = 0L
        var lumaMin = 255; var lumaMax = 0
        var diffSum = 0.0
        val prev = IntArray(16 * 12)
        var stdSum = 0.0
        var firstAt = 0L
        val phaseSum = LongArray(3); val phaseN = IntArray(3)
        val t0 = SystemClock.elapsedRealtime()
        val cpu0 = Process.getElapsedCpuTime()
        var error: String? = null
        setInUse(true)
        report("$api: opening…")
        src.start({ f ->
            if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
            delivered++
            // Mean / spread / frame-to-frame change of a 16×12 grid: tells a live sensor (noise, motion)
            // from a dead pipeline (constant frames) and a closed shutter / dark room (near-black).
            var s = 0; var n = 0; var d = 0; var sq = 0L
            for (gy in 0 until 12) for (gx in 0 until 16) {
                val v = f.at(gx * f.width / 16, gy * f.height / 12)
                s += v; sq += (v * v).toLong()
                if (v < lumaMin) lumaMin = v
                if (v > lumaMax) lumaMax = v
                d += abs(v - prev[n]); prev[n] = v
                n++
            }
            lumaSum += s / n
            if (delivered > 1) diffSum += d.toDouble() / n
            val mean = s.toDouble() / n
            stdSum += sqrt((sq.toDouble() / n - mean * mean).coerceAtLeast(0.0))
            val ph = ((SystemClock.elapsedRealtime() - t0) * 3 / RUN_MS).toInt().coerceIn(0, 2)
            phaseSum[ph] += s.toLong(); phaseN[ph] += n
        }, { e -> error = e })
        if (flash) {
            main.postDelayed({ setWhite(true) }, RUN_MS / 3)
            main.postDelayed({ setWhite(false) }, RUN_MS * 2 / 3)
        }
        main.postDelayed({
            val dt = SystemClock.elapsedRealtime() - t0
            src.stop()
            val cpu = Process.getElapsedCpuTime() - cpu0
            val extra = when (src) {
                is Camera2Source -> "level=${src.hardwareLevel} sizes=${src.offeredSizes} fpsRanges=${src.offeredFps} using fps=${src.fpsRange}"
                is Camera1Source -> "sizes=${src.offeredSizes} fpsRanges=${src.offeredFps} using fps=${src.fpsRange}"
                else -> ""
            }
            report("$api: ${if (delivered > 0) "OK" else "FAIL"} size=${src.size} seen=${src.framesSeen} delivered=$delivered " +
                "fps=${"%.1f".format(src.framesSeen * 1000f / dt)} firstFrameMs=${if (firstAt > 0) firstAt - t0 else -1} " +
                "meanLuma=${if (delivered > 0) lumaSum / delivered else -1} min=$lumaMin max=$lumaMax " +
                "std=${"%.1f".format(if (delivered > 0) stdSum / delivered else 0.0)} " +
                "meanDiff=${"%.2f".format(if (delivered > 2) diffSum / (delivered - 1) else 0.0)} " +
                "appCpuMs=$cpu (${"%.1f".format(cpu * 100f / dt)}% of a core)" + (error?.let { " error=$it" } ?: ""))
            report("$api: $extra")
            if (flash) report("$api: flash test mean luma black/white/black = " +
                (0..2).joinToString("/") { if (phaseN[it] > 0) "%.1f".format(phaseSum[it].toFloat() / phaseN[it]) else "-" })
            source = null
            setInUse(false)
            main.postDelayed({ next(interval) }, 1000)
        }, RUN_MS)
    }

    private fun setInUse(on: Boolean) = services.system.update { it.copy(cameraInUse = on) }

    private fun report(s: String) {
        Log.i(TAG, s)
        main.post { lines += s; while (lines.size > 14) lines.removeAt(1); invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (white) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        if (white) return
        var y = 40f
        for (l in lines) l.chunked(95).forEach { text.draw(canvas, it, 20f, y, 0xFFFFFFFF.toInt()); y += 26f }
    }

    companion object {
        const val TAG = "VesperCamProbe"
        const val RUN_MS = 12000L
    }
}
