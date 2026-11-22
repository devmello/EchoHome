package com.echobedside.feature.assistant.voice

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.echobedside.feature.assistant.audio.AudioFocusDucker
import com.echobedside.feature.assistant.audio.TAG
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * sherpa-onnx (Apache-2.0) on the CPU, models loaded from files pushed to the app's external
 * files dir (`/sdcard/Android/data/com.echobedside/files/voice/{kws,stt,tts}`, see
 * docs/assistant-setup.md):
 *  - wake word: zipformer keyword spotter (gigaspeech 3.3M, int8), keywords.txt
 *  - STT: streaming zipformer en 20M (int8), with sherpa's endpoint rules
 *  - TTS: Piper VITS en_US amy low (int8, 16 kHz), played with a streaming AudioTrack
 * Without the native libs or the files, every `*Available` is false and the assistant says so.
 * Never logs transcripts.
 */
class SherpaVoice(private val root: File, private val threads: Int = 2) : VoiceStack {
    private val kwsDir = File(root, "kws")
    private val sttDir = File(root, "stt")
    private val ttsRoot = File(root, "tts")
    /** The selected voice: "amy" is the flat tts/ dir, any other id a tts/<id>/ subdir. */
    @Volatile private var voiceId = DEFAULT_VOICE
    private val ttsDir get() = if (voiceId == DEFAULT_VOICE) ttsRoot else File(ttsRoot, voiceId)
    private val ttsModel get() = if (voiceId == DEFAULT_VOICE) TTS_MODEL
        else ttsDir.listFiles { f -> f.name.endsWith(".onnx") }?.minOfOrNull { it.name } ?: TTS_MODEL

    private val native: Boolean by lazy {
        try { System.loadLibrary("sherpa-onnx-jni"); true }
        catch (e: Throwable) { Log.w(TAG, "sherpa-onnx native libs missing (run tools/fetch-voice.sh): ${e.javaClass.simpleName}"); false }
    }

    private fun has(dir: File, vararg names: String) = names.all { File(dir, it).isFile }

    override val sttAvailable get() = native && has(sttDir, STT_ENC, STT_DEC, STT_JOIN, "tokens.txt")
    override val wakeAvailable get() = native && has(kwsDir, KWS_ENC, KWS_DEC, KWS_JOIN, "tokens.txt", "keywords.txt")
    /** sherpa-onnx 2.x has no espeak-ng: Piper voices read phonemes from an English lexicon. */
    private val lexicon get() = File(ttsDir, LEXICON).takeIf { it.isFile } ?: File(ttsRoot, LEXICON)

    override val ttsAvailable get() = native && has(ttsDir, ttsModel, "tokens.txt") && lexicon.isFile

    override val defaultVoice get() = DEFAULT_VOICE

