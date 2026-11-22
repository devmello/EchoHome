package com.echobedside.feature.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.Presence
import com.echobedside.core.model.Profile
import com.echobedside.core.model.SwitchReason
import com.echobedside.feature.camera.capture.Camera1Source
import com.echobedside.feature.camera.capture.FrameSource
import com.echobedside.feature.camera.capture.YPlane
import com.echobedside.feature.camera.face.ConsecutiveMatcher
import com.echobedside.feature.camera.face.FaceBox
import com.echobedside.feature.camera.face.FaceEmbedder
import com.echobedside.feature.camera.face.FaceMatcher
import com.echobedside.feature.camera.face.FaceTemplates
import com.echobedside.feature.camera.face.GreyImage
import com.echobedside.feature.camera.face.LbpEmbedder
import com.echobedside.feature.camera.face.LegacyFaceFinder
import com.echobedside.feature.camera.face.QualityFilter
import com.echobedside.feature.camera.face.QualityIssue
import com.echobedside.feature.camera.face.cropFace
import com.echobedside.feature.camera.presence.MotionDetector
import com.echobedside.feature.camera.presence.PresenceTracker
import com.echobedside.feature.camera.profiles.FaceProfileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the camera is doing, for the indicator and the Profiles overlay (Profiles.dc.html). */
enum class CameraPhase { OFF, PRESENCE, DETECTING, RECOGNIZED, ENROLLING }

enum class OffReason { NONE, HIDDEN, BLOCKED, DARK, DISABLED, NO_PERMISSION, ERROR, BUSY }

data class CameraUi(
    val phase: CameraPhase = CameraPhase.OFF,
    val off: OffReason = OffReason.HIDDEN,
    val presence: Presence = Presence.UNKNOWN,
    /** Set while the "Hi <name>" greeting is up. */
    val greeting: Profile? = null,
    /** Presence sampling rate, for the "Presence · 2 fps" label. */
    val presenceFps: Int = 2,
)

/** One enrolment frame: where the face is (for the guide) and, if it passed quality, its embedding. */
class EnrolSample(val box: FaceBox?, val frameW: Int, val frameH: Int, val issue: QualityIssue, val embedding: FloatArray?, val sharpness: Float)

/**
 * Owns the camera and runs NOTES §6.6 stage by stage. Main-thread API; frames are processed on
 * the camera thread.
 *
 *  1. Gate: runs only while our window is visible, the camera isn't blocked (button, toggle,
 *     shutter), we have the permission, and frames aren't near-black (closed shutter / dark
 *     room → release the camera and retry with back-off, or when the light rises / on touch).
 *  2. Presence at [PRESENCE_WIDTH] px, Y plane only, 80×60 differencing at 2 fps.
 *  3. Detection burst (~5 fps for ~2 s) after someone arrives.
 *  4. Quality filter. 5. Recognition (≤3 tries, 2 consecutive matches). 6. Greeting + switch.
 *
 * Each stage can be turned off in settings (`camera.stage.presence|detect|recognize`); they were
 * measured on the Echo before being turned on by default (docs/status/camera.md).
 */
