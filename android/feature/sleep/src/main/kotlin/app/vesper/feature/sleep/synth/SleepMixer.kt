package app.vesper.feature.sleep.synth

import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The gain curves (pure, unit-tested). Times in ms on one clock (elapsedRealtime). */
object SleepFades {
    /** A new sound comes in over this long (no click, no jolt). */
    const val FADE_IN_MS = 3_000L
    /** Stop / pause: a short ramp down. */
    const val STOP_MS = 600L
    /** Changing sound: equal-power crossfade. */
    const val CROSSFADE_MS = 1_500L
    /** Ducked under the assistant or a notification. */
    const val DUCK = 0.25f

    fun fadeIn(sinceStartMs: Long): Float {
        val x = (sinceStartMs.toFloat() / FADE_IN_MS).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * The sleep timer's fade-out: 1 until [fadeMs] before [endsAt], then down to 0 at [endsAt].
     * Squared, so the last seconds are already very quiet (sounds like an even fade to the ear).
     */
    fun timer(now: Long, endsAt: Long?, fadeMs: Long = SleepSounds.FADE_OUT_MS): Float {
        endsAt ?: return 1f
        val r = ((endsAt - now).toFloat() / fadeMs).coerceIn(0f, 1f)
        return r * r
    }

    fun stop(now: Long, stopAt: Long?): Float {
        stopAt ?: return 1f
        return (1f - (now - stopAt).toFloat() / STOP_MS).coerceIn(0f, 1f)
    }

    /** Silent and done: the timer ran out, or the stop ramp finished. */
    fun finished(now: Long, endsAt: Long?, stopAt: Long?): Boolean =
        (endsAt != null && now >= endsAt) || (stopAt != null && now >= stopAt + STOP_MS)
}

/**
 * Mixes the current [SleepSynth] (and the previous one during a crossfade) into 16-bit PCM with the
 * level, fade-in, timer fade-out, duck and stop ramp. The gain is computed once per [render] call
 * and ramped linearly across it, so changes never click. Runs on the audio thread; the setters are
 * called from the main thread (volatile fields, picked up at the next block).
 */
class SleepMixer(private val sampleRate: Int = SleepSynth.SAMPLE_RATE, maxBlock: Int = 2048) {
    @Volatile var level: Float = 0.7f
    @Volatile var ducked: Boolean = false
    /** elapsedRealtime when the timer's fade reaches silence; null = no timer. */
    @Volatile var endsAt: Long? = null
    @Volatile private var stopAt: Long? = null
    @Volatile private var wanted: SleepSound? = null

    private var current: SleepSynth? = null
    private var previous: SleepSynth? = null
    private var switchedAt = 0L
    private var startedAt = -1L
    private var lastGain = 0f
    private val a = FloatArray(maxBlock)
    private val b = FloatArray(maxBlock)

    val sound: SleepSound? get() = wanted

    /** Starts (or crossfades to) [sound]. Clears a pending stop. */
    fun play(sound: SleepSound) { wanted = sound; stopAt = null }

    /** Ramps down over [SleepFades.STOP_MS] from [now]; [render] then returns false. */
    fun stop(now: Long) { if (stopAt == null) stopAt = now }

    /** Gain this block ends on (before the ramp): for tests and the debug log. */
    var gain = 0f
        private set

    /**
     * Fills [out] with [n] samples ending at time [now]. Returns false once it's finished (timer
     * ran out or stopped): the caller stops writing and releases the track.
     */
    fun render(out: ShortArray, n: Int, now: Long): Boolean {
        val w = wanted
        if (w != null && current?.sound != w) {
            if (current == null) { current = SleepSynth(w, sampleRate, (now xor 0x9E3779B9L).toInt()); startedAt = now }
            else { previous = current; current = SleepSynth(w, sampleRate, (now xor 0x7F4A7C15L).toInt()); switchedAt = now }
        }
        val cur = current
        if (cur == null) { java.util.Arrays.fill(out, 0, n, 0); return stopAt == null }
        val end = endsAt
        val stop = stopAt
        val g1 = level.coerceIn(0f, 1f) *
            SleepFades.fadeIn(now - startedAt) *
            SleepFades.timer(now, end) *
            SleepFades.stop(now, stop) *
            (if (ducked) SleepFades.DUCK else 1f)
        gain = g1
        cur.render(a, n)
        val prev = previous
        val x = if (prev != null) ((now - switchedAt).toFloat() / SleepFades.CROSSFADE_MS).coerceIn(0f, 1f) else 1f
        if (prev != null) {
            prev.render(b, n)
            val gn = sin(x * PI / 2).toFloat()
            val go = cos(x * PI / 2).toFloat()
            for (k in 0 until n) a[k] = a[k] * gn + b[k] * go
            if (x >= 1f) previous = null
        }
        val g0 = lastGain
        val dg = (g1 - g0) / n
        var g = g0
        for (k in 0 until n) {
            g += dg
            out[k] = (a[k] * g * 32767f).toInt().coerceIn(-32767, 32767).toShort()
        }
        lastGain = g1
        return !SleepFades.finished(now, end, stop)
    }
}
