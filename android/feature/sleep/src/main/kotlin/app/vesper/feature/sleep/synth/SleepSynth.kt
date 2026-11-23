package app.vesper.feature.sleep.synth

import app.vesper.core.model.SleepSound
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Procedural sleep sounds, generated sample by sample (pure Kotlin, no Android, no audio files).
 * Mono float output in (-1, 1), meant for [SAMPLE_RATE] = 22.05 kHz: everything worth hearing in
 * rain, surf and fans is below 10 kHz, and half the rate is half the cost.
 *
 * Building blocks, all one multiply-add or two per sample:
 *  - white noise from a 32-bit xorshift, pink (Paul Kellet's 3-pole "economy" filter) and brown
 *    (a leaky integrator);
 *  - one-pole low/high-pass filters and a Chamberlin state-variable band-pass (fan hum);
 *  - slow random LFOs (gusts, wave sets, cricket distance) updated once per [BLOCK] samples and
 *    interpolated across it, so no per-sample exp/sin;
 *  - a sine table for the cricket carrier and the fan's blade wobble;
 *  - a DC blocker and a soft knee above 0.8, so nothing clips even when drops pile up.
 *
 * Levels are calibrated so every sound sits around -18 dBFS RMS (SynthTest checks the bounds).
 */
class SleepSynth(val sound: SleepSound, val sampleRate: Int = SAMPLE_RATE, seed: Int = 0x5EED1234) {

    private var rng = seed or 1
    private val inv = 1f / sampleRate

    /** White noise in [-1, 1). */
    private fun white(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        return x * (1f / 2147483648f)
    }

    /** Uniform in [0, 1). */
    private fun uni(): Float = (white() + 1f) * 0.5f

    // pink (Kellet economy)
    private var p0 = 0f; private var p1 = 0f; private var p2 = 0f
    private fun pink(w: Float): Float {
        p0 = 0.99765f * p0 + w * 0.0990460f
        p1 = 0.96300f * p1 + w * 0.2965164f
        p2 = 0.57000f * p2 + w * 1.0526913f
        return (p0 + p1 + p2 + w * 0.1848f) * 0.25f
    }

    // brown (leaky integrator)
    private var br = 0f
    private fun brown(w: Float): Float {
        br = (br + 0.02f * w) * (1f / 1.02f)
        return br * 3.5f
    }

    /** One-pole coefficient for a cutoff of [hz]. */
    private fun coef(hz: Float): Float = (1.0 - exp(-2.0 * PI * hz / sampleRate)).toFloat()

    // DC blocker + output state
    private var dcX = 0f; private var dcY = 0f

    /** Fills [out] with [n] samples. No allocation. */
    fun render(out: FloatArray, n: Int = out.size) {
        var i = 0
        while (i < n) {
            val len = minOf(BLOCK, n - i)
            when (sound) {
                SleepSound.WHITE -> white(out, i, len)
                SleepSound.PINK -> pinkBlock(out, i, len)
                SleepSound.BROWN -> brownBlock(out, i, len)
                SleepSound.RAIN -> rain(out, i, len)
                SleepSound.OCEAN -> ocean(out, i, len)
                SleepSound.FAN -> fan(out, i, len)
                SleepSound.CRICKETS -> crickets(out, i, len)
            }
            finish(out, i, len)
            i += len
        }
    }

    /** DC blocker and the soft knee: |x| < 0.8 untouched, above it bends toward 1.0, never past. */
    private fun finish(out: FloatArray, from: Int, n: Int) {
        for (k in from until from + n) {
            val x = out[k]
            val y = x - dcX + 0.9985f * dcY
            dcX = x; dcY = y
            val a = abs(y)
            out[k] = if (a <= KNEE) y else {
                knees++
                val s = KNEE + (1f - KNEE) * tanh(((a - KNEE) / (1f - KNEE)).toDouble()).toFloat()
                if (y < 0) -s else s
            }
        }
    }

    /** Samples that went through the soft knee (for tests: should be ~0). */
    var knees = 0L
        private set

    // ---------------- noise colours ----------------

    private fun white(out: FloatArray, from: Int, n: Int) {
        // a touch of low-pass takes the harsh top octave off (white noise at bedtime is still bright)
        val a = 0.85f
        var lp = wLp
        for (k in from until from + n) { lp += a * (white() - lp); out[k] = lp * 0.26f }
        wLp = lp
    }
    private var wLp = 0f

    private fun pinkBlock(out: FloatArray, from: Int, n: Int) {
        for (k in from until from + n) out[k] = pink(white()) * 0.33f
    }

    private fun brownBlock(out: FloatArray, from: Int, n: Int) {
        for (k in from until from + n) out[k] = brown(white()) * 0.68f
    }

    // ---------------- slow random LFO ----------------

    /** A smooth random value in [0, 1]: heads for a new target every [minS]..[maxS] seconds. */
    private inner class Drift(private val minS: Float, private val maxS: Float, private val glide: Float, start: Float = 0.5f) {
        var value = start; private set
        private var target = start
        private var left = 0f
        /** Advance by one block of [n] samples. */
        fun step(n: Int): Float {
            val dt = n * inv
            left -= dt
            if (left <= 0f) { target = uni(); left = minS + (maxS - minS) * uni() }
            value += (target - value) * (1f - exp(-dt / glide))
            return value
        }
    }

    // ---------------- rain ----------------

    private val rainGust = Drift(2f, 6f, 1.8f)
    private val rainHpA = coef(450f)
    private val rainLpA = coef(5200f)
    private val dropLpA = coef(2600f)
    private val dropHpA = coef(700f)
    private var rHp = 0f; private var rLp = 0f
    private var dEnv = 0f; private var dDecay = 0.99f; private var dLp = 0f; private var dHp = 0f
    private var rumble = 0f
    private val rumbleA = coef(180f)
    private var gPrev = 0.5f

    private fun rain(out: FloatArray, from: Int, n: Int) {
        val g1 = rainGust.step(n)
        val g0 = gPrev; gPrev = g1
        val dg = (g1 - g0) / n
        var g = g0
        for (k in from until from + n) {
            g += dg
            val w = white()
            // the steady hiss: pink, band-limited 450 Hz..5.2 kHz, swelling with the gusts
            val pk = pink(w)
            rLp += rainLpA * (pk - rLp)
            rHp += rainHpA * (rLp - rHp)
            val hiss = (rLp - rHp) * (0.55f + 0.45f * g)
            // drops: Poisson clicks of filtered noise, 20..70 a second
            if (uni() < (20f + 50f * g) * inv) {
                val amp = uni().let { it * it } * 0.9f + 0.1f
                if (amp > dEnv) dEnv = amp
                dDecay = exp(-1f / (sampleRate * (0.004f + 0.01f * uni())))
            }
            val dn = white() * dEnv
            dEnv *= dDecay
            dLp += dropLpA * (dn - dLp)
            dHp += dropHpA * (dLp - dHp)
            val drop = dLp - dHp
            // distant body of the storm
            rumble += rumbleA * (w - rumble)
            out[k] = hiss * 0.72f + drop * 0.55f + rumble * 0.35f
        }
    }

    // ---------------- ocean ----------------

    private var wavePhase = 0.15f
    private var wavePeriod = 9f
    private var waveSize = 0.8f
    private var ePrev = 0f
    private var oLp = 0f
    private var oBody = 0f
    private val oBodyA = coef(140f)
    private var oHiss = 0f

    /** Wave envelope over one period: quick swell (35 %), then a long hiss back down the beach. */
    private fun waveShape(p: Float): Float =
        if (p < 0.35f) { val x = p / 0.35f; x * x * (3f - 2f * x) } else { val x = (1f - p) / 0.65f; x * x * (0.6f + 0.4f * x) }

    private fun ocean(out: FloatArray, from: Int, n: Int) {
        wavePhase += n * inv / wavePeriod
        if (wavePhase >= 1f) { wavePhase -= 1f; wavePeriod = 7f + 5f * uni(); waveSize = 0.55f + 0.45f * uni() }
        val e1 = waveShape(wavePhase) * waveSize
        val e0 = ePrev; ePrev = e1
        // the wash gets brighter as the wave breaks: cutoff follows the envelope (one exp per block)
        val a = coef(250f + 2600f * e1)
        val de = (e1 - e0) / n
        var e = e0
        for (k in from until from + n) {
            e += de
            val w = white()
            val pk = pink(w)
            oLp += a * (pk - oLp)
            oBody += oBodyA * (brown(w) - oBody)
            oHiss += 0.6f * (w - oHiss)
            out[k] = (oBody * (0.45f + 0.35f * e) + oLp * (0.15f + 1.05f * e) + oHiss * 0.05f * e * e) * 0.45f
        }
    }

    // ---------------- fan ----------------

    private var fLp = 0f
    private val fLpA = coef(900f)
    private var svLow = 0f; private var svBand = 0f
    private val svF = (2.0 * sin(PI * 105.0 / sampleRate)).toFloat()
    private val svQ = 1f / 6f
    private var blade = 0f
    private val bladeStep = 14.5f / sampleRate
    private val fanWobble = Drift(3f, 8f, 3f)
    private var wPrev = 0.5f

    private fun fan(out: FloatArray, from: Int, n: Int) {
        val w1 = fanWobble.step(n)
        val w0 = wPrev; wPrev = w1
        val dw = (w1 - w0) / n
        var wb = w0
        for (k in from until from + n) {
            wb += dw
            val w = white()
            fLp += fLpA * (pink(w) - fLp)
            // motor/air hum around 105 Hz (state-variable band-pass)
            val hi = w - svLow - svQ * svBand
            svBand += svF * hi
            svLow += svF * svBand
            blade += bladeStep; if (blade >= 1f) blade -= 1f
            val am = 1f + 0.07f * SINE[(blade * TABLE).toInt() and TABLE_MASK]
            out[k] = (fLp * 0.95f + brown(w) * 0.35f + svBand * 0.05f) * am * (0.92f + 0.08f * wb) * 0.37f
        }
    }

    // ---------------- crickets ----------------

    private inner class Cricket(val hz: Float, val level: Float, val every: Float, val pulses: Int) {
        var phase = 0f
        var pulse = 0          // pulses left in this chirp
        var t = 0              // samples into the current pulse/gap
        var wait = (every * uni() * sampleRate).toInt()
        val pulseLen = (0.016f * sampleRate).toInt()
        val gapLen = (0.011f * sampleRate).toInt()
        val step = hz / sampleRate
        val far = Drift(4f, 12f, 3f, 0.7f)
        var g = 0.7f

        fun sample(): Float {
            if (pulse == 0) {
                if (--wait > 0) return 0f
                pulse = pulses; t = 0
            }
            val s: Float
            if (t < pulseLen) {
                val env = SINE[((t.toFloat() / pulseLen) * (TABLE / 2)).toInt() and TABLE_MASK] // half sine window
                phase += step; if (phase >= 1f) phase -= 1f
                s = SINE[(phase * TABLE).toInt() and TABLE_MASK] * env
            } else s = 0f
            if (++t >= pulseLen + gapLen) {
                t = 0
                if (--pulse == 0) wait = ((every * (0.8f + 0.4f * uni())) * sampleRate).toInt()
            }
            return s * level * g
        }
    }

    private val crickets = arrayOf(
        Cricket(4650f, 0.16f, 0.85f, 3),
        Cricket(4280f, 0.10f, 1.1f, 4),
        Cricket(4930f, 0.055f, 0.7f, 3),
    )
    private var cAir = 0f
    private var cAirHp = 0f
    private val cAirA = coef(1200f)

    private fun crickets(out: FloatArray, from: Int, n: Int) {
        for (c in crickets) c.g = 0.35f + 0.65f * c.far.step(n)
        for (k in from until from + n) {
            val w = white()
            // still night air: soft brown with a breath of high pink
            val pk = pink(w)
            cAir += cAirA * (pk - cAir)
            cAirHp = pk - cAir
            var s = brown(w) * 0.55f + cAirHp * 0.03f
            for (c in crickets) s += c.sample()
            out[k] = s
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        /** LFO / envelope update granularity (~11.6 ms at 22.05 kHz). */
        const val BLOCK = 256
        const val KNEE = 0.8f
        private const val TABLE = 2048
        private const val TABLE_MASK = TABLE - 1
        private val SINE = FloatArray(TABLE) { sin(2.0 * PI * it / TABLE).toFloat() }
    }
}