class CameraPipeline(
    private val context: Context,
    private val services: EchoServices,
    private val profiles: FaceProfileManager,
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = services.settings
    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui.asStateFlow()

    val embedder: FaceEmbedder = LbpEmbedder()
    @Volatile var stats = Stats()

    private var source: FrameSource? = null
    private var sourceWidth = 0
    private var visible = false
    private var started = false

    // camera-thread state
    private val motion = MotionDetector()
    private val tracker = PresenceTracker()
    private val finder = LegacyFaceFinder()
    private val quality = QualityFilter()
    private val enrolQuality = QualityFilter(maxAsymmetry = 0.45f)
    private val matcher = FaceMatcher()
    private val decision = ConsecutiveMatcher()
    private val crop = GreyImage(112, 112)
    private var darkStreak = 0
    @Volatile private var touchAt = 0L
    @Volatile private var burstUntil = 0L
    @Volatile private var burstTemplates: List<FaceTemplates> = emptyList()
    @Volatile private var burstExcluded: Set<String> = emptySet()
    @Volatile private var enrolListener: ((EnrolSample) -> Unit)? = null
    /** Debug: hold a stage continuously for measurements ("camera-bench" screen). */
    @Volatile var benchStage: CameraPhase? = null
        set(v) { field = v; main.post { reconfigure() } }
    /** Debug: presence with the duty-cycled preview (bench stage "presence-duty"). */
    @Volatile var benchDuty = false

    // arrival bookkeeping (main thread)
    private var lastKnown = Presence.UNKNOWN
    private var burstsThisArrival = 0
    private var lastBurstAt = 0L
    private var arrivalDone = false

    // dark back-off (main thread)
    private var darkRetryMs = DARK_RETRY_MIN_MS
    private var darkUntil = 0L
    private var luxAtDark = -1f
    private var errorUntil = 0L
    // Another user of the camera in this app (the capture probe): stay off.
    var externalUser = false
        set(v) { field = v; evaluate() }
    private var lux = -1f
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val luxListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            lux = e.values[0]
            // The lights came on: try again now instead of waiting out the back-off.
            if (_ui.value.off == OffReason.DARK && lux >= 3f && lux > luxAtDark * 2f + 1f) { darkUntil = 0L; evaluate() }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start() {
        if (started) return
        started = true
        services.scope.launch {
            services.system.state.map { it.cameraBlocked }.distinctUntilChanged().collect { evaluate() }
        }
        services.scope.launch { profiles.autoByFace.collect { evaluate() } }
        services.scope.launch { settings.changes.collect { k -> if (k.startsWith("camera.stage.")) evaluate() } }
    }

    /** Our window became visible / invisible (screen off, another app in front). */
    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sensors.registerListener(luxListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        else sensors?.unregisterListener(luxListener)
        evaluate()
    }

    /** A touch anywhere: someone is here (also retries a dark camera now). */
    fun onTouch() {
        if (_ui.value.off == OffReason.DARK) { darkUntil = 0L; evaluate() }
        touchAt = SystemClock.elapsedRealtime()
    }

    fun stageEnabled(stage: String): Boolean = settings.getBoolean("camera.stage.$stage", STAGE_DEFAULTS[stage] ?: false)

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Enrolment (settings screen): detection at 5 fps with embeddings, until [endEnrol]. */
    fun beginEnrol(listener: (EnrolSample) -> Unit) { enrolListener = listener; reconfigure() }
    fun endEnrol() { enrolListener = null; reconfigure() }

    /** The greeting's "Not me". */
    fun notMe() {
        val g = _ui.value.greeting ?: profiles.active.value.takeIf { it.id != Profile.SHARED_ID } ?: return
        profiles.reject(g.id)
        _ui.value = _ui.value.copy(greeting = null, phase = if (source != null) CameraPhase.PRESENCE else CameraPhase.OFF)
        main.removeCallbacks(endGreeting)
    }

    // --- gating ---

    private fun offReason(): OffReason = when {
        !visible -> OffReason.HIDDEN
        externalUser -> OffReason.BUSY
        services.system.state.value.cameraBlocked -> OffReason.BLOCKED
        !hasPermission() -> OffReason.NO_PERMISSION
        !stageEnabled("presence") && enrolListener == null && benchStage == null -> OffReason.DISABLED
        SystemClock.elapsedRealtime() < errorUntil -> OffReason.ERROR
        SystemClock.elapsedRealtime() < darkUntil && enrolListener == null && benchStage == null -> OffReason.DARK
        else -> OffReason.NONE
    }

    fun evaluate() {
        val why = offReason()
        if (why != OffReason.NONE) {
            if (source != null) stopCamera()
            _ui.value = _ui.value.copy(phase = CameraPhase.OFF, off = why, presence = Presence.UNKNOWN)
            return
        }
        reconfigure()
    }

    /** Opens the camera at the size the current stage needs (restarting it if the size changes). */
    private fun reconfigure() {
        if (offReason() != OffReason.NONE) { evaluate(); return }
        val detecting = enrolListener != null || benchStage == CameraPhase.DETECTING || benchStage == CameraPhase.RECOGNIZED ||
            SystemClock.elapsedRealtime() < burstUntil
        val width = if (detecting) DETECT_WIDTH else PRESENCE_WIDTH
        val interval = if (detecting) DETECT_INTERVAL_MS else PRESENCE_INTERVAL_MS
        if (source != null && sourceWidth != width) stopCamera(keepPresence = true)
        if (source == null) {
            val src = Camera1Source(preferredWidth = width, minIntervalMs = interval,
                dutyCycle = !detecting && (benchDuty || settings.getBoolean(KEY_DUTY, DUTY_DEFAULT)))
            source = src
            sourceWidth = width
            motion.reset()
            darkStreak = 0
            services.system.update { it.copy(cameraInUse = true) }
            src.start({ f -> onFrame(f) }, { err -> main.post { onCameraError(err) } })
            Log.i(TAG, "camera on: ${src.name} width=$width every ${interval}ms")
        } else source?.let { it.minIntervalMs = interval; (it as? Camera1Source)?.dutyCycle = !detecting && (benchDuty || settings.getBoolean(KEY_DUTY, DUTY_DEFAULT)) }
        val phase = when {
            enrolListener != null -> CameraPhase.ENROLLING
            detecting -> CameraPhase.DETECTING
            _ui.value.greeting != null -> CameraPhase.RECOGNIZED
            else -> CameraPhase.PRESENCE
        }
        _ui.value = _ui.value.copy(phase = phase, off = OffReason.NONE)
    }

    private fun stopCamera(keepPresence: Boolean = false) {
        val s = source ?: return
        source = null
        s.stop()
        stats.framesSeen += s.framesSeen
        services.system.update { it.copy(cameraInUse = false) }
        if (!keepPresence) {
            tracker.reset()
            burstUntil = 0L
            postPresence(Presence.UNKNOWN, false)
        }
        Log.i(TAG, "camera off")
    }

    private fun onCameraError(err: String) {
        Log.w(TAG, "camera error: $err")
        source = null
        services.system.update { it.copy(cameraInUse = false) }
        _ui.value = _ui.value.copy(phase = CameraPhase.OFF, off = OffReason.ERROR)
        // Another client (or the HAL) took the camera: try again later.
        errorUntil = SystemClock.elapsedRealtime() + ERROR_RETRY_MS
        main.postDelayed({ errorUntil = 0L; evaluate() }, ERROR_RETRY_MS)
    }

    private fun onDark() {
        luxAtDark = lux
        darkUntil = SystemClock.elapsedRealtime() + darkRetryMs
        Log.i(TAG, "frames near-black (lux=$lux): camera off, retry in ${darkRetryMs / 1000}s")
        val retry = darkRetryMs
        darkRetryMs = (darkRetryMs * 2).coerceAtMost(DARK_RETRY_MAX_MS)
        evaluate()
        main.postDelayed({ if (_ui.value.off == OffReason.DARK) evaluate() }, retry + 50)
    }

    // --- camera thread ---

    private fun onFrame(f: YPlane) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val now = SystemClock.elapsedRealtime()
        val m = motion.process(f)
        stats.changedSum += m.changed; stats.lumaSum += m.meanLuma; stats.samples++
        (source as? Camera1Source)?.let { stats.restartMs = it.restartLatencyMs }
        stats.presence.add(SystemClock.elapsedRealtimeNanos() - t0)
        if (m.dark && enrolListener == null && benchStage == null) {
            if (++darkStreak >= DARK_SAMPLES) { darkStreak = 0; main.post { if (source != null) onDark() } }
            return
        }
        darkStreak = 0
        val touched = touchAt
        var touchArrived = false
        if (touched != 0L) { touchAt = 0L; tracker.onTouch(touched); touchArrived = tracker.arrived }
        val p = tracker.onSample(m.motion || (benchStage != null), now)
        postPresence(p, tracker.arrived || touchArrived, m.motion)

        val enrol = enrolListener
        val inBurst = now < burstUntil || benchStage == CameraPhase.DETECTING || benchStage == CameraPhase.RECOGNIZED
        if (enrol == null && !inBurst) return
        if (f.width < DETECT_WIDTH) return // still on the presence size; the restart is on its way

        val t1 = SystemClock.elapsedRealtimeNanos()
        val faces = finder.find(f)
        stats.detect.add(SystemClock.elapsedRealtimeNanos() - t1)
        val box = faces.firstOrNull()
        val q = if (enrol != null) enrolQuality else quality
        var issue = box?.let { q.check(it, f.width, f.height) } ?: QualityIssue.TOO_SMALL
        var emb: FloatArray? = null
        var sharp = 0f
        if (box != null && issue == QualityIssue.NONE) {
            cropFace(f, box, 112, crop)
            issue = q.check(crop)
            sharp = QualityFilter.sharpness(crop)
            val recognize = enrol != null || stageEnabled("recognize") || benchStage == CameraPhase.RECOGNIZED
            if (issue == QualityIssue.NONE && recognize) {
                val t2 = SystemClock.elapsedRealtimeNanos()
                emb = embedder.embed(crop)
                stats.embed.add(SystemClock.elapsedRealtimeNanos() - t2)
            }
        }
        if (benchStage == CameraPhase.RECOGNIZED && emb == null) {
            // Bench: time crop + embedding + matching on the centre even without a usable face.
            val t2 = SystemClock.elapsedRealtimeNanos()
            cropFace(f, FaceBox(f.width / 2f, f.height / 2.5f, f.width / 6f, 1f), 112, crop)
            val v = embedder.embed(crop)
            matcher.match(v, burstTemplates)
            stats.embed.add(SystemClock.elapsedRealtimeNanos() - t2)
        }
        stats.count(issue)
        if (enrol != null) {
            val sample = EnrolSample(box, f.width, f.height, issue, emb, sharp)
            main.post { enrolListener?.invoke(sample) }
            return
        }
        if (emb != null && burstTemplates.isNotEmpty()) {
            val match = matcher.match(emb, burstTemplates, burstExcluded)
            Log.i(TAG, "match try ${decision.tries + 1}: ${match.profileId ?: "-"} score=${"%.3f".format(match.score)} second=${"%.3f".format(match.secondScore)}")
            decision.offer(match)
            if (decision.done) { val id = decision.decided; burstUntil = 0L; main.post { endBurst(id) } }
        }
    }

    private fun postPresence(p: Presence, arrived: Boolean, moved: Boolean = false) {
        main.post {
            darkRetryMs = DARK_RETRY_MIN_MS
            if (services.system.state.value.presence != p) services.system.update { it.copy(presence = p) }
            if (_ui.value.presence != p) _ui.value = _ui.value.copy(presence = p)
            onPresence(p, arrived, moved)
        }
    }

    // --- arrivals and bursts (main thread) ---

    private fun onPresence(p: Presence, arrived: Boolean, moved: Boolean) {
        val prev = lastKnown
        if (p != Presence.UNKNOWN) lastKnown = p
        if (p == Presence.EMPTY) { burstsThisArrival = 0; arrivalDone = false }
        if (p != Presence.PRESENT || source == null) return
        if (arrived) {
            // A real arrival (after an empty room) always checks; after a camera restart only if
            // nobody's profile is active (don't re-run while the same person stays in view).
            val shouldRun = prev == Presence.EMPTY || profiles.active.value.id == Profile.SHARED_ID
            if (!shouldRun) { arrivalDone = true; return }
            arrivalDone = false
            burstsThisArrival = 0
        }
        val now = SystemClock.elapsedRealtime()
        if (arrivalDone || burstsThisArrival >= MAX_BURSTS || now < burstUntil || now - lastBurstAt < BURST_GAP_MS) return
        // First burst on arrival; retries (no face found yet) only on later motion.
        if (!arrived && !(moved && burstsThisArrival > 0)) return
        maybeStartBurst()
    }

    private fun maybeStartBurst() {
        if (!stageEnabled("detect") || !profiles.autoByFace.value || _ui.value.greeting != null) return
        val people = if (stageEnabled("recognize")) profiles.enrolledTemplates(embedder.id) else emptyList()
        val excluded = profiles.blocked()
        if (stageEnabled("recognize") && people.none { it.profileId !in excluded }) { arrivalDone = true; return }
        burstTemplates = people
        burstExcluded = excluded
        decision.reset()
        burstsThisArrival++
        lastBurstAt = SystemClock.elapsedRealtime()
        // The window counts from the restart at detection size (~0.6 s to the first frame).
        burstUntil = lastBurstAt + BURST_MS + RESTART_ALLOWANCE_MS
        Log.i(TAG, "detection burst ${burstsThisArrival}/$MAX_BURSTS, ${people.size} enrolled")
        reconfigure()
        main.postDelayed(burstTimeout, BURST_MS + RESTART_ALLOWANCE_MS + 50)
    }

    private val burstTimeout = Runnable { if (burstUntil != 0L && SystemClock.elapsedRealtime() >= burstUntil) endBurst(null) }

    private fun endBurst(decided: String?) {
        main.removeCallbacks(burstTimeout)
        burstUntil = 0L
        burstTemplates = emptyList()
        if (decided != null) {
            arrivalDone = true
            profiles.switchTo(decided, SwitchReason.FACE)
            val p = profiles.profiles.value.firstOrNull { it.id == decided }
            if (p != null && profiles.active.value.id == decided) {
                Log.i(TAG, "recognized ${p.id}")
                _ui.value = _ui.value.copy(greeting = p)
                main.removeCallbacks(endGreeting)
                main.postDelayed(endGreeting, GREETING_MS)
            }
        } else if (decision.tries >= 3) arrivalDone = true
        reconfigure()
    }

    private val endGreeting = Runnable { _ui.value = _ui.value.copy(greeting = null); reconfigure() }

    /** Debug / demo: show the greeting for [p] without a camera (Profiles states screenshots). */
    fun demoGreeting(p: Profile?) {
        main.removeCallbacks(endGreeting)
        _ui.value = _ui.value.copy(greeting = p, phase = if (p != null) CameraPhase.RECOGNIZED else _ui.value.phase)
    }

    /** Rolling per-stage timings (ns), logged and shown on the bench screen. */
    class Stats {
        class Timer { @Volatile var n = 0L; @Volatile var totalNs = 0L; @Volatile var maxNs = 0L
            fun add(ns: Long) { n++; totalNs += ns; if (ns > maxNs) maxNs = ns }
            fun avgMs() = if (n == 0L) 0f else totalNs / n / 1e6f
            override fun toString() = "n=$n avg=${"%.2f".format(avgMs())}ms max=${"%.1f".format(maxNs / 1e6f)}ms" }
        val presence = Timer(); val detect = Timer(); val embed = Timer()
        @Volatile var framesSeen = 0L
        @Volatile var changedSum = 0.0
        @Volatile var lumaSum = 0L
        @Volatile var samples = 0L
        @Volatile var restartMs = 0L
        val issues = IntArray(QualityIssue.entries.size)
        fun count(i: QualityIssue) { issues[i.ordinal]++ }
        override fun toString() = "samples=$samples meanChanged=${"%.4f".format(if (samples > 0) changedSum / samples else 0.0)} meanLuma=${if (samples > 0) lumaSum / samples else 0} restartMs=$restartMs presence[$presence] detect[$detect] embed[$embed] quality=" +
            QualityIssue.entries.filter { issues[it.ordinal] > 0 }.joinToString { "${it.name}:${issues[it.ordinal]}" }
    }

    companion object {
        const val TAG = "EchoCamera"
        const val KEY_DUTY = "camera.presence.duty"
        const val DUTY_DEFAULT = false
        const val PRESENCE_WIDTH = 176
        const val PRESENCE_INTERVAL_MS = 500L
        const val DETECT_WIDTH = 320
        const val DETECT_INTERVAL_MS = 200L
        const val BURST_MS = 2_000L
        const val RESTART_ALLOWANCE_MS = 700L
        const val BURST_GAP_MS = 8_000L
        const val MAX_BURSTS = 3
        const val GREETING_MS = 4_000L
        const val DARK_SAMPLES = 6
        const val DARK_RETRY_MIN_MS = 30_000L
        const val DARK_RETRY_MAX_MS = 5 * 60_000L
        const val ERROR_RETRY_MS = 60_000L
        /** Defaults after measuring each stage on the Echo (docs/status/camera.md). */
        val STAGE_DEFAULTS = mapOf("presence" to true, "detect" to true, "recognize" to true)
    }
}
