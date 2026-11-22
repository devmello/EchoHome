package com.echobedside.feature.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Pulls s16 PCM from the native ring and writes it to an [AudioTrack] (music stream) on its own
 * thread, one per audio stream. The thread blocks in native code while nothing arrives (2 wakeups
 * a second while paused) and ends with the stream, so an idle receiver has no thread at all.
 *
 * @param onFlowing PCM started (true) or stopped (false, nothing for [IDLE_MS]) flowing. Called on
 *   the player thread.
 */
internal class AudioPlayer(
    private val handle: Long,
    private val sampleRate: Int,
    private val channels: Int,
    private val onFlowing: (Boolean) -> Unit,
) {
    @Volatile private var running = true
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1f
    /** Drop audio instead of playing it (local pause, or audio focus lost). */
    @Volatile var muted = false

    private val thread = Thread(::run, "airplay-audio")

    fun start() = thread.start()

    /** Ends the thread; the stream's -1 ends it too. */
    fun stop() {
        running = false
        NativeBridge.nativeWakeReader(handle)
        try { track?.pause() } catch (_: IllegalStateException) {} // releases a blocking write()
    }

    fun join(timeoutMs: Long) = thread.join(timeoutMs)

    fun flush() {
        flushRequested = true
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
        track?.setVolume(gain)
    }

    @Volatile private var track: AudioTrack? = null

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // ~200 ms in the AudioTrack: enough to ride out Wi-Fi jitter on top of the native ring.
        val bytes = maxOf(minBytes * 2, sampleRate * channels * 2 / 5)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack ${sampleRate}Hz/${channels}ch failed", e)
            return
        }
        track = t
        t.setVolume(gain)
        Log.i(TAG, "audio ${sampleRate}Hz ${channels}ch, track buffer ${t.bufferSizeInFrames} frames")

        val buf = ShortArray(4096)
        var flowing = false
        var idleMs = 0
        try {
            while (running) {
                if (flushRequested) {
                    flushRequested = false
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                    t.flush()
                }
                val n = NativeBridge.nativeReadPcm(handle, buf, POLL_MS)
                if (n < 0) break
                if (n == 0) {
                    idleMs += POLL_MS
                    if (flowing && idleMs >= IDLE_MS) {
                        flowing = false
                        t.pause()
                        onFlowing(false)
                    }
                    continue
                }
                idleMs = 0
                if (!flowing) {
                    flowing = true
                    onFlowing(true)
                }
                if (muted) {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) { t.pause(); t.flush() }
                    continue
                }
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                var off = 0
                while (off < n && running) {
                    val w = t.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio thread failed", e)
        } finally {
            track = null
            try { t.stop() } catch (_: IllegalStateException) {}
            t.release()
            if (flowing) onFlowing(false)
            Log.i(TAG, "audio thread ended")
        }
    }

    companion object {
        private const val TAG = "EchoAirPlay"
        const val POLL_MS = 500
        /** No PCM for this long = paused (AirPlay 2 pauses by stopping the flow, without a flush). */
        const val IDLE_MS = 1_000
    }
}
