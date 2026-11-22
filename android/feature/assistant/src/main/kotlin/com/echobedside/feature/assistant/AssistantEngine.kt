package com.echobedside.feature.assistant

import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.AssistantPhase
import com.echobedside.core.model.HaConnection
import com.echobedside.core.model.HaException
import com.echobedside.core.model.HomeAssistant
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.IslandManager
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.Navigator
import com.echobedside.core.model.SettingsStore
import com.echobedside.core.model.SystemStateStore
import com.echobedside.feature.assistant.audio.Ducker
import com.echobedside.feature.assistant.audio.MicSource
import com.echobedside.feature.assistant.audio.Pcm
import com.echobedside.feature.assistant.audio.SpeechPlayer
import com.echobedside.feature.assistant.audio.TAG
import com.echobedside.feature.assistant.audio.VoiceGate
import com.echobedside.feature.assistant.pipeline.AssistReducer
import com.echobedside.feature.assistant.pipeline.AssistState
import com.echobedside.feature.assistant.pipeline.DemoScripts
import com.echobedside.feature.assistant.pipeline.Phase
import com.echobedside.feature.assistant.pipeline.PipelineEvent
import com.echobedside.feature.assistant.pipeline.PipelineEvents
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Why the assistant can or can't listen right now (status pill + idle subtitle). */
enum class Availability { READY, NOT_CONFIGURED, CONNECTING, NO_PERMISSION, MUTED, DISABLED }

/**
 * The voice assistant: Home Assistant's Assist pipeline (wake word → STT → conversation agent → TTS).
 *
 * - While HA is connected, the mic isn't muted (software or hardware), the RECORD_AUDIO permission
 *   is granted and the assistant is enabled, it keeps one `assist_pipeline/run` from the
 *   `wake_word` stage open and streams 16 kHz mono PCM to it (openWakeWord runs on the server).
 *   A local voice gate only lets audio through around speech (HA's recommendation).
 * - When the wake word is heard: opens the assistant screen (setting), publishes the ASSISTANT
 *   LiveActivity, ducks other audio, streams the command until HA's VAD ends it, shows the
 *   transcript, the agent's tool steps and the answer, plays the TTS, and starts the next
 *   wake-word run once it's done (or a follow-up run when the agent asks a question).
 * - On mute (software or hardware) the mic stops at once and the run is ended.
 *
 * Everything runs on [scope] (main thread); audio chunks are sent from the mic thread.
 */
