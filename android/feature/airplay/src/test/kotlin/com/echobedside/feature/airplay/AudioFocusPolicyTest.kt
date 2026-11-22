package com.echobedside.feature.airplay

import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
import com.echobedside.feature.airplay.AudioFocusPolicy.change
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {
    private val held = AudioFocusPolicy.requested(FocusState(), granted = true)

    @Test fun requestGrantedHoldsFocusAtFullVolume() {
        assertEquals(FocusState(hasFocus = true), held)
        assertEquals(FocusState(), AudioFocusPolicy.requested(FocusState(), granted = false))
        assertEquals(0.5f, AudioFocusPolicy.gain(0.5f, held), 0f)
    }

    @Test fun assistantDucksToTwentyPercentAndGainRestores() {
        val ducked = change(held, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, hasRemote = true, playing = true)
        assertFalse("ducking never pauses the sender", ducked.togglePlayPause)
        assertTrue(ducked.state.ducked)
        assertFalse(ducked.state.muted)
        assertEquals(0.2f, AudioFocusPolicy.gain(1f, ducked.state), 1e-6f)
        assertEquals(0.1f, AudioFocusPolicy.gain(0.5f, ducked.state), 1e-6f) // on top of the sender's volume

        val back = change(ducked.state, AUDIOFOCUS_GAIN, hasRemote = true, playing = true)
        assertFalse(back.togglePlayPause)
        assertEquals(FocusState(hasFocus = true), back.state)
        assertEquals(1f, AudioFocusPolicy.gain(1f, back.state), 0f)
    }

    @Test fun transientLossPausesSenderWithRemoteAndResumesOnGain() {
        val lost = change(held, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = true, playing = true)
        assertTrue(lost.togglePlayPause)
        assertTrue(lost.state.muted && lost.state.pausedSender && lost.state.hasFocus)

        // A second transient loss (or one arriving after the sender paused) doesn't toggle again.
        val again = change(lost.state, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = true, playing = false)
        assertFalse(again.togglePlayPause)
        assertTrue(again.state.pausedSender)

        val back = change(again.state, AUDIOFOCUS_GAIN, hasRemote = true, playing = false)
        assertTrue("we paused it, so we play it again", back.togglePlayPause)
        assertEquals(FocusState(hasFocus = true), back.state)
    }

    @Test fun transientLossWithoutRemoteOnlyMutes() {
        val lost = change(held, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = false, playing = true)
        assertFalse(lost.togglePlayPause)
        assertTrue(lost.state.muted)
        assertFalse(lost.state.pausedSender)
        val back = change(lost.state, AUDIOFOCUS_GAIN, hasRemote = false, playing = true)
        assertFalse(back.togglePlayPause)
        assertFalse(back.state.muted)
    }

    @Test fun pausedSenderIsNotResumedOnGain() {
        // The user paused before the call: GAIN must not start playback.
        val lost = change(held, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = true, playing = false)
        assertFalse(lost.togglePlayPause)
        assertFalse(change(lost.state, AUDIOFOCUS_GAIN, hasRemote = true, playing = false).togglePlayPause)
    }

    @Test fun duckThenTransientLossMutesInsteadOfDucking() {
        val ducked = change(held, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, hasRemote = false, playing = true).state
        val lost = change(ducked, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = false, playing = true).state
        assertTrue(lost.muted)
        assertFalse(lost.ducked)
    }

    @Test fun permanentLossPausesButNeverResumes() {
        val lost = change(held, AUDIOFOCUS_LOSS, hasRemote = true, playing = true)
        assertTrue(lost.togglePlayPause)
        assertEquals(FocusState(hasFocus = false, muted = true), lost.state)

        // Transient first, then permanent: the sender stays paused for good.
        val transient = change(held, AUDIOFOCUS_LOSS_TRANSIENT, hasRemote = true, playing = true).state
        val permanent = change(transient, AUDIOFOCUS_LOSS, hasRemote = true, playing = false)
        assertFalse(permanent.togglePlayPause)
        assertFalse(permanent.state.pausedSender)
    }

    @Test fun unknownChangeIsIgnored() {
        val step = change(held, 12345, hasRemote = true, playing = true)
        assertEquals(held, step.state)
        assertFalse(step.togglePlayPause)
    }
}
