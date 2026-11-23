package app.vesper.feature.radio

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

class RadioModelTest {
    private object NoControls : MediaControls {
        override fun play() {}
        override fun pause() {}
        override fun next() {}
        override fun previous() {}
        override fun seekTo(positionMs: Long) {}
        override fun setVolume(volume: Float) {}
    }

    private fun session(id: String, kind: MediaSourceKind, playing: Boolean) =
        MediaSession(id, MediaState("t", "a", playing = playing, source = kind, positionSampledAt = 0), NoControls)

    @Test fun arbitration() {
        val list = listOf(
            session("radio", MediaSourceKind.OTHER_APP, true),
            session("airplay", MediaSourceKind.AIRPLAY, true),
            session("spotify", MediaSourceKind.SPOTIFY, false),
            session("iphone", MediaSourceKind.OTHER_APP, true), // the phone's own player: not on the Echo
            session(FakeMediaSource.ID, MediaSourceKind.AIRPLAY, true), // demo
        )
        assertEquals(setOf("airplay"), RadioArbitration.othersPlaying(list, "radio"))
        assertEquals(listOf("airplay"), RadioArbitration.toPause(list, "radio").map { it.sourceId })
        assertTrue(RadioArbitration.shouldStopRadio(emptySet(), setOf("airplay"), radioOn = true))
        assertFalse(RadioArbitration.shouldStopRadio(setOf("airplay"), setOf("airplay"), radioOn = true))
        assertFalse(RadioArbitration.shouldStopRadio(emptySet(), setOf("airplay"), radioOn = false))
        assertTrue(RadioArbitration.shouldStopRadio(setOf("airplay"), setOf("airplay", "bluetooth"), radioOn = true))
    }

    @Test fun queries() {
        assertTrue(RadioQuery.wantsFavourite("my favourites"))
        assertTrue(RadioQuery.wantsFavourite("Favorite station"))
        assertTrue(RadioQuery.wantsFavourite("the radio"))
        assertFalse(RadioQuery.wantsFavourite("jazz"))
        assertEquals("jazz", RadioQuery.tagOf("some jazz radio please"))
        assertEquals("classic rock", RadioQuery.tagOf("Classic Rock station"))
        val names = RadioQuery.countryNames()
        assertEquals("FR", RadioQuery.countryCode("France", names))
        assertEquals("FR", RadioQuery.countryCode("jazz from france", names))
        assertEquals("GB", RadioQuery.countryCode("uk", names))
        assertEquals("DE", RadioQuery.countryCode("Germany", names))
        assertNull(RadioQuery.countryCode("lo-fi beats", names))
    }

    @Test fun stationText() {
        val s = Station("u", "Night Swim Jazz", "https://x", tags = listOf("jazz", "smooth jazz", "lounge"), country = "France", countryCode = "FR", codec = "aac+", bitrate = 64)
        assertEquals("AAC+ · 64 kbps", s.format)
        assertEquals("Jazz · Smooth jazz", s.genre)
        assertEquals("Jazz · Smooth jazz · France", s.subtitle)
        assertEquals("NS", RadioArt.initials(s.name))
        assertEquals("KE", RadioArt.initials("KEXP"))
        assertTrue(Station.playable(s))
        assertFalse(Station.playable(s.copy(codec = "WMA")))
        assertTrue(Station.playable(s.copy(codec = "UNKNOWN", hls = true)))
        assertEquals(RadioArt.hue("Night Swim Jazz"), RadioArt.hue("night swim jazz"), 0f)
    }

    @Test fun genresAndDemo() {
        assertEquals(8, Genre.ALL.size)
        assertEquals("jazz", Genre.byId("JAZZ")?.tag)
        assertNull(Genre.byId("local")?.tag)
        assertTrue(DemoStations.ALL.all { it.isDemo })
        assertTrue(DemoStations.forGenre(Genre.byId("jazz")!!, "US").any { it.name == "Night Swim Jazz" })
        assertTrue(DemoStations.forGenre(Genre.byId("local")!!, "US").all { it.countryCode == "US" })
        assertEquals("Night Swim Jazz", DemoStations.search("night swim").single().name)
    }
}
