package app.vesper.feature.spotify

import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import app.vesper.core.model.fake.FakeMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPolicyTest {
    private object NoControls : MediaControls {
        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
    }

    private fun session(id: String, kind: MediaSourceKind, playing: Boolean) =
        MediaSession(id, MediaState("t", "a", source = kind, playing = playing, positionSampledAt = 0), NoControls)

    // --- volume ---

    @Test fun volumeMapping() {
        assertEquals(0f, SpotifyVolume.fraction(0), 0f)
        assertEquals(1f, SpotifyVolume.fraction(65_535), 0f)
        assertEquals(1f, SpotifyVolume.fraction(99_999), 0f)
        assertEquals(32_768, SpotifyVolume.fromFraction(0.5f))
        assertEquals(65_535, SpotifyVolume.fromFraction(2f))
        assertEquals(0, SpotifyVolume.index(0, 15))
        assertEquals(15, SpotifyVolume.index(65_535, 15))
        assertEquals(8, SpotifyVolume.index(32_768, 15))
    }

    @Test fun spotifySliderSetsTheSpeakerWithoutEchoingBack() {
        val sync = VolumeSync(15)
        // Slider to ~50 %: speaker 4 → 8.
        assertEquals(8, sync.fromSpotify(32_768, currentIndex = 4))
        // The speaker change comes back from the system: not reported (it was ours).
        assertNull(sync.fromSpeaker(8))
        // Already there: nothing to set.
        assertNull(sync.fromSpotify(34_000, currentIndex = 8))
    }

    @Test fun echoKeysReportBackToSpotify() {
        val sync = VolumeSync(15)
        assertEquals(SpotifyVolume.fromFraction(5 / 15f), sync.fromSpeaker(5))
        assertEquals(SpotifyVolume.fromFraction(6 / 15f), sync.fromSpeaker(6))
        // After a slider change, a key press to another level is reported again.
        sync.fromSpotify(65_535, currentIndex = 6)
        assertNull(sync.fromSpeaker(15))
        assertEquals(SpotifyVolume.fromFraction(14 / 15f), sync.fromSpeaker(14))
        // And back to the slider's level by key: reported (it's no longer our own change).
        assertEquals(SpotifyVolume.fromFraction(1f), sync.fromSpeaker(15))
    }

    // --- audio focus ---

    private val held = SpotifyFocusPolicy.requested(SpotifyFocus(), granted = true)

    @Test fun duckLowersAndGainRestores() {
        val ducked = SpotifyFocusPolicy.change(held, AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true)
        assertFalse(ducked.pause)
        assertEquals(0.2f, SpotifyFocusPolicy.gain(ducked.focus), 0f)
        val back = SpotifyFocusPolicy.change(ducked.focus, AUDIOFOCUS_GAIN, playing = true)
        assertFalse(back.play)
        assertEquals(1f, SpotifyFocusPolicy.gain(back.focus), 0f)
    }

    @Test fun transientLossPausesAndGainResumesOnlyWhatWePaused() {
        val lost = SpotifyFocusPolicy.change(held, AUDIOFOCUS_LOSS_TRANSIENT, playing = true)
        assertTrue(lost.pause)
        assertTrue(lost.focus.pausedForLoss)
        // A second transient loss while already paused doesn't pause again, and still resumes.
        val again = SpotifyFocusPolicy.change(lost.focus, AUDIOFOCUS_LOSS_TRANSIENT, playing = false)
        assertFalse(again.pause)
        assertTrue(SpotifyFocusPolicy.change(again.focus, AUDIOFOCUS_GAIN, playing = false).play)
        // Paused by the user before the loss: GAIN doesn't start it.
        val userPaused = SpotifyFocusPolicy.change(held, AUDIOFOCUS_LOSS_TRANSIENT, playing = false)
        assertFalse(SpotifyFocusPolicy.change(userPaused.focus, AUDIOFOCUS_GAIN, playing = false).play)
    }

    @Test fun permanentLossPausesForGood() {
        val lost = SpotifyFocusPolicy.change(held, AUDIOFOCUS_LOSS, playing = true)
        assertTrue(lost.pause)
        assertFalse(lost.focus.hasFocus)
        assertFalse(SpotifyFocusPolicy.change(lost.focus, AUDIOFOCUS_GAIN, playing = false).play)
    }

    // --- arbitration with AirPlay / Bluetooth ---

    @Test fun airplayStartingPausesSpotify() {
        val before = Arbitration.othersPlaying(listOf(session("airplay", MediaSourceKind.AIRPLAY, false)), "spotify")
        val now = Arbitration.othersPlaying(listOf(session("airplay", MediaSourceKind.AIRPLAY, true), session("spotify", MediaSourceKind.SPOTIFY, true)), "spotify")
        assertEquals(setOf("airplay"), now)
        assertTrue(Arbitration.shouldPauseSpotify(before, now, spotifyPlaying = true))
        // Spotify paused already: nothing to do. AirPlay still playing on the next update: no repeat.
        assertFalse(Arbitration.shouldPauseSpotify(before, now, spotifyPlaying = false))
        assertFalse(Arbitration.shouldPauseSpotify(now, now, spotifyPlaying = true))
    }

    @Test fun bluetoothCountsButAppsAndTheDemoDoNot() {
        val list = listOf(
            session("bluetooth", MediaSourceKind.BLUETOOTH, true),
            session("app:com.spotify.music:main", MediaSourceKind.OTHER_APP, true),
            session(FakeMediaSource.ID, MediaSourceKind.AIRPLAY, true),
            session("spotify", MediaSourceKind.SPOTIFY, true),
        )
        assertEquals(setOf("bluetooth"), Arbitration.othersPlaying(list, "spotify"))
    }

    @Test fun spotifyStartingPausesTheOtherReceivers() {
        val list = listOf(
            session("airplay", MediaSourceKind.AIRPLAY, true),
            session("bluetooth", MediaSourceKind.BLUETOOTH, false),
            session("spotify", MediaSourceKind.SPOTIFY, true),
        )
        assertEquals(listOf("airplay"), Arbitration.toPauseWhenSpotifyStarts(list, "spotify").map { it.sourceId })
    }

    // --- crash guard ---

    @Test fun crashGuardStopsAfterThreeUnfinishedStarts() {
        assertTrue(CrashGuard.allowed(0))
        assertTrue(CrashGuard.allowed(2))
        assertFalse(CrashGuard.allowed(3))
    }
}
