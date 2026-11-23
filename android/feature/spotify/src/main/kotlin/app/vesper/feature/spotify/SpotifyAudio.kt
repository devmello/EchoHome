package app.vesper.feature.spotify

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log

/**
 * Pulls s16 PCM (44.1 kHz stereo) from the native ring and writes it to an [AudioTrack] (music
 * stream) on its own thread, for the life of the native receiver. The thread blocks in native code
 * while nothing arrives (2 wakeups a second). The AudioTrack exists only while audio flows: it is
 * created on the first samples and released after [RELEASE_AFTER_MS] without any.
 *
 * librespot paces itself on this reader (the native ring blocks the decoder when full), so the
 * write here is blocking and the buffers are small: ~0.25 s in the ring, ~0.2 s in the track.
 */
internal class SpotifyAudio(private val handle: Long) {
    @Volatile private var running = true
    @Volatile private var pauseRequested = false
    @Volatile private var gain = 1f
    @Volatile private var track: AudioTrack? = null

    private val thread = Thread(::run, "spotify-audio")

    fun start() = thread.start()

    /** Ends the thread (the native stop ends it too). */
    fun stop() {
        running = false
        NativeBridge.nativeWakeReader(handle)
        try { track?.pause() } catch (_: IllegalStateException) {} // releases a blocking write()
    }

    fun join(timeoutMs: Long) = thread.join(timeoutMs)

    /** librespot closed its output (pause / stop): pause and drop what the track holds, at once. */
    fun pauseNow() {
        pauseRequested = true
        NativeBridge.nativeWakeReader(handle)
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
        try { track?.setVolume(gain) } catch (_: IllegalStateException) {}
    }

    private fun newTrack(): AudioTrack? {
        val rate = NativeBridge.SAMPLE_RATE
        val mask = AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(minBytes * 2, rate * 2 * 2 / 5) // ~200 ms
        return try {
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
                        .setSampleRate(rate)
                        .setChannelMask(mask)
                        .build(),
                )
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.setVolume(gain); Log.i(TAG, "audio track ${rate}Hz stereo, buffer ${it.bufferSizeInFrames} frames") }
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack failed", e)
            null
        }
    }

    private fun release() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: IllegalStateException) {}
        t.release()
        Log.i(TAG, "audio track released (idle)")
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(4096)
        var idleMs = 0L
        try {
            while (running) {
                if (pauseRequested) {
                    pauseRequested = false
                    track?.let { if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause(); it.flush() }
                }
                val n = NativeBridge.nativeReadPcm(handle, buf, POLL_MS)
                if (n < 0) break
                if (n == 0) {
                    idleMs += POLL_MS
                    if (track != null && idleMs >= RELEASE_AFTER_MS) release()
                    continue
                }
                idleMs = 0
                val t = track ?: newTrack()?.also { track = it } ?: continue
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                var off = 0
                while (off < n && running && !pauseRequested) {
                    val w = t.write(buf, off, n - off, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    off += w
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio thread failed", e)
        } finally {
            release()
            Log.i(TAG, "audio thread ended")
        }
    }

    companion object {
        private const val TAG = "VesperSpotify"
        const val POLL_MS = 500
        const val RELEASE_AFTER_MS = 30_000L
    }
}
