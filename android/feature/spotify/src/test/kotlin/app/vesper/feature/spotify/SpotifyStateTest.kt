package app.vesper.feature.spotify

import app.vesper.core.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyStateTest {
    private val connected = SpotifyReducer.phase(SpotifyState(), NativeBridge.PHASE_CONNECTED)

    private fun loaded(now: Long = 1_000) = SpotifyReducer.track(
        SpotifyReducer.client(connected, "Maya's iPhone"),
        "Afterglow", "Kito Rae, Low Tide", "Night Swim", 201_000, "https://i.scdn.co/image/ab67", now,
    )

    @Test fun nothingLoadedPublishesNothing() {
        assertNull(connected.toMediaState(null, 0.5f, 160))
        assertFalse(connected.loaded)
    }

    @Test fun trackMapsToMediaState() {
        val s = SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 12_000, 2_000)
        val m = s.toMediaState(null, 0.4f, 160)!!
        assertEquals("Afterglow", m.title)
        assertEquals("Kito Rae, Low Tide", m.artist)
        assertEquals("Night Swim", m.album)
        assertEquals(201_000L, m.durationMs)
        assertEquals(12_000L, m.positionMs)
        assertEquals(2_000L, m.positionSampledAt)
        assertTrue(m.playing)
        assertEquals(MediaSourceKind.SPOTIFY, m.source)
        assertEquals("Maya's iPhone", m.sourceLabel)
        assertEquals(0.4f, m.volume!!, 0f)
        assertEquals(listOf("Spotify Connect", "160 kbps", "Maya's iPhone"), m.route)
    }

    @Test fun positionExtrapolatesOnlyWhilePlaying() {
        val playing = SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 10_000, 5_000)
        assertEquals(13_000L, playing.positionNow(8_000))
        val paused = SpotifyReducer.playback(playing, NativeBridge.PLAY_PAUSED, 13_000, 8_000)
        assertFalse(paused.playing)
        assertEquals(13_000L, paused.positionNow(60_000))
        // Clamped to the duration.
        assertEquals(201_000L, playing.positionNow(10_000_000))
    }

    @Test fun loadingKeepsThePlayStateSoASkipDoesNotFlashPaused() {
        val playing = SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 10_000, 5_000)
        val next = SpotifyReducer.track(playing, "Paper Satellites", "The Northern Lakes", "Small Orbits", 252_000, "", 6_000)
        val loading = SpotifyReducer.playback(next, NativeBridge.PLAY_LOADING, 0, 6_000)
        assertTrue(loading.playing)
        assertTrue(loading.loading)
        assertEquals(0L, loading.positionNow(9_000)) // no extrapolation while loading
        val resumed = SpotifyReducer.playback(loading, NativeBridge.PLAY_PLAYING, 0, 7_000)
        assertFalse(resumed.loading)
        assertEquals(2_000L, resumed.positionNow(9_000))
    }

    @Test fun newTrackResetsPositionAndArtwork() {
        val withArt = SpotifyReducer.artwork(SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 50_000, 2_000), "https://i.scdn.co/image/ab67")
        assertEquals("https://i.scdn.co/image/ab67", withArt.artworkUrl)
        val next = SpotifyReducer.track(withArt, "Paper Satellites", "The Northern Lakes", "Small Orbits", 252_000, "https://i.scdn.co/image/cd89", 3_000)
        assertEquals(0L, next.positionMs)
        assertEquals(3_000L, next.positionAt)
        // The old bitmap isn't shown with the new track.
        assertNull(next.toMediaState(null, null, 96)!!.artwork)
        assertEquals("https://i.scdn.co/image/cd89", next.coverUrl)
        // The same track again (a repeat TrackChanged) keeps the position.
        val same = SpotifyReducer.track(withArt, "Afterglow", "Kito Rae, Low Tide", "Night Swim", 201_000, "https://i.scdn.co/image/ab67", 9_000)
        assertEquals(50_000L, same.positionMs)
        assertEquals("https://i.scdn.co/image/ab67", same.artworkUrl)
    }

    @Test fun emptyCoverAndClientAreNull() {
        val s = SpotifyReducer.client(SpotifyReducer.track(connected, "t", "a", "", 1, "", 0), "")
        assertNull(s.coverUrl)
        assertNull(s.client)
        assertEquals(listOf("Spotify Connect", "96 kbps"), s.route(96))
        assertEquals("", s.toMediaState(null, null, 96)!!.sourceLabel)
    }

    @Test fun seekMovesThePosition() {
        val s = SpotifyReducer.position(SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 10_000, 1_000), 90_000, 4_000)
        assertEquals(90_000L, s.positionNow(4_000))
        assertTrue(s.playing)
    }

    @Test fun stoppedClearsTheTrackButKeepsThePhase() {
        val s = SpotifyReducer.stopped(SpotifyReducer.playback(loaded(), NativeBridge.PLAY_PLAYING, 0, 0))
        assertNull(s.toMediaState(null, null, 160))
        assertEquals(NativeBridge.PHASE_CONNECTED, s.phase)
        assertNull(s.client)
    }

    @Test fun signOutPremiumAndLoginFailuresClearEverything() {
        for (p in listOf(NativeBridge.PHASE_IDLE, NativeBridge.PHASE_PREMIUM_REQUIRED, NativeBridge.PHASE_LOGIN_FAILED, NativeBridge.PHASE_FAILED)) {
            val s = SpotifyReducer.phase(loaded(), p)
            assertEquals(SpotifyState(phase = p), s)
        }
        // Offline keeps the track until the native side says it stopped.
        assertTrue(SpotifyReducer.phase(loaded(), NativeBridge.PHASE_OFFLINE).loaded)
    }

    @Test fun statusLines() {
        assertEquals("Off.", SpotifyStatus.text(null, null, "Echo Show"))
        assertTrue(SpotifyStatus.text(NativeBridge.PHASE_IDLE, null, "Echo Show").contains("“Echo Show”"))
        assertEquals("Connected. Playing from Maya's iPhone.", SpotifyStatus.text(NativeBridge.PHASE_CONNECTED, "Maya's iPhone", "Echo Show"))
        assertTrue(SpotifyStatus.text(NativeBridge.PHASE_CONNECTED, null, "Kitchen").contains("Kitchen"))
        assertEquals(SpotifyStatus.PREMIUM_NEEDED, SpotifyStatus.text(NativeBridge.PHASE_PREMIUM_REQUIRED, null, "Echo Show"))
        assertTrue(SpotifyStatus.PREMIUM_NEEDED.startsWith("Spotify Premium is needed"))
        assertTrue(SpotifyStatus.text(NativeBridge.PHASE_OFFLINE, null, "Echo Show").contains("Retrying"))
        assertTrue(SpotifyStatus.text(NativeBridge.PHASE_IDLE, null, "Echo Show", failedStarts = true).startsWith("Turned off"))
    }
}
