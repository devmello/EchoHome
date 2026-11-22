package app.vesper.feature.phone

import app.vesper.feature.phone.audio.AssistantPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPauseTest {
    private var t = 0L
    private val sent = mutableListOf<String>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val p = AssistantPause(
        pause = { sent += "pause" },
        play = { sent += "play" },
        later = { ms, block -> val e = (t + ms) to block; timers += e; { timers.remove(e) } },
        now = { t },
    )

    private fun advance(ms: Long) {
        t += ms
        timers.filter { it.first <= t }.forEach { timers.remove(it); it.second() }
    }

    @Test fun pausesWhilePlayingAndResumesAfterTheTurn() {
        p.onAssistant(true, playing = true)
        assertEquals(listOf("pause"), sent)
        p.onPlayingChanged(false)
        p.onAssistant(false, playing = false)
        advance(AssistantPause.RESUME_DELAY_MS - 1)
        assertEquals(listOf("pause"), sent)
        advance(1)
        assertEquals(listOf("pause", "play"), sent)
        assertFalse(p.holding)
    }

    @Test fun nothingWhenThePhoneWasntPlaying() {
        p.onAssistant(true, playing = false)
        p.onAssistant(false, playing = false)
        advance(5_000)
        assertTrue(sent.isEmpty())
    }

    @Test fun aGapBetweenPhasesDoesntFlap() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(false)
        p.onAssistant(false, playing = false) // working → speaking: island ends and comes back
        advance(300)
        p.onAssistant(true, playing = false)
        advance(5_000)
        assertEquals(listOf("pause"), sent)
        p.onAssistant(false, playing = false)
        advance(AssistantPause.RESUME_DELAY_MS)
        assertEquals(listOf("pause", "play"), sent)
    }

    @Test fun repeatedStartsPauseOnce() {
        p.onAssistant(true, playing = true)
        p.onAssistant(true, playing = true) // a stale state before the phone reported paused
        assertEquals(listOf("pause"), sent)
    }

    @Test fun aCommandFromNowPlayingCancelsTheResume() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(false)
        p.onUserCommand() // "pause the music" via the assistant's tools, or the Pause button
        p.onAssistant(false, playing = false)
        advance(5_000)
        assertEquals(listOf("pause"), sent)
    }

    @Test fun aCommandAfterTheTurnButBeforeTheResumeCancelsIt() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(false)
        p.onAssistant(false, playing = false)
        advance(500)
        p.onUserCommand()
        advance(5_000)
        assertEquals(listOf("pause"), sent)
        assertTrue(timers.isEmpty())
    }

    @Test fun restartedOnThePhoneDuringTheTurnIsLeftAlone() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(false)
        p.onPlayingChanged(true) // the user pressed play on the iPhone
        p.onAssistant(false, playing = true)
        advance(5_000)
        assertEquals(listOf("pause"), sent)
    }

    @Test fun aLatePlayingStateBeforeThePauseLandsDoesntCancel() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(true) // stale, the phone hasn't reported paused yet
        p.onPlayingChanged(false)
        p.onAssistant(false, playing = false)
        advance(AssistantPause.RESUME_DELAY_MS)
        assertEquals(listOf("pause", "play"), sent)
    }

    @Test fun aVeryLongHoldDoesntResume() {
        p.onAssistant(true, playing = true)
        p.onPlayingChanged(false)
        advance(AssistantPause.MAX_RESUME_AFTER_MS + 1)
        p.onAssistant(false, playing = false)
        advance(AssistantPause.RESUME_DELAY_MS)
        assertEquals(listOf("pause"), sent)
        assertFalse(p.holding)
    }

    @Test fun disconnectDuringTheHoldForgetsIt() {
        p.onAssistant(true, playing = true)
        p.reset() // A2DP dropped
        p.onAssistant(false, playing = false)
        advance(5_000)
        assertEquals(listOf("pause"), sent)
        p.onAssistant(true, playing = true) // a new turn after reconnecting works again
        assertEquals(listOf("pause", "pause"), sent)
    }
}
