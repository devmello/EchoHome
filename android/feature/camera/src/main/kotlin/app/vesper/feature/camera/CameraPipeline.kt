package app.vesper.feature.camera

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
import app.vesper.core.model.VesperServices
import app.vesper.core.model.Presence
import app.vesper.core.model.Profile
import app.vesper.core.model.SwitchReason
import app.vesper.feature.camera.capture.Camera1Source
import app.vesper.feature.camera.capture.FrameSource
import app.vesper.feature.camera.capture.YPlane
import app.vesper.feature.camera.face.BlazeFaceFinder
import app.vesper.feature.camera.face.ConsecutiveMatcher
import app.vesper.feature.camera.face.FaceBox
import app.vesper.feature.camera.face.FaceFinder
import app.vesper.feature.camera.face.FaceImage
import app.vesper.feature.camera.face.FaceMatcher
import app.vesper.feature.camera.face.FaceTemplates
import app.vesper.feature.camera.face.FallbackEmbedder
import app.vesper.feature.camera.face.GreyImage
import app.vesper.feature.camera.face.LbpEmbedder
import app.vesper.feature.camera.face.LegacyFaceFinder
import app.vesper.feature.camera.face.MatchPolicy
import app.vesper.feature.camera.face.ModelFiles
import app.vesper.feature.camera.face.QualityFilter
import app.vesper.feature.camera.face.QualityIssue
import app.vesper.feature.camera.face.TfliteEmbedder
import app.vesper.feature.camera.face.alignFace
import app.vesper.feature.camera.face.cropFace
import app.vesper.feature.camera.presence.MotionDetector
import app.vesper.feature.camera.presence.PresenceTracker
import app.vesper.feature.camera.profiles.FaceProfileManager
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
class EnrolSample(val box: FaceBox?, val frameW: Int, val frameH: Int, val issue: QualityIssue, val embedding: FloatArray?, val sharpness: Float,
    val embedderId: String = "")

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
 * Detection is BlazeFace (or `android.media.FaceDetector`, setting `camera.detector`); the face is
 * aligned on its landmarks and embedded by EdgeFace (setting `camera.embedder`) on TFLite, with LBP as the fallback if
 * the model can't be loaded. The models are loaded on the first detection frame and freed
 * whenever the camera stops (end of a burst, enrolment, screen off).
 *
 * Each stage can be turned off in settings (`camera.stage.presence|detect|recognize`); they were
 * measured on the Echo before being turned on by default (docs/status/camera.md).
 */
