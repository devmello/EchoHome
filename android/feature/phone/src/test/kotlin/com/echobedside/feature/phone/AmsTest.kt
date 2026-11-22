package com.echobedside.feature.phone

import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaSession
import com.echobedside.core.model.MediaSourceKind
import com.echobedside.core.model.MediaState
import com.echobedside.feature.phone.ams.Ams
import com.echobedside.feature.phone.ams.AmsMapping
import com.echobedside.feature.phone.ams.AmsMapping.Show
import com.echobedside.feature.phone.ams.AmsNowPlaying
import com.echobedside.feature.phone.ams.AmsSession
import com.echobedside.feature.phone.ams.FakeAmsPhone
import com.echobedside.feature.phone.ams.ItunesArt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsTest {
    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun ascii(s: String) = s.toByteArray(Charsets.UTF_8)

    private var t = 1_000L
    private val reads = mutableListOf<Pair<Int, Int>>()
    private val sent = mutableListOf<Int>()
    private val changes = mutableListOf<AmsNowPlaying>()
    private val session = AmsSession(object : AmsSession.Io {
        override fun readAttribute(entity: Int, attribute: Int) { reads += entity to attribute }
        override fun command(id: Int) { sent += id }
    }, { changes += it }, { t })

    // --- codec ---

    @Test fun subscriptionsAndCommandBytes() {
        assertArrayEquals(hex("02 00 01 02 03"), Ams.SUBSCRIBE_TRACK)
        assertArrayEquals(hex("00 00 01 02"), Ams.SUBSCRIBE_PLAYER)
        assertArrayEquals(hex("02 02"), Ams.attributeRequest(Ams.ENTITY_TRACK, Ams.TRACK_TITLE))
        assertArrayEquals(hex("03"), Ams.command(Ams.CMD_NEXT_TRACK))
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6), Ams.parseCommands(hex("00 01 02 03 04 05 06")))
    }

    @Test fun parsesUpdatesAndPlaybackInfo() {
        val u = Ams.parseUpdate(hex("02 02 00") + ascii("Holocene"))!!
        assertEquals(Ams.ENTITY_TRACK, u.entity); assertEquals(Ams.TRACK_TITLE, u.attribute); assertFalse(u.truncated); assertEquals("Holocene", u.value)
        assertNull(Ams.parseUpdate(hex("02 02")))
        assertEquals(Ams.PlaybackInfo(1, 1f, 61_250), Ams.parsePlaybackInfo("1,1.0,61.250"))
        assertEquals(Ams.PlaybackInfo(0, 0f, 0), Ams.parsePlaybackInfo("0,,"))
        assertNull(Ams.parsePlaybackInfo("garbage"))
        assertEquals(214_000L, Ams.parseSeconds("214.000"))
        assertEquals(0L, Ams.parseSeconds(""))
    }

    @Test fun truncatedValueCutInsideAUtf8SequenceDropsTheHalfCharacter() {
        // "Sigur Rós – Hoppípolla" cut after the first byte of "ó" (0xC3 0xB3).
        val full = ascii("Sigur Rós")
        val cut = full.copyOf(8) // "Sigur R" + 0xC3
        val u = Ams.parseUpdate(hex("02 00 01") + cut)!!
        assertTrue(u.truncated)
        assertEquals("Sigur R", u.value)
    }

    // --- session: recorded streams ---

    /** Spotify at MTU 23, as iOS sends it right after the Echo subscribes (Track, then Player). */
    private val spotifyMtu23 = listOf(
        "02 00 00 42 6f 6e 20 49 76 65 72",                         // artist "Bon Iver"
        "02 01 01 42 6f 6e 20 49 76 65 72 2c 20 42 6f 6e 20 49",    // album "Bon Iver, Bon I…" (truncated)
        "02 02 00 48 6f 6c 6f 63 65 6e 65",                         // title "Holocene"
        "02 03 00 33 33 36 2e 39 33 33",                            // duration "336.933"
        "00 00 00 53 70 6f 74 69 66 79",                            // player "Spotify"
        "00 01 00 31 2c 31 2e 30 2c 36 31 2e 32 35 30",             // "1,1.0,61.250"
        "00 02 00 30 2e 35 36",                                     // volume "0.56"
    ).map(::hex)

    @Test fun spotifyStreamBuildsNowPlayingAndFetchesTheTruncatedAlbum() {
        spotifyMtu23.forEach(session::onEntityUpdate)
        session.onCommands(hex("00 01 02 03 04 05 06"))
        val s = session.state
        assertEquals("Spotify", s.app)
        assertEquals("Holocene", s.title)
        assertEquals("Bon Iver", s.artist)
        assertEquals("Bon Iver, Bon I", s.album) // shown truncated until the read lands
        assertEquals(336_933L, s.durationMs)
        assertTrue(s.playing)
        assertEquals(61_250L, s.elapsedMs)
        assertEquals(1_000L, s.sampledAt)
        assertEquals(0.56f, s.volume!!, 0.001f)
        assertEquals(listOf(Ams.ENTITY_TRACK to Ams.TRACK_ALBUM), reads)
        assertFalse(session.settled) // no art lookup with a cut-off name
        session.onAttributeRead(ascii("Bon Iver, Bon Iver"))
        assertEquals("Bon Iver, Bon Iver", session.state.album)
        assertTrue(session.settled)
    }

    @Test fun truncatedValuesAreReadOneAtATimeAndAStaleReadLoses() {
        val phone = FakeAmsPhone(mtu = 23)
        phone.track("Hoppípolla (Live at the Royal Albert Hall)", "Sigur Rós", "Inni (Live at Alexandra Palace)", 270.0).forEach(session::onEntityUpdate)
        assertEquals(listOf(Ams.ENTITY_TRACK to Ams.TRACK_ALBUM), reads) // only one in flight
        // The title changes (next track) while the album read is pending: the new album wins.
        session.onEntityUpdate(phone.update(Ams.ENTITY_TRACK, Ams.TRACK_ALBUM, "Takk..."))
        phone.writeAttribute(Ams.attributeRequest(Ams.ENTITY_TRACK, Ams.TRACK_ALBUM))
        session.onAttributeRead(ascii("Inni (Live at Alexandra Palace)")) // the old, full value
        assertEquals("Takk...", session.state.album)
        // Then the title read goes out, and its answer is used.
        assertEquals(Ams.ENTITY_TRACK to Ams.TRACK_TITLE, reads.last())
        phone.writeAttribute(Ams.attributeRequest(Ams.ENTITY_TRACK, Ams.TRACK_TITLE))
        session.onAttributeRead(phone.readAttribute())
        assertEquals("Hoppípolla (Live at the Royal Albert Hall)", session.state.title)
    }

    @Test fun aFailedReadKeepsTheTruncatedText() {
        session.onEntityUpdate(hex("02 02 01") + ascii("A very long ti"))
        session.onAttributeRead(null)
        assertEquals("A very long ti", session.state.title)
    }

    @Test fun podcastsFromANonSpotifyAppWithoutAnAlbum() {
        // Apple Podcasts: show as artist, no album, paused, 0.0 rate, a long duration.
        val phone = FakeAmsPhone(mtu = 185)
        phone.track("Episode 212: Night Owls", "The Slow Science Show", "", 3725.5).forEach(session::onEntityUpdate)
        phone.player("Podcasts", Ams.STATE_PAUSED, 1803.25, volume = 0.25f).forEach(session::onEntityUpdate)
        assertTrue(reads.isEmpty())
        val st = AmsMapping.toState(session.state, "Maya’s iPhone", null)!!
        assertEquals("Episode 212: Night Owls", st.title)
        assertEquals("", st.album)
        assertFalse(st.playing)
        assertEquals(3_725_500L, st.durationMs)
        assertEquals(1_803_250L, st.positionMs)
        assertEquals("Podcasts on Maya’s iPhone", st.sourceLabel)
        assertEquals(MediaSourceKind.OTHER_APP, st.source)
        assertEquals(listOf("Remote", "Maya’s iPhone", "Podcasts"), st.route)
    }

    @Test fun youTubeFastForwardCountsAsPlaying() {
        session.onEntityUpdate(hex("00 00 00") + ascii("YouTube"))
        session.onEntityUpdate(hex("02 02 00") + ascii("Lo-fi beats"))
        session.onEntityUpdate(hex("00 01 00") + ascii("3,2.0,12.000"))
        assertTrue(session.state.playing)
    }

    @Test fun nothingLoadedPublishesNothing() {
        session.onEntityUpdate(hex("00 00 00"))      // no player app
        session.onEntityUpdate(hex("02 02 00"))      // empty title
        assertNull(AmsMapping.toState(session.state, "Maya’s iPhone", null))
    }

    @Test fun commandsOnlyWhenTheIPhoneListsThem() {
        session.onCommands(hex("02")) // toggle only, no skip (e.g. a live radio stream)
        assertFalse(session.command(Ams.CMD_NEXT_TRACK))
        assertTrue(session.command(Ams.CMD_TOGGLE_PLAY_PAUSE))
        assertEquals(listOf(Ams.CMD_TOGGLE_PLAY_PAUSE), sent)
        val c = session.state.commands
        assertFalse(AmsMapping.canSkip(c)); assertTrue(AmsMapping.canPause(c))
        assertEquals(Ams.CMD_TOGGLE_PLAY_PAUSE, AmsMapping.playCommand(c, play = true))
        val all = setOf(Ams.CMD_PLAY, Ams.CMD_PAUSE, Ams.CMD_TOGGLE_PLAY_PAUSE)
        assertEquals(Ams.CMD_PAUSE, AmsMapping.playCommand(all, play = false))
        assertNull(AmsMapping.playCommand(emptySet(), play = true))
    }

    @Test fun volumeMovesInSixteenSteps() {
        assertEquals(4, AmsMapping.volumeSteps(0.5f, 0.75f))
        assertEquals(-8, AmsMapping.volumeSteps(0.5f, 0f))
        assertEquals(0, AmsMapping.volumeSteps(0.5f, 0.52f))
        assertEquals(0, AmsMapping.volumeSteps(null, 1f)) // unknown volume: don't guess
    }

    @Test fun resetClearsEverything() {
        spotifyMtu23.forEach(session::onEntityUpdate)
        session.reset()
        assertTrue(session.state.empty)
        session.onAttributeRead(ascii("late")) // a read answered after the link dropped is ignored
        assertTrue(session.state.empty)
    }

    // --- precedence ---

    private val noop = object : MediaControls {
        override fun play() {} ; override fun pause() {} ; override fun next() {} ; override fun previous() {}
        override fun seekTo(positionMs: Long) {} ; override fun setVolume(volume: Float) {}
    }
    private fun session(id: String, kind: MediaSourceKind, playing: Boolean) = MediaSession(id, MediaState("T", "A", playing = playing, source = kind), noop)
    private val np = AmsNowPlaying(app = "Spotify", title = "Holocene", artist = "Bon Iver", state = Ams.STATE_PLAYING)

    @Test fun precedence() {
        assertEquals(Show.OWN, AmsMapping.decide(np, emptyList(), a2dpSamePhone = false))
        // The same iPhone streams to the Echo over A2DP: one entry.
        assertEquals(Show.MERGED, AmsMapping.decide(np, listOf(session("bluetooth", MediaSourceKind.BLUETOOTH, true)), a2dpSamePhone = true))
        // AirPlay (or another phone's Bluetooth) playing on the Echo wins over the remote-only iPhone.
        assertEquals(Show.HIDDEN, AmsMapping.decide(np, listOf(session("airplay", MediaSourceKind.AIRPLAY, true)), a2dpSamePhone = false))
        assertEquals(Show.HIDDEN, AmsMapping.decide(np, listOf(session("bluetooth", MediaSourceKind.BLUETOOTH, true)), a2dpSamePhone = false))
        // Paused audio on the Echo doesn't hide it.
        assertEquals(Show.OWN, AmsMapping.decide(np, listOf(session("airplay", MediaSourceKind.AIRPLAY, false)), a2dpSamePhone = false))
        assertEquals(Show.HIDDEN, AmsMapping.decide(AmsNowPlaying(), emptyList(), a2dpSamePhone = false))
        assertTrue(AmsMapping.sameTrack("Holocene ", "bon iver", "holocene", "Bon Iver"))
        assertFalse(AmsMapping.sameTrack("Holocene", "Bon Iver", "Towers", "Bon Iver"))
    }

    // --- artwork lookup ---

    @Test fun itunesSearchUrlAndArtwork() {
        assertEquals("https://itunes.apple.com/search?term=Bon+Iver+Holocene&entity=song&limit=5", ItunesArt.searchUrl("Bon Iver", "Holocene"))
        assertEquals("https://itunes.apple.com/search?term=Sigur+R%C3%B3s+Hopp%C3%ADpolla&entity=song&limit=5", ItunesArt.searchUrl("Sigur Rós", "Hoppípolla"))
        // As seen on the Echo: a cover version ranks first; the artist's own record is used.
        val json = """{"resultCount":2,"results":[{"wrapperType":"track","kind":"song","artistName":"Vitamin String Quartet","artworkUrl100":"https:\/\/is1-ssl.mzstatic.com\/image\/thumb\/Music\/vsq\/100x100bb.jpg"},""" +
            """{"wrapperType":"track","kind":"song","artistName":"Bon Iver","artworkUrl60":"https:\/\/is1-ssl.mzstatic.com\/image\/thumb\/Music\/v4\/a1\/60x60bb.jpg","artworkUrl100":"https:\/\/is1-ssl.mzstatic.com\/image\/thumb\/Music\/v4\/a1\/b2\/source\/100x100bb.jpg"}]}"""
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/a1/b2/source/600x600bb.jpg", ItunesArt.artworkUrl(json, "bon iver"))
        // A podcast title that only matches someone else's song: no art.
        assertNull(ItunesArt.artworkUrl(json, "The Slow Science Show"))
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/x/600x600bb.jpg",
            ItunesArt.artworkUrl("""{"results":[{"wrapperType":"track","artistName":"Sigur Rós","artworkUrl100":"https:\/\/is1-ssl.mzstatic.com\/image\/thumb\/Music\/x\/100x100bb.jpg"}]}""", "Sigur Rós"))
        assertNull(ItunesArt.artworkUrl("""{"resultCount":0,"results":[]}""", "Bon Iver"))
        assertNull(ItunesArt.artworkUrl("""{"results":[{"wrapperType":"track","artistName":"Bon Iver","artworkUrl100":"http:\/\/example.com\/100x100bb.jpg"}]}""", "Bon Iver"))
        assertEquals(ItunesArt.key("Bon Iver", "Holocene"), ItunesArt.key(" bon iver", "HOLOCENE "))
    }
}
