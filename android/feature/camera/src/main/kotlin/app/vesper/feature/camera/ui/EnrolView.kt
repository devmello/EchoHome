package app.vesper.feature.camera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import app.vesper.core.design.VesperColors
import app.vesper.core.design.SpecText
import app.vesper.core.design.withAlpha
import app.vesper.core.model.VesperScreen
import app.vesper.core.model.VesperServices
import app.vesper.core.model.Profile
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.camera.CameraPipeline
import app.vesper.feature.camera.EnrolSample
import app.vesper.feature.camera.OffReason
import app.vesper.feature.camera.face.FaceGeometry
import app.vesper.feature.camera.face.FaceTemplates
import app.vesper.feature.camera.face.QualityIssue
import app.vesper.feature.camera.profiles.FaceProfileManager

/**
 * "profiles-enrol" (`--es profile <id>`): explicit consent, then 5 looks (straight, left, right,
 * up, down), then the encrypted templates are saved (NOTES §6.6: "Enrolment happens in settings,
 * with 5 angles and explicit consent per person"). No preview image is shown or kept: the guide
 * draws only where the detector sees a face.
 */
@SuppressLint("ViewConstructor")
class EnrolView(
    context: Context,
    private val services: VesperServices,
    private val manager: FaceProfileManager,
    private val pipeline: CameraPipeline,
) : View(context), VesperScreen {

    private enum class Step { CONSENT, CAPTURE, FAILED, DONE }

    private val p = Painter()
    private val head = SpecText(15f, 700)
    private val title = SpecText(30f, 800, -0.025f)
    private val body = SpecText(17f, 600)
    private val btn = SpecText(15f, 700)
    private val foot = SpecText(12f, 600)
    private val main = Handler(Looper.getMainLooper())

    private var person: Profile? = null
    private var step = Step.CONSENT
    private var look = 0
    private var stepStart = 0L
    private var good = mutableListOf<Pair<Float, FloatArray>>()
    private val vectors = mutableListOf<FloatArray>()
    /** The embedder that made [vectors] (if the model falls back to LBP mid-way, start over). */
    private var vectorsId = ""
    private var last: EnrolSample? = null
    private var flashUntil = 0L

    private var returnTo: String? = null

    private val primary = RectF()
    private val secondary = RectF()

    override fun onScreenShown(args: Bundle?) {
        val id = args?.getString("profile")
        if (id == null && person != null) { if (step == Step.CAPTURE) startCapture(keepProgress = true); return }
        // `--es return <screen>`: where Done / Cancel go (the first-run setup), else Profiles.
        returnTo = args?.getString("return")
        person = manager.profiles.value.firstOrNull { it.id == id && it.id != Profile.SHARED_ID }
            ?: manager.profiles.value.firstOrNull { it.id != Profile.SHARED_ID }
        step = Step.CONSENT
        look = 0; vectors.clear(); good.clear(); last = null
        invalidate()
    }

    override fun onScreenHidden() {
        pipeline.endEnrol()
        main.removeCallbacksAndMessages(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeWhileAttached(pipeline.ui) { invalidate() }
    }

    private fun startCapture(keepProgress: Boolean = false) {
        step = Step.CAPTURE
        if (!keepProgress) { look = 0; vectors.clear() }
        good.clear()
        stepStart = SystemClock.elapsedRealtime()
        pipeline.beginEnrol(::onSample)
        main.removeCallbacks(timeout); main.postDelayed(timeout, STEP_TIMEOUT_MS)
        invalidate()
    }

    private val timeout = Runnable {
        if (step == Step.CAPTURE) { pipeline.endEnrol(); step = Step.FAILED; invalidate() }
    }

    private fun onSample(s: EnrolSample) {
        if (step != Step.CAPTURE) return
        last = s
        val now = SystemClock.elapsedRealtime()
        if (now < flashUntil) { invalidate(); return }
        if (s.embedding != null && vectorsId != s.embedderId) {
            if (vectors.isNotEmpty() || good.isNotEmpty()) { look = 0; vectors.clear(); good.clear(); stepStart = now }
            vectorsId = s.embedderId
        }
        if (now - stepStart >= SETTLE_MS && s.issue == QualityIssue.NONE && s.embedding != null) good += s.sharpness to s.embedding
        if (good.size >= SAMPLES_PER_LOOK) {
            vectors += good.maxBy { it.first }.second
            good.clear()
            look++
            flashUntil = now + 600
            if (look >= LOOKS.size) finish()
            else { stepStart = now + 600; main.removeCallbacks(timeout); main.postDelayed(timeout, STEP_TIMEOUT_MS) }
        }
        invalidate()
    }

    private fun finish() {
        pipeline.endEnrol()
        main.removeCallbacks(timeout)
        val who = person ?: return
        try {
            val t = FaceTemplates(who.id, vectorsId, vectors.toList())
            // Calibration aid (numbers only): how alike the person's own shots are.
            Log.i(CameraPipeline.TAG, "enrolled ${who.id} with $vectorsId: weakest pair ${"%.3f".format(t.weakestPair())}, " +
                "threshold ${"%.3f".format(t.threshold(pipeline.embedder.policy))}")
            manager.saveTemplates(t)
            step = Step.DONE
        } catch (e: Exception) {
            Log.w(CameraPipeline.TAG, "saving templates failed", e)
            step = Step.FAILED
        }
        vectors.clear()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val who = person ?: run { body.draw(c, "Add a person first.", 40f, 80f, Color.WHITE); return }
        p.avatar(c, 40f + 18f, 30f + 18f, 18f, who.color, who.name, ring = false)
        head.draw(c, "Face enrolment · ${who.name}", 40f + 36f + 12f, head.baselineIn(30f, 36f), withAlpha(Color.WHITE, 0.8f))
        when (step) {
            Step.CONSENT -> drawConsent(c, who)
            Step.CAPTURE -> drawCapture(c, who)
            Step.FAILED -> drawMessage(c, "Couldn’t get a clear look", "Face the screen from about an arm’s length, in good light, and try again.", "Try again", "Cancel")
            Step.DONE -> drawMessage(c, "${who.name}’s face is enrolled", "When the camera sees ${who.name}, this Echo switches to their clock, colour, alarm and calendar. “Not me” on the greeting switches back.", "Done", null)
        }
        foot.draw(c, "On-device recognition · no images saved", 22f, foot.baselineIn(480f - 14f - foot.normalLineHeight), withAlpha(Color.WHITE, 0.5f))
    }

    private fun drawConsent(c: Canvas, who: Profile) {
        p.avatar(c, 140f, 240f, 64f, who.color, who.name, ring = true)
        val x = 260f
        var y = 128f
        title.draw(c, "Enrol ${who.name}’s face?", x, title.baselineIn(y), Color.WHITE)
        y += title.normalLineHeight + 12f
        val text = "The camera takes 5 quick looks at ${who.name}’s face. Only an encrypted face template is kept, on this Echo. " +
            "No photos are saved and nothing leaves the device. You can forget it any time in Profiles."
        for (l in wrap(body, text, 960f - x - 60f)) { body.draw(c, l, x, body.baselineIn(y), withAlpha(Color.WHITE, 0.78f)); y += body.normalLineHeight + 4f }
        y += 8f
        val ask = "${who.name} should be here and agree to this."
        body.draw(c, ask, x, body.baselineIn(y), Color.WHITE)
        y += body.normalLineHeight + 24f
        p.primary(c, btn, "${who.name} agrees", x, y, 48f, 22f, primary)
        p.button(c, btn, "Cancel", primary.right + 12f, y, 48f, 22f, secondary)
    }

    private fun drawCapture(c: Canvas, who: Profile) {
        // Guide: a 4:3 box standing for the camera's view, mirrored like a mirror.
        val gl = 60f; val gt = 120f; val gw = 300f; val gh = 225f
        p.roundRect(c, gl, gt, gl + gw, gt + gh, 28f, withAlpha(Color.WHITE, 0.06f))
        p.stroke.color = withAlpha(Color.WHITE, 0.35f); p.stroke.strokeWidth = 2f
        p.stroke.pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        c.drawOval(RectF(gl + gw / 2f - 58f, gt + gh / 2f - 74f, gl + gw / 2f + 58f, gt + gh / 2f + 74f), p.stroke)
        p.stroke.pathEffect = null
        val s = last
        val box = s?.box
        if (s != null && box != null) {
            val sx = gw / s.frameW; val sy = gh / s.frameH
            val cx = gl + gw - box.cx * sx
            val cy = gt + FaceGeometry.centerY(box) * sy
            val r = FaceGeometry.side(box) / 2f * sx
            p.stroke.color = if (s.issue == QualityIssue.NONE) VesperColors.GREEN else withAlpha(Color.WHITE, 0.7f)
            p.stroke.strokeWidth = 3f
            c.drawCircle(cx, cy, r, p.stroke)
        }

        val x = 420f
        var y = 132f
        val now = SystemClock.elapsedRealtime()
        val flashing = now < flashUntil
        val instruction = if (flashing) "Got it" else LOOKS[look.coerceAtMost(LOOKS.size - 1)]
        title.draw(c, instruction, x, title.baselineIn(y), if (flashing) VesperColors.GREEN else Color.WHITE)
        y += title.normalLineHeight + 10f
        val hint = when {
            services.system.state.value.cameraBlocked -> "The camera is off. Open the shutter and turn the camera on."
            !pipeline.hasPermission() -> "Camera permission is needed (Profiles → Allow camera)."
            pipeline.ui.value.off == OffReason.ERROR -> "The camera is busy. Trying again…"
            s == null -> "Starting the camera…"
            box == null -> "Looking for ${who.name}…"
            else -> when (s.issue) {
                QualityIssue.NONE -> "Hold still"
                QualityIssue.TOO_SMALL -> "Come a little closer"
                QualityIssue.CLIPPED -> "Move into the middle"
                QualityIssue.TOO_DARK -> "It’s too dark here: turn on a light"
                QualityIssue.TOO_BRIGHT -> "Too bright behind you"
                QualityIssue.BLURRY -> "Hold still"
                QualityIssue.LOW_CONFIDENCE, QualityIssue.NOT_FRONTAL -> "Face the screen"
            }
        }
        body.draw(c, hint, x, body.baselineIn(y), withAlpha(Color.WHITE, 0.72f))
        y += body.normalLineHeight + 26f
        // progress: 5 dots
        for (i in LOOKS.indices) {
            p.fill.color = when { i < look -> VesperColors.GREEN; i == look -> Color.WHITE; else -> withAlpha(Color.WHITE, 0.25f) }
            c.drawCircle(x + 7f + i * 26f, y + 7f, 7f, p.fill)
        }
        y += 14f + 30f
        primary.setEmpty()
        p.button(c, btn, "Cancel", x, y, 44f, 20f, secondary)
        if (flashing) postInvalidateDelayed(flashUntil - now + 16)
    }

    private fun drawMessage(c: Canvas, t: String, b: String, primaryLabel: String, secondaryLabel: String?) {
        val x = 60f
        var y = 150f
        title.draw(c, t, x, title.baselineIn(y), Color.WHITE)
        y += title.normalLineHeight + 12f
        for (l in wrap(body, b, 840f)) { body.draw(c, l, x, body.baselineIn(y), withAlpha(Color.WHITE, 0.78f)); y += body.normalLineHeight + 4f }
        y += 24f
        p.primary(c, btn, primaryLabel, x, y, 48f, 22f, primary)
        if (secondaryLabel != null) p.button(c, btn, secondaryLabel, primary.right + 12f, y, 48f, 22f, secondary) else secondary.setEmpty()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val onPrimary = !primary.isEmpty && primary.contains(e.x, e.y)
        val onSecondary = !secondary.isEmpty && secondary.contains(e.x, e.y)
        when (step) {
            Step.CONSENT -> if (onPrimary) startCapture() else if (onSecondary) close()
            Step.CAPTURE -> if (onSecondary) { pipeline.endEnrol(); vectors.clear(); close() }
            Step.FAILED -> if (onPrimary) startCapture() else if (onSecondary) close()
            Step.DONE -> if (onPrimary) close()
        }
        return true
    }

    private fun close() {
        main.removeCallbacksAndMessages(null)
        services.navigator.show(returnTo ?: "profiles")
    }

    companion object {
        val LOOKS = listOf("Look straight at the screen", "Turn a little to your left", "Turn a little to your right", "Tilt your chin up a little", "Tilt your chin down a little")
        const val SETTLE_MS = 800L
        const val SAMPLES_PER_LOOK = 4
        const val STEP_TIMEOUT_MS = 20_000L
    }
}
