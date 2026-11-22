package com.echobedside.feature.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The mic, abstracted so the engine runs in JVM tests. */
interface MicSource {
    val running: Boolean
    /** Starts capture; [onChunk] runs on the audio thread for every 40 ms chunk with its dBFS. */
    fun start(onChunk: (chunk: ByteArray, db: Float) -> Unit): Boolean
    /** Stops capture and releases the mic. Returns once no more chunks will be delivered. */
    fun stop()
}

/** Speaks a TTS answer; suspends until it finished (or was stopped). */
interface SpeechPlayer {
    suspend fun play(audio: ByteArray, mimeType: String?)
    fun stop()
}

/** Ducks other audio while the user talks and while the answer plays. */
interface Ducker {
    fun duck()
    fun release()
}

const val TAG = "EchoAssistant"

/**
 * AudioRecord at 16 kHz mono 16-bit (the Echo's "Built-In Mic" is natively 16 kHz, so no
 * resampling anywhere). VOICE_RECOGNITION gives unprocessed audio, which is what the server's wake
 * word and STT models want; falls back to MIC.
 */
class AndroidMic : MicSource {
    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var stopping = false
    override val running: Boolean get() = thread != null

    @Synchronized
    override fun start(onChunk: (ByteArray, Float) -> Unit): Boolean {
        if (thread != null) return true
        val min = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, Pcm.CHUNK_BYTES * 8)
        val rec = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { src ->
            try {
                AudioRecord(src, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: null
            } catch (e: Exception) { Log.w(TAG, "AudioRecord source $src failed: ${e.message}"); null }
        } ?: return false
        try { rec.startRecording() } catch (e: Exception) { Log.w(TAG, "startRecording failed", e); rec.release(); return false }
        record = rec
        stopping = false
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(Pcm.CHUNK_BYTES)
            val chunker = AudioChunker { c -> onChunk(c, Pcm.dbfs(c)) }
            while (!stopping) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { Log.w(TAG, "AudioRecord.read error $n"); break }
                if (n > 0 && !stopping) chunker.write(buf, 0, n)
            }
        }, "assistant-mic").also { it.start() }
        Log.i(TAG, "mic started (buffer $size B)")
        return true
    }

    @Synchronized
    override fun stop() {
        val t = thread ?: return
        stopping = true
        try { record?.stop() } catch (_: Exception) {}
        t.join(500)
        record?.release()
        record = null
        thread = null
        Log.i(TAG, "mic stopped")
    }
}

class AudioFocusDucker(context: Context) : Ducker {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setOnAudioFocusChangeListener { }
        .build()
    private var held = false

    override fun duck() { if (!held) { am.requestAudioFocus(request); held = true } }
    override fun release() { if (held) { am.abandonAudioFocusRequest(request); held = false } }

    companion object {
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** MediaPlayer over the fetched bytes (mp3/wav/flac from Piper), no temp files. */
class MediaSpeechPlayer : SpeechPlayer {
    private var player: MediaPlayer? = null
    private var done: (() -> Unit)? = null

    override suspend fun play(audio: ByteArray, mimeType: String?) = suspendCancellableCoroutine { cont ->
        stop()
        val mp = MediaPlayer()
        player = mp
        val finish = {
            if (player === mp) { player = null; done = null }
            mp.release()
            if (cont.isActive) cont.resume(Unit)
        }
        done = finish
        try {
            mp.setAudioAttributes(AudioFocusDucker.SPEECH_ATTRIBUTES)
            mp.setDataSource(BytesDataSource(audio))
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { finish() }
            mp.setOnErrorListener { _, what, extra -> Log.w(TAG, "TTS playback error $what/$extra"); finish(); true }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "TTS playback failed", e)
            finish()
        }
        cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { stop() } }
    }

    override fun stop() {
        val mp = player ?: return
        try { mp.stop() } catch (_: Exception) {}
        done?.invoke() ?: mp.release()
        player = null
        done = null
    }

    private class BytesDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }
}
