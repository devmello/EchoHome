package app.vesper.feature.camera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.SpecText
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.feature.camera.CameraFeature
import app.vesper.feature.camera.CameraPhase
import app.vesper.feature.camera.CameraPipeline

/**
 * "camera-bench" (debug): holds one pipeline stage continuously so its cost can be measured with
 * scripts/echo-perf.sh before the stage is turned on by default (NOTES §6.6).
 * `--es screen camera-bench --es stage presence|presence-duty|detect|detect-compare|recognize|selftest`
 * Logs per-stage timings every 10 s (tag VesperCamera).
 */
@SuppressLint("ViewConstructor")
class CameraBenchView(context: Context, private val services: VesperServices, private val pipeline: CameraPipeline) : View(context), VesperScreen {
    private val main = Handler(Looper.getMainLooper())
    private val text = SpecText(18f, 500)
    private var stage = "presence"
    private var cpu0 = 0L
    private var t0 = 0L
    private val tick = object : Runnable {
        override fun run() {
            val dt = SystemClock.elapsedRealtime() - t0
            val cpu = Process.getElapsedCpuTime() - cpu0
            Log.i(CameraPipeline.TAG, "bench $stage: ${pipeline.stats} appCpu=${"%.1f".format(cpu * 100f / dt.coerceAtLeast(1))}% ui=${pipeline.ui.value}")
            invalidate()
            main.postDelayed(this, 10_000)
        }
    }

    override fun onScreenShown(args: Bundle?) {
        if (args == null && (pipeline.benchStage != null || stage == "selftest")) return
        stage = args?.getString("stage") ?: "presence"
        pipeline.stats = CameraPipeline.Stats()
        if (stage == "selftest") { selfTest(args?.getString("model"), args?.getString("threads")?.toIntOrNull() ?: 2, args?.getString("xnnpack") != "false"); return }
        pipeline.benchDuty = stage == "presence-duty"
        pipeline.benchCompare = stage == "detect-compare"
        pipeline.benchStage = when (stage) {
            "detect", "detect-compare" -> CameraPhase.DETECTING
            "recognize" -> CameraPhase.RECOGNIZED
            else -> CameraPhase.PRESENCE
        }
        cpu0 = Process.getElapsedCpuTime(); t0 = SystemClock.elapsedRealtime()
        main.removeCallbacks(tick); main.postDelayed(tick, 10_000)
    }

    override fun onScreenHidden() {
        main.removeCallbacks(tick)
        pipeline.benchDuty = false; pipeline.benchCompare = false
        if (pipeline.benchStage != null) pipeline.benchStage = null
        if (selfTesting) { selfTesting = false; pipeline.externalUser = false }
    }

    @Volatile private var selfLines: List<String> = emptyList()
    private var selfTesting = false

    /** Camera off; models on the drawn face, on a background thread. Logs "selftest: …" lines. */
    private fun selfTest(model: String?, threads: Int, xnnpack: Boolean) {
        if (selfTesting) return
        selfTesting = true
        pipeline.externalUser = true
        selfLines = listOf("running…")
        invalidate()
        Thread({
            val lines = try {
                val t = ModelSelfTest(context)
                if (model != null) t.variant(model, threads, xnnpack) else t.run()
            } catch (t: Throwable) { listOf("failed: $t") }
            lines.forEach { Log.i(CameraPipeline.TAG, "selftest: $it") }
            Log.i(CameraPipeline.TAG, "selftest: done")
            selfLines = lines
            main.post { if (selfTesting) { selfTesting = false; pipeline.externalUser = false }; invalidate() }
        }, "echo-selftest").start()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        var y = 80f
        val lines = if (stage == "selftest") listOf("Camera self-test") + selfLines
            else listOf("Camera bench: $stage", pipeline.stats.toString(), pipeline.ui.value.toString())
        for (l in lines)
            l.chunked(90).forEach { text.draw(canvas, it, 20f, y, 0xFFFFFFFF.toInt()); y += 28f }
    }
}

/**
 * "profiles-demo" (debug / design review): a see-through screen that forces the Profiles overlay
 * into one step of Profiles.dc.html over the StandBy face, without a camera.
 * `--es screen profiles-demo --es step empty|detecting|recognized|personalized|cycle`
 * ("cycle" runs the design's 13 s loop: empty 3 s, detecting 1.6 s, recognized 4 s, personalized.)
 */
@SuppressLint("ViewConstructor")
class ProfilesDemoView(context: Context, private val services: VesperServices) : View(context), VesperScreen {
    private val main = Handler(Looper.getMainLooper())
    private var cycleStart = 0L
    private val cycle = object : Runnable {
        override fun run() {
            val el = (SystemClock.elapsedRealtime() - cycleStart) % 13_000
            apply(when { el < 3000 -> "empty"; el < 4600 -> "detecting"; el < 8600 -> "recognized"; else -> "personalized" })
            main.postDelayed(this, 100)
        }
    }
    private var applied: String? = null

    override fun onScreenShown(args: Bundle?) {
        val step = args?.getString("step") ?: if (applied != null) return else "cycle"
        main.removeCallbacks(cycle)
        applied = null
        if (step == "cycle") { cycleStart = SystemClock.elapsedRealtime(); main.post(cycle) } else apply(step)
    }

    private fun apply(step: String) {
        if (step == applied) return
        applied = step
        val maya = services.profiles.profiles.value.firstOrNull { it.id == "maya" }
            ?: services.profiles.profiles.value.firstOrNull { it.id != "shared" }
        val s = when (step) {
            "empty" -> DemoStep.EMPTY
            "detecting" -> DemoStep.DETECTING
            "recognized" -> DemoStep.RECOGNIZED
            else -> DemoStep.PERSONALIZED
        }
        CameraFeature.showDemo(s, maya)
        // "then her own face and colour": the clock follows profiles.active (feature:clock).
        val mine = s == DemoStep.RECOGNIZED || s == DemoStep.PERSONALIZED
        if (maya != null) services.profiles.switchTo(if (mine) maya.id else "shared", app.vesper.core.model.SwitchReason.MANUAL)
    }

    override fun onScreenHidden() {
        main.removeCallbacks(cycle)
        if (applied != null) services.profiles.switchTo("shared", app.vesper.core.model.SwitchReason.MANUAL)
        applied = null
        CameraFeature.showDemo(null, null)
    }

    // See-through: never draws, never takes touches (Back closes it).
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}
