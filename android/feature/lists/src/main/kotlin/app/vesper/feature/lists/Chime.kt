package app.vesper.feature.lists

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The reminder chime: three soft rising bell notes (G5 B5 D6, ~1.6 s), synthesised (no bundled
 * audio), played once on a static AudioTrack. Alarm usage like the timer chime, so the Echo's
 * alarm volume applies; [play]'s volume makes it quieter in Night mode. The caller skips it in
 * Do Not Disturb. ~70 KB of PCM, rendered on first use and kept.
 */
internal object Chime {
    private const val RATE = 22050
    private val pcm: ShortArray by lazy { render() }
    private val main = Handler(Looper.getMainLooper())

    fun play(volume: Float) {
        try {
            val data = pcm
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(data.size * 2)
                .build()
            t.write(data, 0, data.size)
            t.setVolume(volume.coerceIn(0f, 1f))
            t.play()
            main.postDelayed({ try { t.stop() } catch (e: Exception) { /* already stopped */ }; t.release() }, data.size * 1000L / RATE + 300)
        } catch (e: Exception) {
            Log.w(ListsFeature.TAG, "chime failed: ${e.javaClass.simpleName}")
        }
    }

    private fun render(): ShortArray {
        val buf = FloatArray((RATE * 1.6f).toInt())
        bell(buf, 0.00f, 783.99f, 0.45f)  // G5
        bell(buf, 0.16f, 987.77f, 0.45f)  // B5
        bell(buf, 0.32f, 1174.66f, 0.5f)  // D6
        return ShortArray(buf.size) { (buf[it].coerceIn(-1f, 1f) * 30000).toInt().toShort() }
    }

    private fun bell(buf: FloatArray, at: Float, freq: Float, amp: Float, decay: Float = 4.2f) {
        val start = (at * RATE).toInt()
        for (i in 0 until buf.size - start) {
            val t = i.toFloat() / RATE
            val env = (if (t < 0.005f) t / 0.005f else 1f) * exp(-decay * t)
            if (env < 0.0005f && t > 0.1f) break
            val w = 2 * PI * freq * t
            val s = sin(w) + 0.3 * sin(2.0 * w) * exp(-3.0 * t) + 0.1 * sin(3.01 * w) * exp(-6.0 * t)
            buf[start + i] += (amp * env * s / 1.4).toFloat()
        }
    }
}
