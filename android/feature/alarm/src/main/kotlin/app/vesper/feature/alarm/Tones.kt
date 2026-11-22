package app.vesper.feature.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Sounds we synthesise (no bundled third-party audio): the timer-done chime, and a fallback alarm
 * tone for when the device has no default alarm sound. 16-bit mono PCM, looped by a static AudioTrack
 * on the alarm usage (so Do Not Disturb's "alarms" exception lets it through).
 */
internal object Tones {
    private const val RATE = 22050

    /** Two soft bell notes, twice, then a pause (2.4 s loop): "ding-dong · ding-dong". */
    fun timerChime(): ShortArray = render(2.4f) { buf ->
        bell(buf, 0.00f, 1318.5f, 0.55f)   // E6
        bell(buf, 0.28f, 987.8f, 0.55f)    // B5
        bell(buf, 0.75f, 1318.5f, 0.55f)
        bell(buf, 1.03f, 987.8f, 0.55f)
    }

    /** Fallback alarm: brighter triple beeps every second. */
    fun alarmFallback(): ShortArray = render(1.0f) { buf ->
        bell(buf, 0.00f, 1760f, 0.5f, decay = 9f)
        bell(buf, 0.16f, 1760f, 0.5f, decay = 9f)
        bell(buf, 0.32f, 1760f, 0.5f, decay = 9f)
    }

    fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        return t
    }

    private inline fun render(seconds: Float, block: (FloatArray) -> Unit): ShortArray {
        val buf = FloatArray((RATE * seconds).toInt())
        block(buf)
        return ShortArray(buf.size) { (buf[it].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
    }

    /** A bell-ish note: fundamental + soft overtones, fast attack, exponential decay. */
    private fun bell(buf: FloatArray, at: Float, freq: Float, amp: Float, decay: Float = 5.5f) {
        val start = (at * RATE).toInt()
        val len = (RATE * 0.9f).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toFloat() / RATE
            val env = (if (t < 0.004f) t / 0.004f else 1f) * exp(-decay * t)
            val w = 2 * PI * freq * t
            val s = sin(w) + 0.35 * sin(2.0 * w) * exp(-3.0 * t) + 0.12 * sin(3.01 * w) * exp(-6.0 * t)
            buf[idx] += (amp * env * s / 1.47).toFloat()
        }
    }
}