    /** amy (flat tts/ dir) plus every tts/<id>/ with a .onnx voice and tokens.txt (e.g. a Piper voice). */
    override fun voices(): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        if (has(ttsRoot, TTS_MODEL, "tokens.txt")) list += DEFAULT_VOICE to "Amy (US English)"
        ttsRoot.listFiles { f -> f.isDirectory && File(f, "tokens.txt").isFile && f.listFiles { x -> x.name.endsWith(".onnx") }?.isNotEmpty() == true }
            ?.sortedBy { it.name }
            ?.forEach { d -> list += d.name to d.name.replace('-', ' ').replace('_', ' ') }
        return list
    }

    override fun selectVoice(id: String) {
        val v = if (id == DEFAULT_VOICE || File(ttsRoot, id).isDirectory) id else DEFAULT_VOICE
        if (v == voiceId) return
        voiceId = v
        speaker.release()
    }

    override fun describe() = "native=$native stt=$sttAvailable wake=$wakeAvailable tts=$ttsAvailable root=$root"

    // ---------------- STT ----------------

    private var recognizer: OnlineRecognizer? = null

    private fun recognizer(): OnlineRecognizer = recognizer ?: run {
        val t0 = SystemClock.elapsedRealtime()
        OnlineRecognizer(null, OnlineRecognizerConfig(
            featConfig = FeatureConfig(16000, 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(f(sttDir, STT_ENC), f(sttDir, STT_DEC), f(sttDir, STT_JOIN)),
                tokens = f(sttDir, "tokens.txt"),
                numThreads = threads,
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 4.0f, 0f),   // nothing said: give up after 4 s of silence
                rule2 = EndpointRule(true, 0.9f, 0f),    // after speech: 0.9 s of silence ends it
                rule3 = EndpointRule(false, 0f, 15f),    // at most 15 s
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )).also {
            recognizer = it
            Log.i(TAG, "STT loaded in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    override fun openStt(): SttSession {
        val r = recognizer()
        val stream = r.createStream()
        return object : SttSession {
            private val buf = FloatArray(640)
            private var text = ""
            override var endpoint = false; private set

            override fun accept(pcm: ByteArray): String {
                val samples = if (pcm.size / 2 == buf.size) pcm16ToFloat(pcm, buf) else pcm16ToFloat(pcm)
                stream.acceptWaveform(samples, 16000)
                while (r.isReady(stream)) r.decode(stream)
                text = r.getResult(stream).text
                if (r.isEndpoint(stream)) endpoint = true
                return text
            }

            override fun finish(): String {
                stream.inputFinished()
                while (r.isReady(stream)) r.decode(stream)
                text = r.getResult(stream).text
                return text
            }

            override fun close() = stream.release()
        }
    }

    override fun releaseStt() {
        recognizer?.release()
        recognizer = null
    }

    // ---------------- wake word ----------------

    @Volatile private var kwsModelType = "zipformer2"
    @Volatile private var kwsEncoder = KWS_ENC
    @Volatile private var kwsKeywords = "keywords.txt"

    override fun openWakeWord(threshold: Float): WakeWordSession {
        val t0 = SystemClock.elapsedRealtime()
        // The sensitivity setting's threshold goes into a copy of keywords.txt (each line's `#t`).
        // (Per-stream keywords via createStream(keywords) cost a whole core on the Echo.)
        val keywordsFile = try {
            val lines = Keywords.withThreshold(File(kwsDir, kwsKeywords).readLines(), threshold).split('/')
            File(root, ACTIVE_KEYWORDS).apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        } catch (e: Exception) { File(kwsDir, "keywords.txt") }
        val kws = KeywordSpotter(null, KeywordSpotterConfig(
            featConfig = FeatureConfig(16000, 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(f(kwsDir, kwsEncoder), f(kwsDir, KWS_DEC), f(kwsDir, KWS_JOIN)),
                tokens = f(kwsDir, "tokens.txt"),
                numThreads = 1,
                modelType = kwsModelType,
            ),
            maxActivePaths = 4,
            keywordsFile = keywordsFile.absolutePath,
            keywordsScore = 2.0f,
            keywordsThreshold = threshold,
            numTrailingBlanks = 1,
        ))
        Log.i(TAG, "wake word loaded in ${SystemClock.elapsedRealtime() - t0} ms (threshold $threshold)")
        val stream = kws.createStream()
        return object : WakeWordSession {
            private val buf = FloatArray(640)
            override fun accept(pcm: ByteArray): String? {
                val samples = if (pcm.size / 2 == buf.size) pcm16ToFloat(pcm, buf) else pcm16ToFloat(pcm)
                val t0 = SystemClock.elapsedRealtime()
                stream.acceptWaveform(samples, 16000)
                var hit: String? = null
                var n = 0
                // Bounded: a chunk is 40 ms, a few decodes at most.
                while (n < MAX_DECODES_PER_CHUNK && kws.isReady(stream)) {
                    kws.decode(stream)
                    n++
                    val k = kws.getResult(stream).keyword
                    if (k.isNotEmpty()) { hit = k; kws.reset(stream) }
                }
                if (n >= MAX_DECODES_PER_CHUNK) Log.w(TAG, "wake decode: $n steps in ${SystemClock.elapsedRealtime() - t0} ms")
                return hit
            }
            override fun reset() = kws.reset(stream)
            override fun close() { stream.release(); kws.release() }
        }
    }

    /**
     * Debug: runs a 16 kHz mono PCM16 WAV (e.g. a model's own test_wavs) through STT ("stt") or the
     * wake word ("kws") as fast as it decodes, and returns timings (never the words). Silent.
     */
    override fun bench(kind: String, wav: File): String {
        val pcm = wav.readBytes().let { it.copyOfRange(minOf(44, it.size), it.size) }
        val audioMs = pcm.size / 32L
        val chunk = 1280
        val t0 = SystemClock.elapsedRealtime()
        var words = 0
        var hits = 0
        if (kind.startsWith("kws")) {
            // Debug variants: "kws,<modelType>,<encoder file>".
            kind.split(",").let { p -> if (p.size >= 2) kwsModelType = p[1]; if (p.size >= 3 && p[2].isNotEmpty()) kwsEncoder = p[2]; if (p.size >= 4) kwsKeywords = p[3] }
            val s = openWakeWord(DEFAULT_WAKE_THRESHOLD)
            val tl = SystemClock.elapsedRealtime()
            try {
                var off = 0
                while (off < pcm.size) { if (s.accept(pcm.copyOfRange(off, minOf(pcm.size, off + chunk))) != null) hits++; off += chunk }
            } finally { s.close() }
            val ms = SystemClock.elapsedRealtime() - tl
            return "kws: load ${tl - t0} ms, ${ms} ms for $audioMs ms of audio (RTF ${"%.2f".format(java.util.Locale.US, ms.toFloat() / audioMs)}), $hits hits"
        }
        val s = openStt()
        val tl = SystemClock.elapsedRealtime()
        try {
            var off = 0
            while (off < pcm.size) { s.accept(pcm.copyOfRange(off, minOf(pcm.size, off + chunk))); off += chunk }
            words = s.finish().split(' ').count { it.isNotBlank() }
        } finally { s.close(); releaseStt() }
        val ms = SystemClock.elapsedRealtime() - tl
        return "stt: load ${tl - t0} ms, ${ms} ms for $audioMs ms of audio (RTF ${"%.2f".format(java.util.Locale.US, ms.toFloat() / audioMs)}), $words words"
    }

    // ---------------- TTS ----------------

    private val speaker by lazy { PiperSpeaker() }
    override fun speaker(): Speaker = speaker

    private inner class PiperSpeaker : Speaker {
        private var tts: OfflineTts? = null
        /** The voice model [tts] was loaded from. */
        private var loadedFrom: String? = null
        private var lastLoadMs = 0L
        @Volatile private var stopped = false
        @Volatile private var track: AudioTrack? = null
        /** RMS per 20 ms window of what's been written, indexed by playback position. */
        private var rms = FloatArray(0)
        @Volatile private var rmsCount = 0
        @Volatile private var window = 320

        override fun prepare() {
            val from = f(ttsDir, ttsModel)
            if (tts != null && loadedFrom == from) return
            release()
            val t0 = SystemClock.elapsedRealtime()
            loadedFrom = from
            tts = OfflineTts(null, OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = from, tokens = f(ttsDir, "tokens.txt"),
                        lexicon = lexicon.absolutePath,
                    ),
                    numThreads = threads,
                ),
                maxNumSentences = 1,
            ))
            lastLoadMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "TTS loaded in $lastLoadMs ms")
        }

        override fun synthesize(text: String, speed: Float, saveTo: File?): SynthStats? {
            val wasLoaded = tts != null && loadedFrom == f(ttsDir, ttsModel)
            try { prepare() } catch (e: Throwable) { Log.w(TAG, "TTS load failed: ${e.javaClass.simpleName}"); return null }
            val engine = tts ?: return null
            val t0 = SystemClock.elapsedRealtime()
            var samples = 0L
            val keep = if (saveTo != null) ArrayList<FloatArray>() else null
            return try {
                // Nothing is played: the samples are only counted.
                engine.generateWithCallback(text, 0, speed, SampleCallback { s -> samples += s.size; keep?.add(s.copyOf()); 1 })
                if (saveTo != null && keep != null) {
                    val all = FloatArray(keep.sumOf { it.size }); var o = 0
                    for (k in keep) { k.copyInto(all, o); o += k.size }
                    com.k2fsa.sherpa.onnx.GeneratedAudio(all, engine.sampleRate()).save(saveTo.absolutePath)
                }
                SynthStats(if (wasLoaded) 0 else lastLoadMs, SystemClock.elapsedRealtime() - t0, samples * 1000 / engine.sampleRate())
            } catch (e: Throwable) {
                Log.w(TAG, "TTS failed: ${e.javaClass.simpleName}"); null
            }
        }

        override fun speak(text: String, speed: Float): Boolean {
            stopped = false
            try { prepare() } catch (e: Throwable) { Log.w(TAG, "TTS load failed: ${e.javaClass.simpleName}"); return false }
            val engine = tts ?: return false
            val rate = engine.sampleRate()
            window = rate / 50
            rms = FloatArray(64); rmsCount = 0
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioFocusDucker.SPEECH_ATTRIBUTES)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(maxOf(min, rate * 4 / 2)) // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            var written = 0L
            try {
                t.play()
                engine.generateWithCallback(text, 0, speed, SampleCallback { samples ->
                    if (stopped) return@SampleCallback 0
                    addRms(samples)
                    var off = 0
                    while (off < samples.size && !stopped) {
                        val n = t.write(samples, off, samples.size - off, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                    }
                    written += samples.size
                    if (stopped) 0 else 1
                })
                // Let the buffer drain.
                val deadline = SystemClock.elapsedRealtime() + 2000 + written * 1000 / rate
                while (!stopped && t.playbackHeadPosition < written && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "TTS failed: ${e.javaClass.simpleName}")
                return false
            } finally {
                track = null
                try { t.stop() } catch (_: Exception) {}
                t.release()
            }
        }

        private fun addRms(s: FloatArray) {
            val w = window
            var i = 0
            while (i < s.size) {
                val end = minOf(s.size, i + w)
                var sum = 0.0
                for (j in i until end) sum += s[j] * s[j]
                if (rmsCount == rms.size) rms = rms.copyOf(rms.size * 2)
                rms[rmsCount] = sqrt(sum / (end - i)).toFloat()
                rmsCount++
                i = end
            }
        }

        override val level: Float get() {
            val t = track ?: return 0f
            val idx = (t.playbackHeadPosition / window)
            val r = rms
            if (idx < 0 || idx >= rmsCount || idx >= r.size) return 0f
            // Speech RMS is ~0.02..0.25 → 0..1.
            return ((r[idx] - 0.01f) / 0.2f).coerceIn(0f, 1f)
        }

        override fun stop() {
            stopped = true
            try { track?.pause(); track?.flush() } catch (_: Exception) {}
        }

        override fun release() {
            tts?.free()
            tts = null
            loadedFrom = null
        }
    }

    private fun f(dir: File, name: String) = File(dir, name).absolutePath

    companion object {
        const val STT_ENC = "encoder-epoch-99-avg-1.int8.onnx"
        const val STT_DEC = "decoder-epoch-99-avg-1.onnx"
        const val STT_JOIN = "joiner-epoch-99-avg-1.int8.onnx"
        const val KWS_ENC = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val KWS_DEC = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val KWS_JOIN = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val TTS_MODEL = "en_US-amy-low.onnx"
        const val DEFAULT_VOICE = "amy"
        const val ACTIVE_KEYWORDS = "keywords-active.txt"
        /** sherpa-onnx tts-models release `lexicon-en-us.txt` (word to espeak phones). */
        const val LEXICON = "lexicon-en-us.txt"
        const val MAX_DECODES_PER_CHUNK = 8
    }
}
