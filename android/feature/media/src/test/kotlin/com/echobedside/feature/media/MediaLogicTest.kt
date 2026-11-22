package com.echobedside.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {
    private fun image(vararg parts: Pair<Int, Int>): IntArray {
        val out = ArrayList<Int>()
        for ((color, count) in parts) repeat(count) { out += color }
        return out.toIntArray()
    }

    private fun hue(c: Int) = rgbToHsl(c)[0]
    private fun light(c: Int) = rgbToHsl(c)[2]

    @Test fun coastlineArtGivesTealBaseAndWarmAccents() {
        // Proportions of the "Coastline Hours" stand-in art: deep teal water and sky, a warm band, the sun.
        val px = image(
            0xFF0F3E48.toInt() to 380, 0xFF06191E.toInt() to 160, 0xFF17485A.toInt() to 170,
            0xFFB9654E.toInt() to 120, 0xFFFF8A4C.toInt() to 90, 0xFFF7B98A.toInt() to 80,
        )
        val p = PaletteExtractor.extract(px)
        assertTrue("a teal, was ${hue(p.a)}", hue(p.a) in 170f..215f)
        assertTrue("b warm, was ${hue(p.b)}", hue(p.b) < 45f || hue(p.b) > 340f)
        assertTrue("c warm and light", (hue(p.c) < 50f || hue(p.c) > 340f) && light(p.c) >= 0.58f)
        assertTrue("base dark", light(p.base) < 0.12f)
        assertTrue("base hue follows a", hueDistance(hue(p.base), hue(p.a)) < 20f)
    }

    @Test fun purpleArtGivesPurpleAndPink() {
        val px = image(
            0xFF2A0F57.toInt() to 500, 0xFF12052A.toInt() to 250, 0xFF6A34B8.toInt() to 150,
            0xFFFF4F9A.toInt() to 60, 0xFFFFC2DD.toInt() to 40,
        )
        val p = PaletteExtractor.extract(px)
        assertTrue("a purple, was ${hue(p.a)}", hue(p.a) in 250f..290f)
        assertTrue("b pink, was ${hue(p.b)}", hue(p.b) in 300f..360f)
    }

    @Test fun greyscaleStaysGrey() {
        val px = image(0xFF202020.toInt() to 300, 0xFF808080.toInt() to 300, 0xFFE0E0E0.toInt() to 300)
        val p = PaletteExtractor.extract(px)
        for (c in listOf(p.base, p.a, p.b, p.c)) assertTrue(rgbToHsl(c)[1] <= 0.13f)
    }

    @Test fun emptyOrTransparentFallsBackToNeutral() {
        assertEquals(AmbientPalette.NEUTRAL, PaletteExtractor.extract(IntArray(0)))
        assertEquals(AmbientPalette.NEUTRAL, PaletteExtractor.extract(IntArray(64) { 0x00FF0000 }))
    }

    @Test fun quantizeKeepsASingleColourAndCapsTheCount() {
        val one = PaletteExtractor.quantize(IntArray(100) { 0xFF336699.toInt() }, 16)
        assertEquals(1, one.size)
        assertEquals(100, one[0].population)
        val many = PaletteExtractor.quantize(IntArray(4096) { (0xFF shl 24) or (it * 4099) }, 16)
        assertTrue(many.size <= 16)
        assertEquals(4096, many.sumOf { it.population })
    }

    @Test fun hslRoundTrips() {
        for (c in listOf(0xFF1E6A78.toInt(), 0xFFE0784C.toInt(), 0xFF9B7BFF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val (h, s, l) = rgbToHsl(c)
            val back = hslToRgb(h, s, l)
            for (shift in listOf(0, 8, 16)) assertTrue(kotlin.math.abs(((c shr shift) and 255) - ((back shr shift) and 255)) <= 1)
        }
    }

    @Test fun blendEnds() {
        assertEquals(0xFF000000.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        assertEquals(0xFF808080.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }
}

class FormatTest {
    @Test fun clockTime() {
        assertEquals("0:00", Format.clockTime(0))
        assertEquals("1:23", Format.clockTime(83))
        assertEquals("12:05", Format.clockTime(725))
        assertEquals("1:02:03", Format.clockTime(3723))
        assertEquals("0:00", Format.clockTime(-5))
    }

    @Test fun progressLabelsMatchTheDesign() {
        // Design: pos 83 of 227 s → "1:23" / "-2:24".
        assertEquals("1:23" to "-2:24", Format.progressLabels(83_000, 227_000))
        // Partial seconds: elapsed floors, the two still add up.
        assertEquals("1:23" to "-2:24", Format.progressLabels(83_900, 227_000))
        assertEquals("3:47" to "-0:00", Format.progressLabels(300_000, 227_000))
        assertEquals("0:42" to "", Format.progressLabels(42_000, 0))
    }

    @Test fun fraction() {
        assertEquals(0f, Format.fraction(10, 0))
        assertEquals(0.5f, Format.fraction(50, 100))
        assertEquals(1f, Format.fraction(150, 100))
    }

    @Test fun wallClock() {
        assertEquals("9:41", Format.wallClock(9, 41, false))
        assertEquals("12:05", Format.wallClock(0, 5, false))
        assertEquals("9:41", Format.wallClock(21, 41, false))
        assertEquals("21:41", Format.wallClock(21, 41, true))
    }

    @Test fun subtitle() {
        assertEquals("Marisol Vey — Coastline Hours", Format.subtitle("Marisol Vey", "Coastline Hours"))
        assertEquals("Marisol Vey", Format.subtitle("Marisol Vey", ""))
        assertEquals("Podcast", Format.subtitle(" ", "Podcast"))
    }
}

class SessionMappingTest {
    private val base = SessionSnapshot(
        packageName = "com.example.player", appLabel = "Player",
        title = "Song", artist = "Band", album = "Record",
        durationMs = 200_000, playbackState = SessionMapping.STATE_PLAYING,
        positionMs = 30_000, lastPositionUpdate = 1_000, speed = 1f,
        actions = SessionMapping.ACTION_SEEK_TO,
    )

    @Test fun playingSessionMaps() {
        val m = SessionMapping.toMediaState(base, now = 5_000)
        assertNotNull(m); m!!
        assertEquals("Song", m.title)
        assertEquals("Band", m.artist)
        assertEquals("Record", m.album)
        assertTrue(m.playing)
        assertEquals(30_000, m.positionMs)
        assertEquals(1_000, m.positionSampledAt)
        // Extrapolates from the sample time.
        assertEquals(34_000, m.positionNow(5_000))
        assertEquals("Player", m.sourceLabel)
        assertNull(m.volume)
        assertTrue(SessionMapping.canSeek(base))
    }

    @Test fun pausedAndBufferingDontAdvance() {
        val paused = SessionMapping.toMediaState(base.copy(playbackState = SessionMapping.STATE_PAUSED), now = 9_000)!!
        assertFalse(paused.playing)
        assertEquals(30_000, paused.positionNow(9_000))
        val buffering = SessionMapping.toMediaState(base.copy(playbackState = SessionMapping.STATE_BUFFERING), now = 9_000)!!
        assertFalse(buffering.playing)
    }

    @Test fun nonUnitSpeedIsRebasedAtNow() {
        val m = SessionMapping.toMediaState(base.copy(speed = 2f), now = 3_000)!!
        assertEquals(34_000, m.positionMs)
        assertEquals(3_000, m.positionSampledAt)
    }

    @Test fun fallbacksForTitleAndArtist() {
        val s = base.copy(title = null, displayTitle = "Episode 4", artist = "", albumArtist = null, displaySubtitle = "A Podcast")
        val m = SessionMapping.toMediaState(s, 0)!!
        assertEquals("Episode 4", m.title)
        assertEquals("A Podcast", m.artist)
    }

    @Test fun stoppedNoneErrorOrUntitledAreHidden() {
        for (st in listOf(SessionMapping.STATE_NONE, SessionMapping.STATE_STOPPED, SessionMapping.STATE_ERROR))
            assertNull(SessionMapping.toMediaState(base.copy(playbackState = st), 0))
        assertNull(SessionMapping.toMediaState(base.copy(title = " ", displayTitle = null), 0))
    }

    @Test fun durationClampsAndUnknownDurationCantSeek() {
        val over = SessionMapping.toMediaState(base.copy(positionMs = 500_000), 1_000)!!
        assertEquals(200_000, over.positionMs)
        val live = base.copy(durationMs = -1)
        assertFalse(SessionMapping.canSeek(live))
        assertEquals(0, SessionMapping.toMediaState(live, 0)!!.durationMs)
        assertFalse(SessionMapping.canSeek(base.copy(actions = 0)))
    }

    @Test fun remoteVolumeIsClamped() {
        assertEquals(1f, SessionMapping.toMediaState(base.copy(remoteVolume = 1.4f), 0)!!.volume)
        assertEquals(0.25f, SessionMapping.toMediaState(base.copy(remoteVolume = 0.25f), 0)!!.volume)
    }

    @Test fun skipAndPauseFromActions() {
        assertTrue(SessionMapping.canSkip(base.copy(actions = 0)))
        assertFalse(SessionMapping.canSkip(base.copy(actions = SessionMapping.ACTION_SEEK_TO)))
        assertTrue(SessionMapping.canSkip(base.copy(actions = SessionMapping.ACTION_SKIP_TO_NEXT)))
        assertFalse(SessionMapping.canPause(base.copy(actions = SessionMapping.ACTION_SEEK_TO)))
        assertTrue(SessionMapping.canPause(base.copy(actions = SessionMapping.ACTION_PLAY_PAUSE)))
    }

    @Test fun bluetoothAndOwnSessionsAreNotMirrored() {
        // :feature:phone publishes the phone's Bluetooth audio itself (MediaSourceKind.BLUETOOTH).
        assertFalse(SessionMapping.isMirrored("com.android.bluetooth", "com.echobedside"))
        assertFalse(SessionMapping.isMirrored("com.echobedside", "com.echobedside"))
        assertTrue(SessionMapping.isMirrored("org.lineageos.eleven", "com.echobedside"))
    }

    @Test fun sourceIds() {
        assertEquals("app:com.spotify.music:main", SessionMapping.sourceId("com.spotify.music", "main"))
    }
}
