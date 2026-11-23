package app.vesper.feature.system.cc

import app.vesper.core.model.MediaControls
import app.vesper.core.model.MediaSession
import app.vesper.core.model.MediaSourceKind
import app.vesper.core.model.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyTileTest {
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

    @Test fun tileFollowsTheSpotifyConnectSession() {
        assertNull(ControlCenterPanel.spotifyTile(emptyList()))
        assertNull(ControlCenterPanel.spotifyTile(listOf(session("airplay", MediaSourceKind.AIRPLAY, true))))
        assertEquals(true, ControlCenterPanel.spotifyTile(listOf(session("airplay", MediaSourceKind.AIRPLAY, false), session("spotify", MediaSourceKind.SPOTIFY, true))))
        assertEquals(false, ControlCenterPanel.spotifyTile(listOf(session("spotify", MediaSourceKind.SPOTIFY, false))))
    }

    @Test fun labels() {
        assertEquals("Spotify", ControlCenterPanel.spotifyLabel(null))
        assertEquals("Playing", ControlCenterPanel.spotifyLabel(true))
        assertEquals("Paused", ControlCenterPanel.spotifyLabel(false))
    }
}
