package com.echobedside.feature.assistant

import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.AssistantPhase
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
import com.echobedside.feature.assistant.audio.TAG
import com.echobedside.feature.assistant.audio.VoiceGate
import com.echobedside.feature.assistant.llm.AssistantAgent
import com.echobedside.feature.assistant.llm.ClaudeException
import com.echobedside.feature.assistant.llm.ClaudeModels
import com.echobedside.feature.assistant.pipeline.AssistReducer
import com.echobedside.feature.assistant.pipeline.AssistState
import com.echobedside.feature.assistant.pipeline.DemoScripts
import com.echobedside.feature.assistant.pipeline.Phase
import com.echobedside.feature.assistant.pipeline.SpeechText
import com.echobedside.feature.assistant.pipeline.Transcript
import com.echobedside.feature.assistant.pipeline.TurnEvent
import com.echobedside.feature.assistant.voice.VoiceStack
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Why the assistant can or can't listen right now (status pill + idle subtitle). */
enum class Availability { READY, NO_KEY, NO_MODELS, NO_PERMISSION, MUTED, DISABLED }

/**
 * The voice assistant (NOTES §6): the wake word, speech-to-text and text-to-speech run on the
 * device (sherpa-onnx, [voice]); Claude answers through the Messages API with the display's tools
 * ([agent]). Home Assistant isn't needed.
 *
 * - Tap the droplet (or a chip) to talk. The STT model loads per turn (the mic buffers meanwhile)
 *   and is freed after the final transcript; TTS loads while Claude works and is freed after speaking.
 * - The wake word ("Hey Nova", placeholder) is optional (`assistant.wakeWord`, off by default):
 *   while on, the mic and the small keyword spotter run whenever the assistant is idle, behind a
 *   voice gate so the model only decodes around speech.
 * - On mute (software or hardware) the mic stops at once and any turn ends.
 * - Demo mode (demo data on, no Anthropic key): the droplet and chips play the design's scripts.
 *
 * State lives on [scope] (main thread); audio is decoded on [voiceThread]. Never logs transcripts
 * or the key.
 */
