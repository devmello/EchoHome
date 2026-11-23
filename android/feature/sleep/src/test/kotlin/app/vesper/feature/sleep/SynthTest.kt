package app.vesper.feature.sleep

import app.vesper.core.model.SleepSound
import app.vesper.feature.sleep.synth.SleepMixer
import app.vesper.feature.sleep.synth.SleepSynth
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every sound: bounded, not silent, not clipping, no DC, not stuck; and cheap enough for the A53. */
class SynthTest {
    private val rate = SleepSynth.SAMPLE_RATE

    private class Stats(val rms: Float, val peak: Float, val mean: Float, val knees: Long, val minWindowRms: Float, val maxWindowRms: Float)

    private fun stats(sound: SleepSound, seconds: Int = 30, seed: Int = 42): Stats {
        val synth = SleepSynth(sound, rate, seed)
        val buf = FloatArray(1024)
        var sum2 = 0.0; var sum = 0.0; var peak = 0f; var n = 0L
        // 1-second windows: a sound must never go silent for a whole second, nor blast
        var w2 = 0.0; var wn = 0; var minW = Float.MAX_VALUE; var maxW = 0f
        // skip the first second (filters settling)
        val skip = rate.toLong()
        var total = 0L
        while (total < seconds.toLong() * rate) {
            synth.render(buf, buf.size)
            for (x in buf) {
                assertTrue("$sound produced a non-finite sample", x.isFinite())
                total++
                if (total <= skip) continue
                sum2 += x * x; sum += x; n++
                if (abs(x) > peak) peak = abs(x)
                w2 += x * x; wn++
                if (wn == rate) {
                    val r = sqrt(w2 / wn).toFloat()
                    if (r < minW) minW = r
                    if (r > maxW) maxW = r
                    w2 = 0.0; wn = 0
                }
            }
        }
        return Stats(sqrt(sum2 / n).toFloat(), peak, (sum / n).toFloat(), synth.knees, minW, maxW)
    }

    private fun db(x: Float) = 20 * log10(x.toDouble())

    @Test fun `every sound is bounded, audible, and doesn't clip`() {
        for (s in SleepSound.values()) {
            val st = stats(s)
            println("%-9s rms %.3f (%.1f dBFS) peak %.3f mean %+.4f knees %d  1s-rms %.3f..%.3f".format(
                s.id, st.rms, db(st.rms), st.peak, st.mean, st.knees, st.minWindowRms, st.maxWindowRms))
            assertTrue("$s is too quiet: rms ${st.rms}", st.rms > 0.04f)
            assertTrue("$s is too loud: rms ${st.rms}", st.rms < 0.30f)
            assertTrue("$s peaks at ${st.peak}", st.peak < 1.0f)
            assertTrue("$s hits the soft knee ${st.knees} times", st.knees < rate) // < 1 s worth in 30 s
            assertTrue("$s has DC ${st.mean}", abs(st.mean) < 0.01f)
            assertTrue("$s goes silent for a second (${st.minWindowRms})", st.minWindowRms > 0.01f)
        }
    }

    @Test fun `waves and gusts move, steady noise doesn't`() {
        val ocean = stats(SleepSound.OCEAN, 40)
        val white = stats(SleepSound.WHITE, 40)
        // the surf swells and draws back: the loudest second is well above the quietest
        assertTrue("ocean should swell (${ocean.minWindowRms}..${ocean.maxWindowRms})", ocean.maxWindowRms / ocean.minWindowRms > 1.6f)
        assertTrue("white noise should be steady (${white.minWindowRms}..${white.maxWindowRms})", white.maxWindowRms / white.minWindowRms < 1.15f)
    }

    @Test fun `seeded output is repeatable and different seeds differ`() {
        val a = FloatArray(4096); val b = FloatArray(4096); val c = FloatArray(4096)
        SleepSynth(SleepSound.RAIN, rate, 7).render(a)
        SleepSynth(SleepSound.RAIN, rate, 7).render(b)
        SleepSynth(SleepSound.RAIN, rate, 8).render(c)
        assertTrue(a.contentEquals(b))
        assertTrue(!a.contentEquals(c))
    }

    @Test fun `noise colours have their spectral tilt`() {
        // fraction of energy in the sample-to-sample difference: high for white, low for brown
        fun hf(s: SleepSound): Double {
            val buf = FloatArray(rate * 4); SleepSynth(s, rate, 3).render(buf)
            var d = 0.0; var e = 0.0
            for (i in rate until buf.size) { val x = buf[i]; val dx = x - buf[i - 1]; d += dx * dx; e += x * x }
            return d / e
        }
        val w = hf(SleepSound.WHITE); val p = hf(SleepSound.PINK); val br = hf(SleepSound.BROWN)
        println("hf ratio white %.3f pink %.3f brown %.4f".format(w, p, br))
        assertTrue(w > p && p > br)
    }

    /**
     * CPU: synthesis of every sound vs real time on this JVM. Target on the Echo (4× Cortex-A53 at
     * 1.3 GHz) is < 3 % of one core; an A53 runs this kind of scalar float loop roughly 8–15× slower
     * than a desktop core, so the JVM bound here is 0.25 % (= ~3 % at 12×).
     */
    @Test fun `synthesis costs well under 3 percent of a core`() {
        val buf = FloatArray(1024)
        val out = ShortArray(1024)
        for (s in SleepSound.values()) {
            val synth = SleepSynth(s, rate, 1)
            repeat(400) { synth.render(buf) } // warm up the JIT
            val seconds = 60
            val blocks = seconds * rate / buf.size
            val t0 = System.nanoTime()
            repeat(blocks) { synth.render(buf) }
            val ns = System.nanoTime() - t0
            val pct = ns / (seconds * 1e9) * 100
            println("%-9s %.1f ns/sample, %.3f %% of one core (JVM), ~%.2f %% on an A53 at 12x".format(s.id, ns.toDouble() / (blocks * buf.size), pct, pct * 12))
            assertTrue("$s costs $pct % on the JVM", pct < 0.25)
        }
        // the mixer on top (gains, ramp, 16-bit conversion), including a crossfade
        val m = SleepMixer(rate, 1024); m.play(SleepSound.RAIN)
        var now = 0L
        repeat(400) { m.render(out, 1024, now); now += 46 }
        m.play(SleepSound.OCEAN)
        val t0 = System.nanoTime()
        val blocks = 60 * rate / 1024
        repeat(blocks) { m.render(out, 1024, now); now += 46 }
        val pct = (System.nanoTime() - t0) / 60e9 * 100
        println("mixer+rain->ocean %.3f %% of one core (JVM)".format(pct))
        assertTrue(pct < 0.5)
    }

    @Test fun `mixer output is 16-bit and follows the gain`() {
        val m = SleepMixer(rate, 1024)
        m.level = 1f
        m.play(SleepSound.PINK)
        val out = ShortArray(1024)
        var now = 0L
        var maxAbs = 0
        repeat(200) { m.render(out, 1024, now); now += 46; for (v in out) maxAbs = maxOf(maxAbs, abs(v.toInt())) }
        assertTrue(maxAbs in 1000..32767)
        assertEquals(1f, m.gain, 1e-3f)
    }
}
