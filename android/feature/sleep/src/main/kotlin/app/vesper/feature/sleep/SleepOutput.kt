package app.vesper.feature.sleep

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.SleepSound
import app.vesper.feature.sleep.synth.SleepMixer
import app.vesper.feature.sleep.synth.SleepSynth

/** Where the samples go. The real one is [AudioTrackOutput]; tests use a fake. */
internal interface SleepOutput {
    /** Starts playing [sound] (or crossfades to it if already playing). */
    fun play(sound: SleepSound, level: Float, endsAt: Long?)
    /** Short ramp down, then the track is released. */
    fun stop()
    fun setEndsAt(endsAt: Long?)
    fun setDucked(ducked: Boolean)
    fun setLevel(level: Float)
    /** Called on the main thread when the output finished by itself (the timer's fade reached silence). */
    var onFinished: (() -> Unit)?
}

/** Audio focus, behind an interface for tests. */
internal interface FocusGate {
    /** true = granted. [onChange] gets AudioManager.AUDIOFOCUS_* on the main thread. */
    fun request(onChange: (Int) -> Unit): Boolean
    fun abandon()
}

internal class AndroidFocusGate(context: Context) : FocusGate {
    private val am = context.getSystemService(AudioManager::class.java)
    private var req: AudioFocusRequest? = null

    override fun request(onChange: (Int) -> Unit): Boolean {
        abandon()
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRS)
            .setWillPauseWhenDucked(false) // we duck ourselves
            .setOnAudioFocusChangeListener({ onChange(it) }, Handler(Looper.getMainLooper()))
            .build()
        req = r
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        req?.let { am.abandonAudioFocusRequest(it) }
        req = null
    }

    companion object {
        val ATTRS: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}

/**
 * A streaming AudioTrack (USAGE_MEDIA, 22.05 kHz mono 16-bit) fed by [SleepMixer] on one
 * audio-priority thread, ~46 ms per write. The thread exists only while a sound plays.
 */
internal class AudioTrackOutput : SleepOutput {
    override var onFinished: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var mixer: SleepMixer? = null
    private var thread: Thread? = null

    override fun play(sound: SleepSound, level: Float, endsAt: Long?) {
        val m = mixer
        if (m != null && thread?.isAlive == true) {
            m.level = level; m.endsAt = endsAt; m.play(sound)
            return
        }
        val nm = SleepMixer(SleepSynth.SAMPLE_RATE, BLOCK).also { it.level = level; it.endsAt = endsAt; it.play(sound) }
        mixer = nm
        thread = Thread({ run(nm) }, "VesperSleepAudio").also { it.start() }
    }

    override fun stop() { mixer?.stop(SystemClock.elapsedRealtime()) }
    override fun setEndsAt(endsAt: Long?) { mixer?.endsAt = endsAt }
    override fun setDucked(ducked: Boolean) { mixer?.ducked = ducked }
    override fun setLevel(level: Float) { mixer?.level = level }

    private fun run(m: SleepMixer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val rate = SleepSynth.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AndroidFocusGate.ATTRS)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, BLOCK * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(SleepFeature.TAG, "AudioTrack failed: ${e.javaClass.simpleName}")
            finished(m); return
        }
        val buf = ShortArray(BLOCK)
        var cpuNs = 0L; var blocks = 0
        try {
            track.play()
            while (mixer === m) {
                val t0 = System.nanoTime()
                val more = m.render(buf, BLOCK, SystemClock.elapsedRealtime())
                cpuNs += System.nanoTime() - t0; blocks++
                if (blocks % STATS_BLOCKS == 0) {
                    Log.i(SleepFeature.TAG, "synth ${m.sound?.id}: %.2f %% of one core".format(cpuNs * 100.0 / (blocks.toLong() * BLOCK * 1e9 / rate)))
                    cpuNs = 0; blocks = 0
                }
                var off = 0
                while (off < BLOCK) {
                    val w = track.write(buf, off, BLOCK - off)
                    if (w <= 0) throw IllegalStateException("write $w")
                    off += w
                }
                if (!more) break
            }
        } catch (e: Exception) {
            Log.w(SleepFeature.TAG, "sleep audio ended: ${e.javaClass.simpleName}")
        } finally {
            try { track.pause(); track.flush(); track.release() } catch (e: Exception) { /* already gone */ }
            finished(m)
        }
    }

    private fun finished(m: SleepMixer) {
        main.post {
            if (mixer === m) { mixer = null; thread = null; onFinished?.invoke() }
        }
    }

    companion object {
        /** 1024 samples = 46 ms at 22.05 kHz. */
        const val BLOCK = 1024
        /** Log the synthesis cost about once a minute. */
        private const val STATS_BLOCKS = 1300
    }
}
