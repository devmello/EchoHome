package com.echobedside.feature.assistant.voice

/**
 * The on-device voice parts, behind interfaces so the engine runs in JVM tests.
 * Real implementations: [SherpaVoice] (sherpa-onnx). All calls happen on one voice thread.
 */

/** Streaming speech-to-text for one utterance. */
interface SttSession {
    /** Feeds 16 kHz mono PCM16. Returns the transcript so far. */
    fun accept(pcm: ByteArray): String
    /** True once the speaker stopped (endpoint rules). */
    val endpoint: Boolean
    /** Flushes and returns the final transcript. */
    fun finish(): String
    fun close()
}

/** Keyword spotter (wake word) over a continuous stream. */
interface WakeWordSession {
    /** Feeds PCM16; returns the keyword when it was just spotted. */
    fun accept(pcm: ByteArray): String?
    fun reset()
    fun close()
}

/** Speech synthesis + playback. [speak] blocks the voice thread until done or [stop]. */
interface Speaker {
    /** Loads the model ahead of time (while Claude is working). */
    fun prepare()
    /** Synthesizes and plays [text] at [speed] (1 = normal); returns false when it couldn't. */
    fun speak(text: String, speed: Float = 1f): Boolean
    /** Synthesizes [text] without playing it (silent measurement); null when it couldn't. */
    fun synthesize(text: String, speed: Float = 1f, saveTo: java.io.File? = null): SynthStats? = null
    /** 0..1 level of what's playing right now (any thread). */
    val level: Float
    /** Stops playback (any thread). */
    fun stop()
    /** Frees the model. */
    fun release()
}

/** Which models are installed and loadable. */
interface VoiceStack {
    val sttAvailable: Boolean
    val wakeAvailable: Boolean
    val ttsAvailable: Boolean
    fun openStt(): SttSession
    /** [threshold]: the spotter's trigger threshold (lower = triggers more easily). */
    fun openWakeWord(threshold: Float = DEFAULT_WAKE_THRESHOLD): WakeWordSession
    fun speaker(): Speaker
    /** Frees the STT model (it's reloaded per turn). */
    fun releaseStt()
    /** What's missing, for the status file / logs. */
    fun describe(): String
    /** Installed voices (id to label). */
    fun voices(): List<Pair<String, String>> = emptyList()
    val defaultVoice: String get() = "amy"
    /** Switches the TTS voice (the loaded one is freed; the next answer loads the new one). */
    fun selectVoice(id: String) {}
    /** Debug: decode timings for a test WAV (see SherpaVoice.bench). */
    fun bench(kind: String, wav: java.io.File): String = "bench unsupported"
}

const val DEFAULT_WAKE_THRESHOLD = 0.25f

/** One silent synthesis: model load (0 if it was loaded), synthesis time and the audio's length. */
data class SynthStats(val loadMs: Long, val synthMs: Long, val audioMs: Long) {
    /** Real-time factor: below 1 means faster than it plays. */
    val rtf: Float get() = if (audioMs > 0) synthMs.toFloat() / audioMs else 0f
}

/**
 * keywords.txt lines ("▁HE Y ▁NO V A :2.0 #0.25 @HEY_NOVA") with each `#threshold` replaced (or
 * added) for the sensitivity setting, joined with "/" (SherpaVoice writes them back one per line).
 */
object Keywords {
    private val THRESHOLD = Regex("""\s#[0-9.]+""")

    fun withThreshold(lines: List<String>, threshold: Float): String {
        val t = " #" + String.format(java.util.Locale.US, "%.2f", threshold)
        return lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("/") { line ->
                val bare = line.replace(THRESHOLD, "")
                val at = bare.indexOf(" @")
                if (at >= 0) bare.substring(0, at) + t + bare.substring(at) else bare + t
            }
    }
}

/** PCM16 little-endian → float [-1, 1]. */
fun pcm16ToFloat(pcm: ByteArray, out: FloatArray = FloatArray(pcm.size / 2)): FloatArray {
    for (i in out.indices) {
        val lo = pcm[2 * i].toInt() and 0xFF
        val hi = pcm[2 * i + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort() / 32768f
    }
    return out
}

/**
 * The TTS sample callback for sherpa-onnx. Its JNI code calls `invoke([F)Ljava/lang/Integer;` on
 * the object it's given. A plain Kotlin lambda compiles (invokedynamic + D8) to a class with only
 * the generic `invoke(Object)`, and the call fails (NoSuchMethodError, then a CheckJNI abort).
 * Kept in release by consumer-rules.pro.
 */
class SampleCallback(private val f: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = f(samples)
}