class AssistantEngine(
    private val scope: CoroutineScope,
    private val system: SystemStateStore,
    private val island: IslandManager,
    private val settings: SettingsStore,
    private val navigator: () -> Navigator?,
    private val mic: MicSource,
    private val ducker: Ducker,
    private val voice: VoiceStack,
    private val agent: () -> AssistantAgent?,
    /** Whether an Anthropic key is set; emits when it changes (the settings page sets it at runtime). */
    private val keyConfigured: Flow<Boolean> = flowOf(true),
    private val hasMicPermission: () -> Boolean,
    private val voiceThread: CoroutineDispatcher,
    /** TTS gets its own thread: loading the voice is slow on the Echo and must never hold up listening. */
    private val ttsThread: CoroutineDispatcher = voiceThread,
    /** Speak answers unless the setting says otherwise (true with a real-time voice). */
    private val speakDefault: Boolean = SPEAK_DEFAULT,
    val demoData: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(AssistState())
    val state: StateFlow<AssistState> = _state.asStateFlow()
    private val _availability = MutableStateFlow(Availability.NO_KEY)
    val availability: StateFlow<Availability> = _availability.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val _wakeActive = MutableStateFlow(false)
    /** True while the wake word is being listened for. */
    val wakeActive: StateFlow<Boolean> = _wakeActive.asStateFlow()

    /** Smoothed 0..1 mic level (mic thread writes, UI reads every frame). */
    @Volatile var micLevel = 0f; private set

    private val permission = MutableStateFlow(false)
    private val enabled = MutableStateFlow(true)
    private val wakeSetting = MutableStateFlow(false)
    private val turnActive = MutableStateFlow(false)

    @Volatile private var audio: Channel<ByteArray>? = null
    private var turnJob: Job? = null
    private var wakeJob: Job? = null
    private var demoJob: Job? = null
    private var dismissJob: Job? = null
    private var islandTicker: Job? = null
    private var started = false
    private var micTest = false
    private var openedByWake = false
    /** Bumped for every turn; events from an older turn are dropped. */
    @Volatile private var token = 0
    private var history: List<JSONObject> = emptyList()
    /** The answer being spoken while it streams. */
    @Volatile private var speech: Speech? = null
    /** The model that wrote [history]; another model starts a fresh conversation. */
    private var historyModel: String? = null
    private var lastAnswerAt = 0L

    // ---------------- lifecycle ----------------

    fun start() {
        if (started) return
        started = true
        refreshPermission()
        enabled.value = settings.getBoolean(KEY_ENABLED, true)
        wakeSetting.value = settings.getBoolean(KEY_WAKE, false)
        Log.i(TAG, "voice: ${voice.describe()}")
        selectVoice()
        scope.launch {
            combine(
                system.state.map { it.micMuted }.distinctUntilChanged(),
                permission, enabled, keyConfigured.distinctUntilChanged(),
            ) { muted, perm, on, key -> availabilityFor(muted, perm, on, key) }
                .distinctUntilChanged()
                .collect { a ->
                    _availability.value = a
                    Log.i(TAG, "availability $a")
                    if (a != Availability.READY) onNotReady(muted = a == Availability.MUTED)
                    syncWake()
                }
        }
        scope.launch {
            settings.changes.collect { key ->
                when (key) {
                    KEY_ENABLED -> enabled.value = settings.getBoolean(KEY_ENABLED, true)
                    KEY_WAKE -> { wakeSetting.value = settings.getBoolean(KEY_WAKE, false); syncWake() }
                    KEY_WAKE_SENSITIVITY -> if (wakeJob != null) { stopWake(keepMic = false); syncWake() } // reload with the new threshold
                    KEY_VOICE -> selectVoice()
                    KEY_MODEL -> { history = emptyList(); historyModel = null }
                }
            }
        }
        scope.launch { combine(_state, _speaking) { s, sp -> s to sp }.collect { (s, sp) -> updateIsland(s, sp) } }
    }

    private fun availabilityFor(muted: Boolean, perm: Boolean, on: Boolean, key: Boolean) = when {
        muted -> Availability.MUTED
        !on -> Availability.DISABLED
        !key || agent() == null -> Availability.NO_KEY
        !perm -> Availability.NO_PERMISSION
        !voice.sttAvailable -> Availability.NO_MODELS
        else -> Availability.READY
    }

    fun refreshPermission() { permission.value = hasMicPermission() }

    /** Debug: use voice [id] for this run (the setting is unchanged). */
    fun debugVoice(id: String) { scope.launch(ttsThread) { voice.selectVoice(id) } }

    private fun selectVoice() {
        val id = settings.getString(KEY_VOICE, voice.defaultVoice)
        scope.launch(ttsThread) { voice.selectVoice(id) }
    }

    /**
     * Whether answers are spoken: `assistant.speak`, and in night mode only with
     * `assistant.speakAtNight` (the screen still shows the answer word by word).
     */
    fun shouldSpeak(): Boolean = voice.ttsAvailable && settings.getBoolean(KEY_SPEAK, speakDefault) &&
        (!system.state.value.nightActive || settings.getBoolean(KEY_SPEAK_AT_NIGHT, false))

    private fun speechRate() = settings.getFloat(KEY_SPEECH_RATE, 1f).coerceIn(0.5f, 2f)

    /** Mic off / disabled: stop capturing right away and end a turn that's listening. */
    private fun onNotReady(muted: Boolean) {
        if (!micTest || muted) { if (micTest) micTest = false; stopMic() }
        if (muted && (turnActive.value || _speaking.value)) stopAll()
        else if (_state.value.phase == Phase.LISTENING && demoJob?.isActive != true) stopAll()
    }

    fun useDemo() = demoData && agent() == null

    // ---------------- requests from the UI ----------------

    /** The droplet: start listening, or stop whatever is going on. */
    fun tapDroplet() {
        val s = _state.value
        val busy = turnActive.value || _speaking.value || demoJob?.isActive == true
        when {
            busy -> stopAll()
            useDemo() -> runDemo(DemoScripts.byId(s.demo).id)
            _availability.value == Availability.READY -> startTurn(null, byWake = false)
            else -> { // the screen explains why; show it as a short error too
                val code = when (_availability.value) {
                    Availability.NO_KEY -> "no-key"
                    Availability.NO_MODELS -> "no-models"
                    Availability.MUTED -> "muted"
                    else -> "mic"
                }
                if (_availability.value != Availability.MUTED) _state.value = AssistReducer.reduce(AssistState(), TurnEvent.Failed(code))
            }
        }
    }

    /** A suggestion chip: a scripted demo in demo mode, else the chip's question sent to Claude. */
    fun ask(text: String, demoId: String? = null) {
        if (useDemo()) { runDemo(demoId ?: "a"); return }
        if (agent() == null) return
        startTurn(text, byWake = false)
    }

    fun stopAll() {
        token++
        demoJob?.cancel(); demoJob = null
        turnJob?.cancel(); turnJob = null
        speech?.cancel(); speech = null
        voice.speaker().stop()
        audio = null
        if (!micTest && wakeJob == null) stopMic()
        _speaking.value = false
        turnActive.value = false
        ducker.release()
        _state.value = AssistState()
        syncWake()
    }

    /** The user touched the screen: don't auto-dismiss it. */
    fun userInteracted() { openedByWake = false; dismissJob?.cancel() }

    /** The screen left: an old answer shouldn't greet the next visit. */
    fun screenHidden() {
        if (micTest) setMicTest(false)
        val s = _state.value
        if ((s.phase == Phase.ANSWER || s.phase == Phase.ERROR) && !_speaking.value && !turnActive.value) _state.value = AssistState()
        openedByWake = false
    }

    // ---------------- a turn ----------------

    private fun emit(t: Int, e: TurnEvent) {
        if (t != token) return
        _state.update { AssistReducer.reduce(it, e) }
    }

    /**
     * [text] null = listen first; otherwise send [text] straight to Claude. [restore]: a follow-up
     * (listening again after Claude asked something); silence then puts this answer back quietly.
     */
    private fun startTurn(text: String?, byWake: Boolean, echoOnly: Boolean = false, restore: AssistState? = null) {
        demoJob?.cancel(); demoJob = null
        turnJob?.cancel()
        dismissJob?.cancel()
        speech?.cancel(); speech = null
        voice.speaker().stop()
        stopWake(keepMic = text == null)
        val t = ++token
        if (!byWake) openedByWake = false
        turnActive.value = true
        turnJob = scope.launch {
            var again = false
            try {
                again = runTurn(t, text, echoOnly, restore) && _availability.value == Availability.READY
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "turn failed: ${e.javaClass.simpleName}")
                emit(t, TurnEvent.Failed("unknown"))
            } finally {
                if (t == token) {
                    audio = null
                    turnActive.value = false
                    _speaking.value = false
                    ducker.release()
                    if (!micTest && !again) stopMic()
                    if (!again) syncWake()
                }
            }
            if (again && t == token) startTurn(null, byWake = openedByWake, restore = _state.value)
        }
    }

    /** Returns true when Claude asked a question and follow-up listening is on. */
    private suspend fun runTurn(t: Int, text: String?, echoOnly: Boolean, restore: AssistState?): Boolean {
        ducker.duck()
        val query: String
        if (text == null) {
            emit(t, TurnEvent.ListenStart)
            val ch = Channel<ByteArray>(LISTEN_BUFFER_CHUNKS, BufferOverflow.DROP_OLDEST)
            audio = ch
            if (!startMic()) { emit(t, TurnEvent.Failed("mic")); return false }
            val heard = withContext(voiceThread) { listen(t, ch) }
            audio = null
            if (!micTest) stopMic()
            query = Transcript.pretty(heard)
            if (query.isBlank() && restore != null) {
                // No reply to Claude's question: back to its answer, no apology.
                if (t == token) { _state.value = restore; scheduleDismiss() }
                return false
            }
            if (query.isBlank()) { emit(t, TurnEvent.Failed("no-speech")); finishTurn(t, speakText = null); return false }
        } else {
            _state.value = AssistState(phase = Phase.LISTENING)
            query = text
        }
        emit(t, TurnEvent.Heard(query))
        if (echoOnly) {
            // Transcription only, without Claude (measuring STT; a wake word without a key): the
            // transcript is shown, never spoken. TTS is measured silently with synthTest.
            emit(t, TurnEvent.Answer(query))
            finishTurn(t, query, ttsReady = false)
            return false
        }
        val a = agent() ?: run { emit(t, TurnEvent.Failed("no-key")); return false }
        // Load the voice while Claude works.
        val tts = if (shouldSpeak())
            scope.async(ttsThread) { runCatching { voice.speaker().prepare() }.isSuccess } else null
        val sp = tts?.let { Speech(t, it) }
        speech = sp
        val follow = now() - lastAnswerAt < CONVERSATION_KEEP_MS && historyModel == ClaudeModels.of(a.model())
        val answer = try {
            withTimeout(TURN_TIMEOUT_MS) {
                a.turn(query, if (follow) history else emptyList()) { ev ->
                    if (t == token) when (ev) {
                        is TurnEvent.AnswerDelta -> sp?.add(ev.text)
                        is TurnEvent.ToolStart -> sp?.toolStarted()
                        else -> {}
                    }
                    emit(t, ev)
                }
            }
        } catch (e: ClaudeException) {
            Log.w(TAG, "Claude: ${e.code} ${e.message}")
            emit(t, TurnEvent.Failed(e.code))
            if (e.code == "auth" || e.code == "refusal") history = emptyList()
            sp?.cancel()
            finishTurn(t, AssistReducer.friendly(e.code), ttsReady(tts))
            return false
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            emit(t, TurnEvent.Failed("timeout"))
            sp?.cancel()
            finishTurn(t, AssistReducer.friendly("timeout"), ttsReady(tts))
            return false
        }
        // A conversation that grew too long starts over (rather than cutting its start, which
        // would break the thinking blocks of models that think between tools).
        history = if (answer.history.size > MAX_HISTORY_MESSAGES) emptyList() else answer.history
        historyModel = answer.model
        lastAnswerAt = now()
        emit(t, TurnEvent.Answer(answer.answer.ifBlank { "Done." }, answer.cards))
        finishTurn(t, answer.answer, ttsReady(tts), sp)
        return t == token && answer.answer.trim().endsWith("?") && settings.getBoolean(KEY_FOLLOW_UP, true)
    }

    /**
     * One answer spoken while Claude still streams it: each finished sentence (the first one may be
     * a clause) goes to the voice as soon as the voice is loaded, so speech starts on the first
     * sentence and the next ones are synthesized while it plays.
     */
    private inner class Speech(private val t: Int, private val tts: kotlinx.coroutines.Deferred<Boolean>) {
        private val chunker = com.echobedside.feature.assistant.pipeline.SentenceChunker()
        private val pending = ArrayList<String>()
        private var utterance: com.echobedside.feature.assistant.voice.Utterance? = null
        @Volatile var cancelled = false; private set
        var sentAny = false; private set

        init { tts.invokeOnCompletion { synchronized(this) { if (!cancelled) flushPending() } } }

        @Synchronized fun add(delta: String) { for (p in chunker.add(delta)) send(p) }
        /** Claude called a tool: its unfinished words before the call aren't spoken. */
        @Synchronized fun toolStarted() = chunker.reset()

        private fun send(piece: String) {
            if (cancelled) return
            sentAny = true
            pending += SpeechText.forSpeech(piece)
            if (tts.isCompleted) flushPending()
        }

        private fun flushPending() {
            if (pending.isEmpty() || t != token) return
            val ready = runCatching { tts.getCompleted() }.getOrDefault(false)
            if (!ready) { pending.clear(); return }
            val u = utterance ?: voice.speaker().open(speechRate()).also {
                utterance = it
                ducker.duck()
                _speaking.value = true
            }
            for (p in pending) u.add(p)
            pending.clear()
        }

        /** The answer is complete: speaks the rest ([fallback] if nothing streamed) and waits. */
        suspend fun finish(fallback: String) {
            synchronized(this) {
                chunker.flush()?.let(::send)
                if (!sentAny) send(fallback)
                flushPending()
            }
            val u = synchronized(this) { utterance } ?: return
            withContext(ttsThread) { u.finish() }
        }

        @Synchronized fun cancel() {
            cancelled = true
            pending.clear()
            utterance?.cancel()
        }
    }

    /** Streams the buffered mic audio through STT until the speaker stops. Runs on the voice thread. */
    private suspend fun listen(t: Int, ch: Channel<ByteArray>): String {
        val session = try { voice.openStt() } catch (e: Throwable) {
            Log.w(TAG, "STT load failed: ${e.javaClass.simpleName}"); return ""
        }
        try {
            var last = ""
            val r = withTimeoutOrNull(LISTEN_MAX_MS) {
                for (chunk in ch) {
                    val text = session.accept(chunk)
                    if (text != last) { last = text; emit(t, TurnEvent.Partial(Transcript.pretty(text))) }
                    if (session.endpoint) break
                }
            }
            if (r == null) Log.i(TAG, "listen: max length reached")
            return session.finish()
        } finally {
            session.close()
            if (!settings.getBoolean(KEY_KEEP_STT, false)) voice.releaseStt()
        }
    }

    /** The voice, if it finished loading by now or within [TTS_WAIT_MS]; else the answer is only shown. */
    private suspend fun ttsReady(tts: kotlinx.coroutines.Deferred<Boolean>?): Boolean =
        tts != null && (withTimeoutOrNull(TTS_WAIT_MS) { tts.await() } ?: false.also { Log.i(TAG, "voice not ready: answer shown only") })

    private suspend fun finishTurn(t: Int, speakText: String?, ttsReady: Boolean = false, stream: Speech? = null) {
        if (stream != null && (!ttsReady || speakText == null || t != token)) stream.cancel()
        if (speakText != null && ttsReady && t == token) {
            ducker.duck()
            _speaking.value = true
            try {
                if (stream != null) stream.finish(speakText)
                else {
                    val rate = speechRate()
                    withContext(ttsThread) { voice.speaker().speak(SpeechText.forSpeech(speakText), rate) }
                }
            } finally {
                _speaking.value = false
                if (speech === stream) speech = null
            }
        } else if (speakText != null && t == token) {
            // No voice installed: the screen still reveals the answer word by word.
            _speaking.value = true
            try { delay(speakText.split(' ').size * DemoScripts.REVEAL_WORD_MS) } finally { _speaking.value = false }
        }
        if (voice.ttsAvailable && !settings.getBoolean(KEY_KEEP_TTS, false)) scope.launch(ttsThread) { voice.speaker().release() } // after a load still running, never waited for
        emit(t, TurnEvent.TurnEnd)
        scheduleDismiss()
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
            _state.value = AssistState()
        }
    }

    // ---------------- wake word ----------------

    /** Session override from the screen args (`wake=1`), for measuring. */
    fun setWakeOverride(on: Boolean?) { wakeOverride = on; syncWake() }
    private var wakeOverride: Boolean? = null

    private fun wakeWanted() = started &&
        (wakeOverride ?: wakeSetting.value) &&
        (_availability.value == Availability.READY ||
            // Measuring without a key (`wake=1`): a wake word then shows the transcript (STT only).
            (_availability.value == Availability.NO_KEY && wakeOverride == true && permission.value && voice.sttAvailable)) &&
        voice.wakeAvailable &&
        !turnActive.value && demoJob?.isActive != true && !micTest

    private fun syncWake() {
        val want = wakeWanted()
        if (want && wakeJob == null) {
            wakeJob = scope.launch { wakeLoop() }
        } else if (!want && wakeJob != null && !turnActive.value) {
            stopWake(keepMic = false)
        }
    }

    private fun stopWake(keepMic: Boolean) {
        val j = wakeJob ?: return
        wakeJob = null
        j.cancel()
        _wakeActive.value = false
        if (!keepMic && !turnActive.value && !micTest) { audio = null; stopMic() }
    }

    private suspend fun wakeLoop() {
        val ch = Channel<ByteArray>(WAKE_BUFFER_CHUNKS, BufferOverflow.DROP_OLDEST)
        audio = ch
        if (!startMic()) { wakeJob = null; return }
        _wakeActive.value = true
        val hit = try {
            withContext(voiceThread) {
                val threshold = AssistantSettings.threshold(settings)
                val session = try { voice.openWakeWord(threshold) } catch (e: Throwable) {
                    Log.w(TAG, "wake word load failed: ${e.javaClass.simpleName}"); null
                } ?: return@withContext false
                val gate = VoiceGate()
                var total = 0; var decoded = 0; var decodeNs = 0L; var lastLog = System.nanoTime()
                try {
                    for (chunk in ch) {
                        val out = gate.process(chunk, Pcm.dbfs(chunk))
                        for (b in out) {
                            val t0 = System.nanoTime()
                            val hit = session.accept(b) != null
                            decodeNs += System.nanoTime() - t0
                            if (hit) return@withContext true
                        }
                        decoded += out.size
                        total++
                        if (System.nanoTime() - lastLog > WAKE_STATS_NS) {
                            lastLog = System.nanoTime()
                            // Levels and counts only (never audio or words): how much the gate lets through.
                            Log.i(TAG, "wake: decoded $decoded/$total chunks, ${decodeNs / 1_000_000} ms decoding, " +
                                "floor ${gate.noiseFloorDb.toInt()} dB, gate ${if (gate.open) "open" else "shut"}")
                            total = 0; decoded = 0; decodeNs = 0
                        }
                    }
                    false
                } finally {
                    session.close()
                }
            }
        } finally {
            _wakeActive.value = false
        }
        if (wakeJob?.isActive != true) return
        wakeJob = null
        if (!hit) return
        Log.i(TAG, "wake word")
        onWakeWord()
    }

    private fun onWakeWord() {
        val nav = navigator()
        if (settings.getBoolean(KEY_OPEN_ON_WAKE, true) && nav != null && nav.current.value != SCREEN_ID) {
            nav.show(SCREEN_ID, null)
            startTurn(null, byWake = true, echoOnly = agent() == null)
            openedByWake = true
        } else {
            startTurn(null, byWake = true, echoOnly = agent() == null)
        }
    }

    // ---------------- mic ----------------

    private fun startMic(): Boolean {
        if (system.state.value.micMuted) return false
        return mic.running || mic.start(::onChunk)
    }

    private fun onChunk(chunk: ByteArray, db: Float) {
        val lvl = Pcm.level(db)
        micLevel += (lvl - micLevel) * if (lvl > micLevel) 0.5f else 0.15f
        audio?.trySend(chunk)
    }

    private fun stopMic() {
        mic.stop()
        micLevel = 0f
    }

    /** Capture only, no models (perf measurement, level meter check). */
    fun setMicTest(on: Boolean) {
        if (on == micTest) return
        if (on) {
            if (system.state.value.micMuted) return
            stopWake(keepMic = false)
            micTest = true
            refreshPermission()
            startMic()
        } else {
            micTest = false
            if (!turnActive.value) stopMic()
            syncWake()
        }
    }

    val micTesting: Boolean get() = micTest

    /** Listens and shows what it heard (STT only, no Claude, nothing spoken), for measuring speech recognition. */
    fun echoTest() {
        val a = _availability.value
        if (a == Availability.MUTED || a == Availability.DISABLED || !voice.sttAvailable || !hasMicPermission()) return
        startTurn(null, byWake = false, echoOnly = true)
    }

    /**
     * Synthesizes [text] with the on-device voice and plays nothing: logs load time, synthesis
     * time and the real-time factor (measuring TTS silently, e.g. at night). Frees the voice after.
     */
    fun synthTest(text: String, synthSaveTo: java.io.File? = null) {
        if (!voice.ttsAvailable) { Log.i(TAG, "synth: no voice installed"); return }
        scope.launch(ttsThread) {
            val sp = voice.speaker()
            val cold = sp.synthesize(SpeechText.forSpeech(text), speechRate())
            val warm = sp.synthesize(SpeechText.forSpeech(text), speechRate(), saveTo = synthSaveTo)
            if (cold == null || warm == null) { Log.w(TAG, "synth failed"); return@launch }
            Log.i(TAG, "synth: load ${cold.loadMs} ms, first ${cold.synthMs} ms, again ${warm.synthMs} ms " +
                "for ${warm.audioMs} ms of audio (RTF ${"%.2f".format(java.util.Locale.US, warm.rtf)}), ${text.length} chars")
            if (!settings.getBoolean(KEY_KEEP_TTS, false)) sp.release()
        }
    }

    /**
     * Debug: decodes a test WAV under the voice dir ([name], e.g. "bench/0.wav") through "stt" or
     * "kws" on its own thread and logs the timings. Silent; the wake word is stopped first.
     */
    fun benchTest(kind: String, file: java.io.File) {
        stopWake(keepMic = false); setWakeOverride(false)
        Thread({
            val r = try { voice.bench(kind, file) } catch (e: Throwable) { "bench failed: ${e.javaClass.simpleName}" }
            Log.i(TAG, r)
        }, "assistant-bench").start()
    }

    /** Speaks [text] with the on-device voice only (no Claude), for measuring TTS. Plays sound. */
    fun sayTest(text: String) {
        if (!voice.ttsAvailable) return
        stopAll()
        val t = ++token
        turnActive.value = true
        turnJob = scope.launch {
            try {
                _state.value = AssistState(phase = Phase.ANSWER, answer = text)
                ducker.duck()
                val tts = scope.async(ttsThread) { runCatching { voice.speaker().prepare() }.isSuccess }
                tts.await()
                // Fed word by word, like Claude's stream (about 30 words a second), so the voice
                // starts on the first piece while the rest still "arrives".
                val sp = Speech(t, tts).also { speech = it }
                text.split(' ').forEachIndexed { i, w -> sp.add(if (i == 0) w else " $w"); delay(SAY_WORD_MS) }
                finishTurn(t, text, ttsReady = true, stream = sp)
            } finally {
                if (t == token) { turnActive.value = false; ducker.release(); syncWake() }
            }
        }
    }

    // ---------------- demos ----------------

    /** Plays a scripted demo. [freezeAtMs] jumps to that point of the script and holds there (screenshots). */
    fun runDemo(id: String, freezeAtMs: Long? = null) {
        val demo = DemoScripts.byId(id)
        stopAll()
        stopWake(keepMic = false)
        val t = token
        val timeline = DemoScripts.timeline(demo)
        demoJob = scope.launch {
            var at0 = 0L
            for ((at, ev) in timeline) {
                if (freezeAtMs != null && at > freezeAtMs) break
                if (freezeAtMs == null && at > at0) { delay(at - at0); at0 = at }
                emit(t, ev)
                if (ev is TurnEvent.ListenStart) _state.update { it.copy(demo = demo.id) }
                if (ev is TurnEvent.Answer) _speaking.value = freezeAtMs == null
                if (ev is TurnEvent.TurnEnd) _speaking.value = false
            }
            if (freezeAtMs == null) scheduleDismiss()
        }
        demoJob?.invokeOnCompletion { if (t == token) { _speaking.value = false; syncWake() } }
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
        AssistantPhase.SPEAKING -> {
            val l = if (_state.value.demo == null) voice.speaker().level else 0f
            if (l > 0f) 0.35f + 0.6f * l else (0.5 + 0.3 * abs(sin(t * 9))).toFloat()
        }
        null -> if (micTest) micLevel else 0.25f
    }

    companion object {
        const val SCREEN_ID = "assistant"
        const val ACTIVITY_ID = "assistant"
        const val KEY_ENABLED = "assistant.enabled"
        const val KEY_WAKE = "assistant.wakeWord"
        const val KEY_OPEN_ON_WAKE = "assistant.openOnWake"
        const val KEY_SPEAK = "assistant.speak"
        const val KEY_KEEP_STT = "assistant.keepSttLoaded"
        const val KEY_KEEP_TTS = "assistant.keepTtsLoaded"
        const val KEY_DISMISS_S = "assistant.dismissSec"
        const val KEY_NAME = "assistant.name"
        const val KEY_WAKE_SENSITIVITY = "assistant.wakeSensitivity"
        const val KEY_MODEL = "assistant.model"
        const val KEY_FOLLOW_UP = "assistant.followUp"
        const val KEY_SPEAK_AT_NIGHT = "assistant.speakAtNight"
        const val KEY_VOICE = "assistant.voice"
        const val KEY_SPEECH_RATE = "assistant.speechRate"
        /** Off: on the Echo Show 5 the Piper voice loads in ~35 s and synthesizes at 3.4× real time (status file). */
        const val SPEAK_DEFAULT = false
        const val TTS_WAIT_MS = 1500L
        const val SAY_WORD_MS = 33L
        const val DISMISS_S = 12
        const val CONVERSATION_KEEP_MS = 60_000L
        const val TURN_TIMEOUT_MS = 45_000L
        const val LISTEN_MAX_MS = 20_000L
        const val MAX_HISTORY_MESSAGES = 24
        /** 16 s of 40 ms chunks: covers the STT model load. */
        const val LISTEN_BUFFER_CHUNKS = 400
        const val WAKE_BUFFER_CHUNKS = 50
        /** Wake word stats in the log every 10 s. */
        const val WAKE_STATS_NS = 10_000_000_000L
    }
}