class CameraPipeline(
    private val context: Context,
    private val services: VesperServices,
    private val profiles: FaceProfileManager,
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = services.settings
    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui.asStateFlow()

    /**
     * The face model picked in settings (`camera.embedder`: edgeface-xxs | edgeface-xs; debug: lbp),
     * or LBP for good once the model fails to load. Swapped live when the setting changes.
     */
    @Volatile var embedder: FallbackEmbedder = buildEmbedder(); private set

    private fun buildEmbedder(): FallbackEmbedder {
        val key = settings.getString(KEY_EMBEDDER, ModelFiles.EMBEDDERS[0].key)
        val m = ModelFiles.embedder(key)
        return FallbackEmbedder(
            if (key == "lbp") null else TfliteEmbedder(m.id, { ModelFiles.load(context, m.path) }, m.threads, m.xnnpack),
            LbpEmbedder(),
        ) { t -> Log.w(TAG, "face model ${m.id} failed, using LBP: $t") }
    }
    @Volatile var stats = Stats()

    private var source: FrameSource? = null
    private var sourceWidth = 0
    private var visible = false
    private var started = false

    // camera-thread state
    private val motion = MotionDetector()
    private val tracker = PresenceTracker()
    // Models: created on the camera thread, freed after the camera stops (both under modelLock).
    private val modelLock = Any()
    private var blaze: FaceFinder? = null
    private var blazeFailed = false
    private var legacy: LegacyFaceFinder? = null
    private val quality = QualityFilter()
    private val enrolQuality = QualityFilter(maxAsymmetry = 0.45f)
    private val matcher = FaceMatcher()
    private val decision = ConsecutiveMatcher()
    private val crop = GreyImage(112, 112)
    private val aligned = FaceImage(112)
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
    /** Debug: run both detectors on every detection frame (bench stage "detect-compare"). */
    @Volatile var benchCompare = false

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
        services.scope.launch {
            settings.changes.collect { k ->
                if (k.startsWith("camera.stage.") || k == KEY_DUTY) evaluate()
                if (k == KEY_EMBEDDER) synchronized(modelLock) { embedder.release(); embedder = buildEmbedder(); Log.i(TAG, "face model: ${embedder.id}") }
            }
        }
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
        releaseModels()
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
        releaseModels()
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

    /** Frees the detector and embedder (the camera thread is stopped, or between frames). */
    private fun releaseModels() = synchronized(modelLock) {
        blaze?.close(); blaze = null
        legacy?.close(); legacy = null
        embedder.release()
    }

    private fun useBlaze() = !blazeFailed && settings.getString(KEY_DETECTOR, DETECTOR_DEFAULT) == "blazeface"

    /** The detector for this frame, created on first use (camera thread, under modelLock). */
    private fun finder(blazeWanted: Boolean): FaceFinder {
        if (blazeWanted && !blazeFailed) {
            blaze?.let { return it }
            val t0 = SystemClock.elapsedRealtimeNanos()
            try {
                return BlazeFaceFinder(ModelFiles.load(context, ModelFiles.DETECTOR)).also {
                    blaze = it
                    stats.load.add(SystemClock.elapsedRealtimeNanos() - t0)
                }
            } catch (t: Throwable) {
                blazeFailed = true
                Log.w(TAG, "BlazeFace failed to load, using android.media.FaceDetector: $t")
            }
        }
        return legacy ?: LegacyFaceFinder().also { legacy = it }
    }

    /** The match rule: the embedder's, with the user's minimum threshold for the neural model. */
    private fun policy(): MatchPolicy {
        val p = embedder.policy
        if (p == MatchPolicy.LBP) return p
        val floor = settings.getFloat(KEY_THRESHOLD, p.floor).coerceIn(0.2f, 0.9f)
        return p.copy(floor = floor, ceiling = maxOf(p.ceiling, floor))
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

        synchronized(modelLock) { detect(f, enrol) }
    }

    private fun detect(f: YPlane, enrol: ((EnrolSample) -> Unit)?) {
        val wantBlaze = useBlaze()
        val det = finder(wantBlaze)
        val t1 = SystemClock.elapsedRealtimeNanos()
        val faces = det.find(f)
        stats.detect.add(SystemClock.elapsedRealtimeNanos() - t1)
        stats.found(det.name, faces.isNotEmpty())
        if (benchCompare) {
            val other = finder(!wantBlaze)
            if (other !== det) {
                val t = SystemClock.elapsedRealtimeNanos()
                val alt = other.find(f)
                stats.detectAlt.add(SystemClock.elapsedRealtimeNanos() - t)
                stats.found(other.name, alt.isNotEmpty())
                if (faces.isNotEmpty() || alt.isNotEmpty()) Log.i(TAG, "compare: ${det.name}=${faces.firstOrNull()} ${other.name}=${alt.firstOrNull()}")
            }
        }
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
            if (issue == QualityIssue.NONE && recognize) emb = embed(f, box)
        }
        if (benchStage == CameraPhase.RECOGNIZED && emb == null) {
            // Bench: time alignment + embedding + matching on the centre even without a usable face.
            val v = embed(f, FaceBox(f.width / 2f, f.height / 2.5f, f.width / 6f, 1f))
            matcher.match(v, burstTemplates)
        }
        stats.count(issue)
        if (enrol != null) {
            val sample = EnrolSample(box, f.width, f.height, issue, emb, sharp, embedder.id)
            main.post { enrolListener?.invoke(sample) }
            return
        }
        if (emb != null && burstTemplates.isNotEmpty()) {
            matcher.policy = policy()
            val match = matcher.match(emb, burstTemplates, burstExcluded)
            Log.i(TAG, "match try ${decision.tries + 1}: ${match.profileId ?: "-"} score=${"%.3f".format(match.score)} second=${"%.3f".format(match.secondScore)}")
            decision.offer(match)
            if (decision.done) { val id = decision.decided; burstUntil = 0L; main.post { endBurst(id) } }
        }
    }

    /** Aligns and embeds; the first call after the camera starts also loads the model (timed apart). */
    private fun embed(f: YPlane, box: FaceBox): FloatArray {
        val t = SystemClock.elapsedRealtimeNanos()
        alignFace(f, box, aligned)
        stats.align.add(SystemClock.elapsedRealtimeNanos() - t)
        val wasLoaded = (embedder.current as? TfliteEmbedder)?.loaded ?: true
        val t2 = SystemClock.elapsedRealtimeNanos()
        val v = embedder.embed(aligned)
        val dt = SystemClock.elapsedRealtimeNanos() - t2
        if (wasLoaded) stats.embed.add(dt) else stats.load.add(dt)
        return v
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
        val presence = Timer(); val detect = Timer(); val detectAlt = Timer(); val align = Timer(); val embed = Timer()
        /** Model loads (detector create, first embedding incl. interpreter create). */
        val load = Timer()
        private val foundBy = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
        /** Frames each detector ran on, and found a face in. */
        fun found(detector: String, hit: Boolean) { val c = foundBy.getOrPut(detector) { IntArray(2) }; c[0]++; if (hit) c[1]++ }
        @Volatile var framesSeen = 0L
        @Volatile var changedSum = 0.0
        @Volatile var lumaSum = 0L
        @Volatile var samples = 0L
        @Volatile var restartMs = 0L
        val issues = IntArray(QualityIssue.entries.size)
        fun count(i: QualityIssue) { issues[i.ordinal]++ }
        override fun toString() = "samples=$samples meanChanged=${"%.4f".format(if (samples > 0) changedSum / samples else 0.0)} meanLuma=${if (samples > 0) lumaSum / samples else 0} restartMs=$restartMs presence[$presence] detect[$detect] detectAlt[$detectAlt] align[$align] embed[$embed] load[$load] found=" +
            foundBy.entries.joinToString { "${it.key}:${it.value[1]}/${it.value[0]}" } + " quality=" +
            QualityIssue.entries.filter { issues[it.ordinal] > 0 }.joinToString { "${it.name}:${issues[it.ordinal]}" }
    }

    companion object {
        const val TAG = "VesperCamera"
        const val KEY_DUTY = "camera.presence.duty"
        const val KEY_DETECTOR = "camera.detector"
        const val DETECTOR_DEFAULT = "blazeface"
        /** The face model: [ModelFiles.EMBEDDERS] keys (debug: "lbp" forces LBP). */
        const val KEY_EMBEDDER = "camera.embedder"
        /** The neural model's minimum match threshold ([MatchPolicy.floor]). */
        const val KEY_THRESHOLD = "camera.match.threshold"
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
