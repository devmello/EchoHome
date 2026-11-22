package com.echobedside.feature.assistant.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** 16 kHz, 16-bit little-endian mono PCM: what the Echo's mic gives natively and what HA expects. */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SAMPLE = 2
    /** 40 ms per WebSocket frame (25 frames/s): small enough for low latency, few enough frames. */
    const val CHUNK_MS = 40
    const val CHUNK_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MS / 1000 // 1280

    fun msToBytes(ms: Int) = SAMPLE_RATE * BYTES_PER_SAMPLE * ms / 1000

    /** RMS level of little-endian 16-bit samples in dBFS (-96 for silence). */
    fun dbfs(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Float {
        val n = length / 2
        if (n == 0) return -96f
        var sum = 0.0
        var i = offset
        val end = offset + n * 2
        while (i < end) {
            val s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble()
            i += 2
        }
        val rms = sqrt(sum / n) / 32768.0
        return if (rms <= 1.6e-5) -96f else (20 * log10(rms)).toFloat()
    }

    /** dBFS → 0..1 for the droplet and the island bars: -60 dB (room) → 0, -12 dB (close voice) → 1. */
    fun level(db: Float): Float = ((db + 60f) / 48f).coerceIn(0f, 1f)
}

/**
 * Re-slices whatever AudioRecord.read returns into fixed [chunkBytes] frames. One reusable buffer;
 * each emitted chunk is a fresh array (it's handed to the WebSocket queue).
 */
class AudioChunker(private val chunkBytes: Int = Pcm.CHUNK_BYTES, private val emit: (ByteArray) -> Unit) {
    private val buf = ByteArray(chunkBytes)
    private var fill = 0

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var off = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, chunkBytes - fill)
            System.arraycopy(data, off, buf, fill, n)
            fill += n; off += n; left -= n
            if (fill == chunkBytes) {
                emit(buf.copyOf())
                fill = 0
            }
        }
    }

    /** Emits the partial chunk, if any (end of stream). */
    fun flush() {
        if (fill > 0) emit(buf.copyOf(fill))
        fill = 0
    }

    fun reset() { fill = 0 }
}

/**
 * On-device voice activity gate for the wake-word stage (HA recommends it: audio streams only when
 * someone speaks, saving network and server CPU). Keeps [prerollMs] of audio so the start of
 * "Hey Nova" isn't lost, opens when a chunk is [thresholdDb] above the tracked noise floor, and
 * stays open [holdMs] after the last loud chunk. When disabled ([enabled] = false) everything passes.
 *
 * Not thread-safe: call from the audio thread.
 */
class VoiceGate(
    prerollMs: Int = 600,
    private val holdMs: Int = 1500,
    private val thresholdDb: Float = 9f,
    /** Never treat anything quieter than this as speech, however quiet the room. */
    private val minSpeechDb: Float = -52f,
    private val chunkMs: Int = Pcm.CHUNK_MS,
) {
    var enabled = true
    var noiseFloorDb = -70f; private set
    var open = false; private set

    private val preroll = ArrayDeque<ByteArray>()
    private val prerollChunks = max(1, prerollMs / chunkMs)
    private var quietMs = 0

    /** Returns the chunks to send now (possibly the pre-roll plus this one, or nothing). */
    fun process(chunk: ByteArray, db: Float): List<ByteArray> {
        if (!enabled) return listOf(chunk)
        val loud = db > max(noiseFloorDb + thresholdDb, minSpeechDb)
        // Noise floor: falls fast, rises slowly, and only learns from non-speech.
        if (!loud) noiseFloorDb = if (db < noiseFloorDb) noiseFloorDb + (db - noiseFloorDb) * 0.3f else noiseFloorDb + (db - noiseFloorDb) * 0.02f
        if (open) {
            if (loud) quietMs = 0 else quietMs += chunkMs
            if (quietMs > holdMs) {
                open = false
                quietMs = 0
                preroll.clear()
                preroll.addLast(chunk)
                return emptyList()
            }
            return listOf(chunk)
        }
        if (loud) {
            open = true
            quietMs = 0
            val out = ArrayList<ByteArray>(preroll.size + 1)
            out.addAll(preroll)
            out.add(chunk)
            preroll.clear()
            return out
        }
        preroll.addLast(chunk)
        while (preroll.size > prerollChunks) preroll.removeFirst()
        return emptyList()
    }

    fun reset() { open = false; quietMs = 0; preroll.clear() }
}
