package app.vesper.feature.sleep

import android.media.AudioManager
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.core.model.Secrets
import app.vesper.core.model.SleepSound
import app.vesper.core.model.SleepSounds
import app.vesper.core.model.VesperConfig
import app.vesper.core.model.VesperServices
import app.vesper.core.model.fake.FakeMediaSource
import app.vesper.feature.sleep.synth.SleepFades
import app.vesper.feature.sleep.synth.SleepMixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeTest {
    @Test fun `timer fade is flat until the last 30 s, then reaches silence at the end`() {
        val end = 1_000_000L
        assertEquals(1f, SleepFades.timer(end - 60_000, end), 0f)
        assertEquals(1f, SleepFades.timer(end - 30_000, end), 0f)
        assertEquals(0.25f, SleepFades.timer(end - 15_000, end), 1e-4f)
        assertEquals(0f, SleepFades.timer(end, end), 0f)
        assertEquals(0f, SleepFades.timer(end + 5_000, end), 0f)
        assertEquals(1f, SleepFades.timer(end, null), 0f)
        // monotonic through the fade
        var last = 1f
        for (t in end - 30_000..end step 500) { val g = SleepFades.timer(t, end); assertTrue(g <= last); last = g }
    }

    @Test fun `fade-in, stop ramp and finish`() {
        assertEquals(0f, SleepFades.fadeIn(0), 0f)
        assertEquals(0.5f, SleepFades.fadeIn(SleepFades.FADE_IN_MS / 2), 1e-4f)
        assertEquals(1f, SleepFades.fadeIn(10_000), 0f)
        assertEquals(1f, SleepFades.stop(5_000, null), 0f)
        assertEquals(0.5f, SleepFades.stop(5_000 + SleepFades.STOP_MS / 2, 5_000), 1e-4f)
        assertFalse(SleepFades.finished(100, 200, null))
        assertTrue(SleepFades.finished(200, 200, null))
        assertFalse(SleepFades.finished(5_000 + SleepFades.STOP_MS - 1, null, 5_000))
        assertTrue(SleepFades.finished(5_000 + SleepFades.STOP_MS, null, 5_000))
    }

    @Test fun `mixer ends by itself at the timer and is silent at the end`() {
        val m = SleepMixer(22_050, 1024)
        m.level = 1f
        m.endsAt = 60_000L
        m.play(SleepSound.BROWN)
        val out = ShortArray(1024)
        var now = 0L
        var more = true
        var lastPeak = 0
        var peakBeforeFade = 0
        while (more && now < 120_000) {
            more = m.render(out, 1024, now)
            val p = out.maxOf { kotlin.math.abs(it.toInt()) }
            if (now in 10_000..29_000) peakBeforeFade = maxOf(peakBeforeFade, p)
            lastPeak = p
            now += 46
        }
        assertFalse("mixer should finish at the timer", more)
        assertTrue(now in 60_000..60_100)
        assertTrue("loud before the fade ($peakBeforeFade)", peakBeforeFade > 3000)
        assertTrue("silent at the end ($lastPeak)", lastPeak < 200)
    }

    @Test fun `ducking and stop ramp the gain without jumps`() {
        val m = SleepMixer(22_050, 1024)
        m.level = 1f
        m.play(SleepSound.PINK)
        val out = ShortArray(1024)
        var now = 0L
        repeat(100) { m.render(out, 1024, now); now += 46 }
        assertEquals(1f, m.gain, 1e-3f)
        m.ducked = true
        m.render(out, 1024, now); now += 46
        assertEquals(SleepFades.DUCK, m.gain, 1e-3f)
        m.ducked = false
        m.stop(now)
        var more = true
        var n = 0
        while (more) { more = m.render(out, 1024, now); now += 46; n++ }
        assertTrue("stop takes about ${SleepFades.STOP_MS} ms ($n blocks)", n in 12..16)
        assertEquals(0f, m.gain, 1e-3f)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SleepControllerTest {
    init { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() = Dispatchers.resetMain()

    private val settings = MemSettings()
    private val services = VesperServices(VesperConfig(debug = true, demoData = false, secrets = Secrets()), settings)
    private val out = FakeOutput()
    private val focus = FakeFocus()
    private val sched = FakeScheduler()
    private var now = 1_000_000L
    private val c = SleepController({ services }, out, focus, sched, { now })

    private fun session() = services.media.sessions.value.firstOrNull { it.sourceId == SleepSounds.SOURCE_ID }

    @Test fun `nothing plays until asked`() {
        assertNull(c.state.value.sound)
        assertTrue(out.log.isEmpty())
        assertEquals(0, focus.requests)
        assertNull(session())
    }

    @Test fun `play uses the saved defaults, takes focus and shows in the media hub`() {
        settings.putString(SleepSettings.SOUND, "ocean")
        settings.putString(SleepSettings.TIMER, "60")
        assertTrue(c.play())
        val st = c.state.value
        assertEquals(SleepSound.OCEAN, st.sound)
        assertTrue(st.playing)
        assertEquals(60, st.timerMinutes)
        assertEquals(now + 60 * 60_000L, st.endsAtElapsed)
        assertEquals(listOf("play ocean"), out.log)
        assertTrue(focus.held)
        val s = session()!!
        assertEquals("Ocean", s.state.title)
        assertEquals(MediaSourceKind.OTHER_APP, s.state.source)
        assertEquals(60 * 60_000L, s.state.durationMs)
        assertTrue(s.state.playing)
        assertFalse(s.controls.canSeek)
        assertEquals(s, services.media.current.value)
    }

    @Test fun `no focus, no sound`() {
        focus.grant = false
        assertFalse(c.play(SleepSound.RAIN, 30))
        assertNull(c.state.value.sound)
        assertTrue(out.log.isEmpty())
    }

    @Test fun `timer marks the fade 30 s before the end, then stops`() {
        c.play(SleepSound.RAIN, 15)
        val end = now + 15 * 60_000L
        assertEquals(end - SleepSounds.FADE_OUT_MS, sched.at)
        now = end - SleepSounds.FADE_OUT_MS; sched.advance(now)
        assertTrue(c.state.value.fading)
        // the output reaches silence and reports it
        now = end; out.onFinished?.invoke()
        assertNull(c.state.value.sound)
        assertNull(session())
        assertFalse(focus.held)
    }

    @Test fun `timer off plays until stopped`() {
        c.play(SleepSound.FAN, 0)
        assertNull(c.state.value.endsAtElapsed)
        assertNull(sched.at)
        assertEquals(0L, session()!!.state.durationMs)
        c.stop()
        assertNull(c.state.value.sound)
        assertEquals("stop", out.log.last())
    }

    @Test fun `changing the timer restarts it from now`() {
        c.play(SleepSound.RAIN, 30)
        now += 10 * 60_000L
        c.setTimer(90)
        assertEquals(now + 90 * 60_000L, c.state.value.endsAtElapsed)
        assertEquals(now + 90 * 60_000L, out.lastEndsAt)
        c.setTimer(0)
        assertNull(c.state.value.endsAtElapsed)
        assertNull(out.lastEndsAt)
    }

    @Test fun `pause keeps the time left, resume continues it`() {
        c.play(SleepSound.RAIN, 30)
        now += 10 * 60_000L
        c.pause()
        assertFalse(c.state.value.playing)
        assertEquals(20 * 60_000L, c.pausedRemainingMs)
        assertFalse(session()!!.state.playing)
        now += 5 * 60_000L
        assertTrue(c.resume())
        assertEquals(now + 20 * 60_000L, c.state.value.endsAtElapsed)
        assertEquals("play rain", out.log.last())
    }

    @Test fun `skip and switch crossfade to another sound, keeping the timer`() {
        c.play(SleepSound.RAIN, 30)
        val end = c.state.value.endsAtElapsed
        session()!!.controls.next()
        assertEquals(SleepSound.OCEAN, c.state.value.sound)
        assertEquals(end, c.state.value.endsAtElapsed)
        assertEquals("play ocean", out.log.last())
        session()!!.controls.previous(); session()!!.controls.previous()
        assertEquals(SleepSound.CRICKETS, c.state.value.sound) // wraps around
    }

    @Test fun `AirPlay taking focus pauses it for good`() {
        c.play(SleepSound.RAIN, 30)
        focus.listener!!(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(c.state.value.playing)
        assertEquals(SleepSound.RAIN, c.state.value.sound)
        focus.listener!!(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("a permanent loss doesn't resume", c.state.value.playing)
    }

    @Test fun `a short interruption resumes, a long one doesn't`() {
        c.play(SleepSound.RAIN, 0)
        focus.listener!!(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(c.state.value.playing)
        now += 5_000
        focus.listener!!(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(c.state.value.playing)
        focus.listener!!(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        now += 10 * 60_000L // an alarm ringing
        focus.listener!!(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(c.state.value.playing)
    }

    @Test fun `the assistant ducks it`() {
        c.play(SleepSound.RAIN, 0)
        focus.listener!!(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(out.duckedNow)
        assertTrue(c.state.value.playing)
        focus.listener!!(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(out.duckedNow)
    }

    @Test fun `another source starting on the hub pauses it`() {
        val nop = object : MediaControls {
            override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {}
            override fun seekTo(positionMs: Long) {}; override fun setVolume(volume: Float) {}
        }
        // already playing before: not a reason to pause
        services.media.publish("app", MediaState("Song", "A", playing = true, source = MediaSourceKind.OTHER_APP), nop)
        c.watchHub()
        c.play(SleepSound.RAIN, 30)
        assertTrue(c.state.value.playing)
        // the silent demo source doesn't count
        services.media.publish(FakeMediaSource.ID, MediaState("Demo", "D", playing = true, source = MediaSourceKind.AIRPLAY), nop)
        assertTrue(c.state.value.playing)
        // Spotify starts
        services.media.publish("spotify", MediaState("Track", "B", playing = true, source = MediaSourceKind.SPOTIFY), nop)
        assertFalse(c.state.value.playing)
        assertEquals(SleepSound.RAIN, c.state.value.sound)
    }

    @Test fun `disconnect from Now Playing stops it`() {
        c.play(SleepSound.CRICKETS, 30)
        session()!!.controls.disconnect()
        assertNull(c.state.value.sound)
        assertNull(session())
    }

    @Test fun `silent preview publishes without audio or focus`() {
        c.start(SleepSound.OCEAN, 30, silent = true)
        assertTrue(c.state.value.playing)
        assertTrue(out.log.isEmpty())
        assertEquals(0, focus.requests)
        assertEquals("Ocean", session()!!.state.title)
        c.stop()
        assertTrue(out.log.isEmpty())
    }

    @Test fun `output ending early restarts it`() {
        c.play(SleepSound.RAIN, 30)
        now += 60_000
        out.onFinished?.invoke()
        assertTrue(c.state.value.playing)
        assertEquals(listOf("play rain", "play rain"), out.log)
    }
}
