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
    /** Synthesizes and plays [text]; returns false when it couldn't. */
    fun speak(text: String): Boolean
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
    fun openWakeWord(): WakeWordSession
    fun speaker(): Speaker
    /** Frees the STT model (it's reloaded per turn). */
    fun releaseStt()
    /** What's missing, for the status file / logs. */
    fun describe(): String
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