class AssistantEngine(
    private val scope: CoroutineScope,
    private val ha: () -> HomeAssistant,
    private val system: SystemStateStore,
    private val island: IslandManager,
    private val settings: SettingsStore,
    private val navigator: () -> Navigator?,
    private val mic: MicSource,
    private val player: SpeechPlayer,
    private val ducker: Ducker,
    private val hasMicPermission: () -> Boolean,
    val demoData: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AssistState())
    val state: StateFlow<AssistState> = _state.asStateFlow()
    private val _availability = MutableStateFlow(Availability.CONNECTING)
    val availability: StateFlow<Availability> = _availability.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    /** Last error of the background wake-word run (e.g. no wake word engine), null once it works. */
    private val _wakeError = MutableStateFlow<String?>(null)
    val wakeError: StateFlow<String?> = _wakeError.asStateFlow()

    /** Smoothed 0..1 mic level (mic thread writes, UI reads every frame). */
    @Volatile var micLevel = 0f; private set
    /** Bytes of audio sent to HA since start (for perf notes). */
    @Volatile var bytesSent = 0L; private set

    val haConnected: Boolean get() = ha().connection.value == HaConnection.CONNECTED

    private val permission = MutableStateFlow(false)
    private val enabled = MutableStateFlow(true)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var nextRequest: Request? = null
    private var currentRun: Job? = null
    private var demoJob: Job? = null
    private var dismissJob: Job? = null
    private var islandTicker: Job? = null
    private var started = false
    private var micTest = false
    private var openedByWake = false
    private var lastAnswerAt = 0L
    private var pipelineId: String? = null

    private sealed class Request(val startStage: String, val needsMic: Boolean) {
        data object Wake : Request("wake_word", true)
        data object PushToTalk : Request("stt", true)
        data object FollowUp : Request("stt", true)
        data class Text(val text: String) : Request("intent", false)
    }

    // ---------------- lifecycle ----------------

    fun start() {
        if (started) return
        started = true
        refreshPermission()
        enabled.value = settings.getBoolean(KEY_ENABLED, true)
        scope.launch {
            combine(
                ha().connection,
                system.state.map { it.micMuted }.distinctUntilChanged(),
                permission,
                enabled,
            ) { conn, muted, perm, on ->
                when {
                    conn == HaConnection.NOT_CONFIGURED -> Availability.NOT_CONFIGURED
                    muted -> Availability.MUTED
                    !on -> Availability.DISABLED
                    !perm -> Availability.NO_PERMISSION
                    conn != HaConnection.CONNECTED -> Availability.CONNECTING
                    else -> Availability.READY
                }
            }.distinctUntilChanged().collect { a ->
                val was = _availability.value
                _availability.value = a
                Log.i(TAG, "availability $a")
                if (a != Availability.READY) onNotReady(muted = a == Availability.MUTED)
                if (a == Availability.READY && was != Availability.READY) signals.trySend(Unit)
            }
        }
        scope.launch {
            settings.changes.onStart { emit(KEY_ENABLED) }.collect { key ->
                if (key == KEY_ENABLED) enabled.value = settings.getBoolean(KEY_ENABLED, true)
                if (key == KEY_PIPELINE) pipelineId = null
            }
        }
        scope.launch { combine(_state, _speaking) { s, sp -> s to sp }.collect { (s, sp) -> updateIsland(s, sp) } }
        scope.launch { loop() }
    }

    fun refreshPermission() { permission.value = hasMicPermission() }

    /** Mic off / HA gone / disabled: stop capturing right away, end any audio run. */
    private fun onNotReady(muted: Boolean) {
        if (!micTest || muted) stopMic()
        val s = _state.value
        val audioRun = s.wakeListening || s.phase == Phase.LISTENING
        if (audioRun && demoJob?.isActive != true) {
            currentRun?.cancel()
            nextRequest = null
            if (s.phase == Phase.LISTENING) _state.value = AssistState()
        }
    }

    // ---------------- requests from the UI ----------------

    /** The droplet: start listening (push-to-talk, no wake word needed), or stop whatever is going on. */
    fun tapDroplet() {
        val s = _state.value
        val busy = s.phase == Phase.LISTENING || s.phase == Phase.WORKING || _speaking.value || demoJob?.isActive == true
        when {
            busy -> stopAll()
            useDemo() -> runDemo(DemoScripts.byId(s.demo).id)
            _availability.value == Availability.READY -> request(Request.PushToTalk)
            else -> {} // the screen explains why (mic off / not connected / permission)
        }
    }

    /** A suggestion chip: a scripted demo without HA, else the chip's text sent to the agent. */
    fun ask(demoId: String) {
        if (useDemo()) { runDemo(demoId); return }
        request(Request.Text(DemoScripts.byId(demoId).query))
    }

    fun useDemo() = demoData && !haConnected

    fun stopAll() {
        demoJob?.cancel(); demoJob = null
        player.stop()
        nextRequest = null
        currentRun?.cancel()
        _speaking.value = false
        ducker.release()
        _state.value = AssistState()
        signals.trySend(Unit)
    }

    /** The user touched the screen: don't auto-dismiss it. */
    fun userInteracted() { openedByWake = false; dismissJob?.cancel() }

    /** The screen left: an old answer shouldn't greet the next visit. */
    fun screenHidden() {
        if (micTest) setMicTest(false)
        val s = _state.value
        if ((s.phase == Phase.ANSWER || s.phase == Phase.ERROR) && !_speaking.value) {
            _state.value = AssistState(wakeListening = s.wakeListening, handlerId = s.handlerId, conversationId = s.conversationId)
        }
        openedByWake = false
    }

    private fun request(r: Request) {
        demoJob?.cancel(); demoJob = null
        player.stop()
        nextRequest = r
        currentRun?.cancel()
        signals.trySend(Unit)
    }

    // ---------------- the run loop ----------------

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val req = nextRequest.also { nextRequest = null }
                ?: if (_availability.value == Availability.READY && demoJob?.isActive != true && !micTest) Request.Wake else null
            if (req == null) { signals.receive(); continue }
            if (req.needsMic && _availability.value != Availability.READY) continue
            if (!req.needsMic && !haConnected) {
                _state.value = AssistState(phase = Phase.ERROR, answer = AssistReducer.friendly("not_connected"), errorCode = "not_connected")
                continue
            }
            val startedAt = now()
            val job = scope.launch { runPipeline(req) }
            currentRun = job
            job.join()
            currentRun = null
            // A wake-word run that fails at once (e.g. no wake word engine in the pipeline) mustn't spin.
            val quick = now() - startedAt < 2000 && nextRequest == null
            val s = _state.value
            failures = if (quick && (s.errorCode != null || req is Request.Wake)) failures + 1 else 0
            if (failures > 0) {
                val wait = (2000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(60_000L)
                Log.w(TAG, "pipeline run ended quickly (${s.errorCode}); retrying in ${wait}ms")
                withTimeoutOrNull(wait) { signals.receive() }
            }
        }
    }

    private suspend fun runPipeline(req: Request) {
        val client = ha()
        // Context carries over to a follow-up, or to a tap/chip shortly after an answer (not to wake runs).
        val follow = req is Request.FollowUp || (req !is Request.Wake && now() - lastAnswerAt < CONVERSATION_KEEP_MS)
        val prevConversation = _state.value.conversationId.takeIf { follow && it?.startsWith("demo-") == false }
        _state.value = AssistReducer.begin(_state.value, req.startStage, (req as? Request.Text)?.text)
            .let { if (prevConversation == null) it.copy(conversationId = null) else it }
        if (req !is Request.Wake) { ducker.duck(); if (req !is Request.FollowUp) openedByWake = false }

        if (req.needsMic && !startMic()) {
            _state.value = AssistState(phase = Phase.ERROR, answer = "The microphone isn’t available.", errorCode = "mic")
            return
        }
        router.reset(gated = req is Request.Wake && settings.getBoolean(KEY_VAD, true))

        val msg = JSONObject()
            .put("type", "assist_pipeline/run")
            .put("start_stage", req.startStage)
            .put("end_stage", "tts")
            .put("timeout", RUN_TIMEOUT_S)
        when (req) {
            is Request.Text -> msg.put("input", JSONObject().put("text", req.text))
            Request.Wake -> msg.put("input", JSONObject()
                .put("sample_rate", Pcm.SAMPLE_RATE)
                .put("timeout", settings.getInt(KEY_WAKE_TIMEOUT, WAKE_TIMEOUT_S))
                .put("noise_suppression_level", settings.getInt(KEY_NOISE, 0))
                .put("auto_gain_dbfs", settings.getInt(KEY_GAIN, 0)))
            else -> msg.put("input", JSONObject().put("sample_rate", Pcm.SAMPLE_RATE))
        }
        resolvePipeline(client)?.let { msg.put("pipeline", it) }
        prevConversation?.let { msg.put("conversation_id", it) }

        var tts: Deferred<ByteArray?>? = null
        var ttsMime: String? = null
        try {
            client.subscribe(msg)
                .map { PipelineEvents.parse(it) }
                .transformWhile { emit(it); it !is PipelineEvent.RunEnd }
                .collect { ev ->
                    if (ev !is PipelineEvent.IntentProgress) Log.d(TAG, "event ${ev::class.simpleName}")
                    dispatch(ev)
                    when (ev) {
                        is PipelineEvent.RunStart -> if (req.needsMic && ev.handlerId != null) router.attach(client, ev.handlerId)
                        PipelineEvent.WakeWordStart -> _wakeError.value = null
                        is PipelineEvent.WakeWordEnd -> if (ev.detected) onWakeWord()
                        PipelineEvent.SttVadEnd, is PipelineEvent.SttEnd -> router.endAudio()
                        is PipelineEvent.TtsEnd -> ev.url?.let { url ->
                            ttsMime = ev.mimeType
                            tts = scope.async { try { client.fetchBytes(url) } catch (e: Exception) { Log.w(TAG, "TTS fetch failed: ${e.message}"); null } }
                        }
                        is PipelineEvent.Error -> {
                            router.endAudio()
                            if (req is Request.Wake && ev.code !in BENIGN_WAKE_ERRORS && _state.value.phase == Phase.IDLE) {
                                Log.w(TAG, "wake-word run error ${ev.code}: ${ev.message}")
                                _wakeError.value = ev.code
                            }
                        }
                        else -> {}
                    }
                }
        } catch (e: CancellationException) {
            router.endAudio()
            tts?.cancel()
            throw e
        } catch (e: HaException) {
            Log.w(TAG, "pipeline run failed: ${e.code}")
            dispatch(PipelineEvent.Error(e.code, e.message ?: ""))
        } catch (e: Exception) {
            Log.w(TAG, "pipeline run failed", e)
            dispatch(PipelineEvent.Error("unknown", e.message ?: ""))
        } finally {
            router.endAudio()
            if (!wakeEnabled() && !micTest) stopMic()
        }

        val s = _state.value
        if (s.phase == Phase.ANSWER || s.phase == Phase.ERROR) {
            lastAnswerAt = now()
            val audio = tts?.await()
            if (audio != null) {
                ducker.duck()
                _speaking.value = true
                try { player.play(audio, ttsMime) } finally { _speaking.value = false }
            }
            ducker.release()
            val cur = _state.value
            // The agent asked something back: listen again without the wake word.
            if (cur.continueConversation && cur.phase == Phase.ANSWER && _availability.value == Availability.READY) {
                nextRequest = Request.FollowUp
                return
            }
            scheduleDismiss()
        } else if (s.phase != Phase.LISTENING) {
            ducker.release()
        }
    }

    private fun wakeEnabled() = enabled.value

    private fun dispatch(e: PipelineEvent) { _state.value = AssistReducer.reduce(_state.value, e) }

    private fun onWakeWord() {
        router.wakeWordHeard()
        ducker.duck()
        dismissJob?.cancel()
        val nav = navigator()
        if (settings.getBoolean(KEY_OPEN_ON_WAKE, true) && nav != null && nav.current.value != SCREEN_ID) {
            openedByWake = true
            nav.show(SCREEN_ID, null)
        }
    }

    /** After an answer: back to StandBy if the wake word opened the screen, else back to "How can I help?". */
    private fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(settings.getInt(KEY_DISMISS_S, DISMISS_S) * 1000L)
            val s = _state.value
            if (s.phase != Phase.ANSWER && s.phase != Phase.ERROR) return@launch
            val nav = navigator()
            if (openedByWake && nav?.current?.value == SCREEN_ID) nav.home()
            openedByWake = false
            _state.value = AssistState(wakeListening = s.wakeListening, handlerId = s.handlerId, conversationId = s.conversationId)
        }
    }

    /** `assistant.pipeline` may be a pipeline id or its name; names are looked up once. */
    private suspend fun resolvePipeline(client: HomeAssistant): String? {
        val want = settings.getString(KEY_PIPELINE, "").trim()
        if (want.isEmpty()) return null
        pipelineId?.let { return it }
        val found = try {
            val res = client.command(JSONObject().put("type", "assist_pipeline/pipeline/list")).optJSONObject("result")
            val list = res?.optJSONArray("pipelines")
            (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }
                .firstOrNull { it.optString("id") == want || it.optString("name").equals(want, ignoreCase = true) }
                ?.optString("id")
        } catch (e: Exception) { null }
        if (found == null) Log.w(TAG, "pipeline '$want' not found; using the preferred pipeline")
        pipelineId = found ?: want
        return found
    }

    // ---------------- mic → HA ----------------

    private fun startMic(): Boolean = mic.running || mic.start(router::onChunk)

    private fun stopMic() {
        router.endAudio()
        mic.stop()
        micLevel = 0f
    }

    /** Capture without HA, discarding the audio (perf measurement, level meter check). */
    fun setMicTest(on: Boolean) {
        if (on == micTest) return
        micTest = on
        if (on) {
            if (system.state.value.micMuted) { micTest = false; return }
            refreshPermission()
            startMic()
        } else if (_availability.value != Availability.READY) stopMic()
    }

    private val router = AudioRouter()

    /** Runs on the mic thread (onChunk) and the main thread (attach/end), hence the lock. */
    private inner class AudioRouter {
        private val lock = Any()
        private var client: HomeAssistant? = null
        private var handler: Int? = null
        private val gate = VoiceGate()

        fun reset(gated: Boolean) = synchronized(lock) { gate.enabled = gated; client = null; handler = null }

        fun attach(c: HomeAssistant, id: Int) = synchronized(lock) { client = c; handler = id }

        /** From here on HA is transcribing the command: stream everything. */
        fun wakeWordHeard() = synchronized(lock) { gate.enabled = false }

        /** Empty frame = end of audio. Nothing is sent after it. */
        fun endAudio() = synchronized(lock) {
            val c = client
            val h = handler
            client = null; handler = null
            if (c != null && h != null) c.sendBinary(h, ByteArray(0))
        }

        fun onChunk(chunk: ByteArray, db: Float) {
            val lvl = Pcm.level(db)
            micLevel += (lvl - micLevel) * if (lvl > micLevel) 0.5f else 0.15f
            synchronized(lock) {
                val out = gate.process(chunk, db)
                val c = client ?: return
                val h = handler ?: return
                for (b in out) { c.sendBinary(h, b); bytesSent += b.size }
            }
        }
    }

    // ---------------- demos ----------------

    /**
     * Plays a scripted demo through the state machine. [freezeAtMs] jumps to that point of the
     * script and holds there (for screenshots).
     */
    fun runDemo(id: String, freezeAtMs: Long? = null) {
        val demo = DemoScripts.byId(id)
        demoJob?.cancel()
        player.stop()
        nextRequest = null
        currentRun?.cancel()
        dismissJob?.cancel()
        _state.value = AssistReducer.begin(AssistState(), "stt", demo = demo.id)
        val timeline = DemoScripts.timeline(demo)
        demoJob = scope.launch {
            var t = 0L
            for ((at, ev) in timeline) {
                if (freezeAtMs != null && at > freezeAtMs) return@launch
                if (freezeAtMs == null && at > t) { delay(at - t); t = at }
                dispatch(ev)
                if (ev is PipelineEvent.IntentEnd && freezeAtMs == null) {
                    _speaking.value = true
                    try { delay(DemoScripts.speakMs(demo)) } finally { _speaking.value = false }
                    t += DemoScripts.speakMs(demo)
                }
            }
            if (freezeAtMs == null) scheduleDismiss()
        }
    }

    // ---------------- island ----------------

    private var islandPhase: AssistantPhase? = null

    private fun updateIsland(s: AssistState, speaking: Boolean) {
        val phase = when {
            s.phase == Phase.LISTENING -> AssistantPhase.LISTENING
            s.phase == Phase.WORKING -> AssistantPhase.WORKING
            speaking -> AssistantPhase.SPEAKING
            else -> null
        }
        if (phase == islandPhase) return
        islandPhase = phase
        if (phase == null) {
            islandTicker?.cancel(); islandTicker = null
            island.end(ACTIVITY_ID)
            return
        }
        island.publish(LiveActivity(
            id = ACTIVITY_ID,
            kind = ActivityKind.ASSISTANT,
            content = LiveContent.Assistant(phase, levelFor(phase)),
            actions = listOf(IslandAction("stop", "Stop") { stopAll() }),
            ownerScreen = SCREEN_ID,
        ))
        if (islandTicker == null) islandTicker = scope.launch {
            // Voice level for the island's bars, 10×/s, only while the island (not our screen) shows it.
            while (true) {
                delay(100)
                val p = islandPhase ?: break
                if (p == AssistantPhase.WORKING || navigator()?.current?.value == SCREEN_ID) continue
                val l = levelFor(p)
                island.update(ACTIVITY_ID) { it.copy(content = LiveContent.Assistant(p, l)) }
            }
        }
    }

    /** The droplet's 0..1 voice level per phase (Assistant.dc.html `level`). */
    fun levelFor(phase: AssistantPhase?, t: Double = now() / 1000.0): Float = when (phase) {
        AssistantPhase.LISTENING ->
            if (_state.value.demo != null || micLevel <= 0f) (0.55 + 0.45 * abs(sin(t * 7.3) * cos(t * 3.1))).toFloat()
            else (0.3f + 0.7f * micLevel).coerceIn(0f, 1f)
        AssistantPhase.WORKING -> 0.4f
        AssistantPhase.SPEAKING -> (0.5 + 0.3 * abs(sin(t * 9))).toFloat()
        null -> if (micTest) micLevel else 0.25f
    }

    val micTesting: Boolean get() = micTest

    companion object {
        const val SCREEN_ID = "assistant"
        const val ACTIVITY_ID = "assistant"
        const val KEY_ENABLED = "assistant.enabled"
        const val KEY_PIPELINE = "assistant.pipeline"
        const val KEY_OPEN_ON_WAKE = "assistant.openOnWake"
        const val KEY_VAD = "assistant.vadGate"
        const val KEY_WAKE_TIMEOUT = "assistant.wakeTimeoutSec"
        const val KEY_NOISE = "assistant.noiseSuppression"
        const val KEY_GAIN = "assistant.autoGainDbfs"
        const val KEY_DISMISS_S = "assistant.dismissSec"
        const val KEY_WAKE_WORD = "assistant.wakeWordName"
        const val RUN_TIMEOUT_S = 600
        const val WAKE_TIMEOUT_S = 60
        const val DISMISS_S = 12
        const val CONVERSATION_KEEP_MS = 60_000L
        val BENIGN_WAKE_ERRORS = setOf("wake-word-timeout", "timeout", "disconnected", "duplicate_wake_up_detected")
    }
}
