package com.echobedside.feature.airplay

import com.echobedside.core.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayMathTest {
    @Test fun volumeMapping() {
        assertEquals(0f, AirPlayMath.volumeFraction(-144f), 0f)
        assertEquals(0f, AirPlayMath.volumeFraction(-30f), 0f)
        assertEquals(0.5f, AirPlayMath.volumeFraction(-15f), 1e-6f)
        assertEquals(1f, AirPlayMath.volumeFraction(0f), 0f)
        assertEquals(-144f, AirPlayMath.dbForFraction(0f), 0f)
        assertEquals(-15f, AirPlayMath.dbForFraction(0.5f), 1e-6f)
        assertEquals(0f, AirPlayMath.dbForFraction(1f), 0f)
        assertEquals(0f, AirPlayMath.gain(-144f), 0f)
        assertEquals(1f, AirPlayMath.gain(0f), 1e-6f)
        assertEquals(0.0316f, AirPlayMath.gain(-30f), 1e-4f)
        assertEquals(50, AirPlayMath.dacpVolume(0.5f))
        assertEquals(100, AirPlayMath.dacpVolume(2f))
    }

    @Test fun progressFromRtp() {
        // 10 s into a 3-minute track.
        val start = 1_000_000L
        val (pos, dur) = AirPlayMath.progress(start, start + 441_000, start + 180 * 44_100)
        assertEquals(10_000L, pos)
        assertEquals(180_000L, dur)
    }

    @Test fun progressWrapsAroundU32() {
        val start = 0xFFFF_FFFFL - 44_100 // 1 s before the wrap
        val current = 44_100L - 1          // 1 s after it
        val end = (start + 60 * 44_100) and 0xFFFF_FFFFL
        val (pos, dur) = AirPlayMath.progress(start, current, end)
        assertEquals(2_000L, pos)
        assertEquals(60_000L, dur)
    }

    @Test fun progressBeforeStartIsZero() {
        val (pos, _) = AirPlayMath.progress(1_000_000, 999_000, 2_000_000)
        assertEquals(0L, pos)
    }
}

class ReceiverStateTest {
    private fun connected(now: Long = 0): ReceiverState {
        var s = Reducer.clientConnected(ReceiverState(), "10.0.0.5")
        s = Reducer.audioStart(s, now)
        return s
    }

    @Test fun nothingPublishedUntilStreamOrMetadata() {
        val s = Reducer.clientConnected(ReceiverState(), "10.0.0.5")
        assertNull(s.toMediaState(null))
        assertFalse(s.connected)
        assertTrue(Reducer.markConnected(s).connected)
        assertNotNull(Reducer.metadata(s, "Song", "Artist", "Album", -1, 0).toMediaState(null))
    }

    @Test fun mediaStateMapping() {
        var s = connected()
        s = Reducer.metadata(s, "Holocene", "Bon Iver", "Bon Iver, Bon Iver", 337_000, 100)
        s = Reducer.senderName(s, "10.0.0.5", "Maya's iPhone")
        s = Reducer.volume(s, -15f)
        s = Reducer.flowing(s, true, 1_000)
        s = Reducer.coverArt(s, 1_100)
        val m = s.toMediaState(null)!!
        assertEquals("Holocene", m.title)
        assertEquals("Bon Iver", m.artist)
        assertEquals("Bon Iver, Bon Iver", m.album)
        assertEquals(337_000L, m.durationMs)
        assertTrue(m.playing)
        assertEquals(MediaSourceKind.AIRPLAY, m.source)
        assertEquals("Maya's iPhone", m.sourceLabel)
        assertEquals(0.5f, m.volume!!, 1e-6f)
        assertTrue(s.connected)
    }

    @Test fun defaultsWithoutMetadata() {
        val m = connected().toMediaState(null)!!
        assertEquals("", m.title)
        assertEquals(ReceiverState.DEFAULT_SENDER_LABEL, m.sourceLabel)
        assertNull(m.volume)
    }

    @Test fun pauseFreezesPosition() {
        var s = connected()
        s = Reducer.progress(s, 0, 44_100 * 5, 44_100 * 60, 10_000) // at 5 s
        s = Reducer.flowing(s, true, 10_000)
        assertEquals(7_000L, s.positionNow(12_000))
        s = Reducer.flowing(s, false, 12_000)
        assertFalse(s.playing)
        assertEquals(7_000L, s.positionNow(50_000))
        s = Reducer.flowing(s, true, 50_000)
        assertEquals(8_000L, s.positionNow(51_000))
        // Flush (pause / seek) also freezes.
        s = Reducer.flush(s, 51_000)
        assertEquals(8_000L, s.positionNow(60_000))
    }

    @Test fun positionClampsToDuration() {
        var s = Reducer.progress(connected(), 0, 44_100 * 59, 44_100 * 60, 0)
        s = Reducer.flowing(s, true, 0)
        assertEquals(60_000L, s.positionNow(10_000))
    }

    @Test fun newTrackClearsArtworkUnlessItJustArrived() {
        var s = Reducer.metadata(connected(), "A", "X", "Alb", -1, 0)
        s = Reducer.coverArt(s, 100)
        // Same track again: keep.
        assertNotNull(Reducer.metadata(s, "A", "X", "Alb", -1, 10_000).artworkAt)
        // New track long after the artwork: clear.
        assertNull(Reducer.metadata(s, "B", "X", "Alb", -1, 10_000).artworkAt)
        // New track right after new artwork arrived (artwork first): keep.
        assertNotNull(Reducer.metadata(Reducer.coverArt(s, 9_500), "B", "X", "Alb", -1, 10_000).artworkAt)
    }

    @Test fun newTrackResetsPositionAndDuration() {
        var s = Reducer.metadata(connected(), "A", "X", "", 200_000, 0)
        s = Reducer.progress(s, 0, 44_100 * 100, 44_100 * 200, 0)
        s = Reducer.metadata(s, "B", "X", "", -1, 5_000)
        assertEquals(0L, s.positionMs)
        assertEquals(0L, s.durationMs)
    }

    @Test fun progressJustBeforeNewTrackTextIsKept() {
        // pyatv / iTunes order: progress, then text, then artwork.
        var s = Reducer.metadata(connected(), "A", "X", "", -1, 0)
        s = Reducer.progress(s, 0, 0, 44_100 * 120, 60_000)
        s = Reducer.metadata(s, "B", "X", "", -1, 60_200)
        assertEquals(120_000L, s.durationMs)
        assertEquals(0L, s.positionMs)
        assertEquals(60_000L, s.positionAt)
    }

    @Test fun localPauseShowsPausedAndFreezes() {
        var s = Reducer.flowing(Reducer.progress(connected(), 0, 0, 44_100 * 60, 0), true, 0)
        s = Reducer.locallyPaused(s, true, 3_000)
        val m = s.toMediaState(null)!!
        assertFalse(m.playing)
        assertEquals(3_000L, s.positionNow(9_000))
        s = Reducer.locallyPaused(s, false, 9_000)
        assertTrue(s.toMediaState(null)!!.playing)
        assertEquals(4_000L, s.positionNow(10_000))
    }

    @Test fun multipleConnectionsPerSender() {
        var s = Reducer.clientConnected(ReceiverState(), "10.0.0.5")
        s = Reducer.clientConnected(s, "10.0.0.5")
        s = Reducer.audioStart(s, 0)
        s = Reducer.senderName(s, "10.0.0.5", "Mac")
        s = Reducer.clientDisconnected(s, "10.0.0.5")
        assertTrue(s.anyConnection)
        assertEquals("Mac", s.senderName)
        s = Reducer.volume(s, -10f)
        s = Reducer.clientDisconnected(s, "10.0.0.5")
        assertFalse(s.anyConnection)
        assertFalse(s.connected)
        assertNull(s.senderName)
        assertNull(s.toMediaState(null))
        assertEquals(-10f, s.volumeDb!!, 0f) // the sender's volume survives for the next stream
    }

    @Test fun routeChips() {
        var s = Reducer.audioStart(Reducer.clientConnected(ReceiverState(), "10.0.0.5"), 0, 44_100, 2)
        assertEquals(listOf("AirPlay 2", "44.1 kHz"), s.toMediaState(null)!!.route)
        s = Reducer.senderName(s, "10.0.0.5", "Maya's iPhone")
        assertEquals(listOf("AirPlay 2", "44.1 kHz", "Maya's iPhone"), s.route())
        assertEquals(listOf("AirPlay", "48 kHz", "Mono"), ReceiverState(ap2 = false, sampleRate = 48_000, channels = 1).route())
        assertEquals("44.1 kHz", ReceiverState.formatRate(44_100))
        assertEquals("48 kHz", ReceiverState.formatRate(48_000))
        assertEquals("96 kHz", ReceiverState.formatRate(96_000))
    }

    @Test fun modeSurvivesLastDisconnect() {
        var s = Reducer.clientConnected(ReceiverState(ap2 = false), "10.0.0.5")
        s = Reducer.clientDisconnected(s, "10.0.0.5")
        assertFalse(s.ap2)
    }

    @Test fun nameForUnknownIpIgnored() {
        val s = Reducer.senderName(connected(), "10.0.0.9", "Other")
        assertNull(s.senderName)
    }

    @Test fun audioStopKeepsMetadataWhileConnected() {
        var s = Reducer.metadata(connected(), "A", "X", "", -1, 0)
        s = Reducer.flowing(s, true, 0)
        s = Reducer.audioStop(s, 1_000)
        assertFalse(s.streaming)
        val m = s.toMediaState(null)!!
        assertFalse(m.playing)
        assertEquals("A", m.title)
    }
}

class ServiceInfoTest {
    private val sample = """
        port=7000
        raop_name=AABBCCDDEEFF@Echo Show
        airplay_name=Echo Show
        ap2=true
        raop.cn=0,1
        raop.et=0,3,5
        raop.ft=0x405C4A00,0x1C340
        airplay.deviceid=AA:BB:CC:DD:EE:FF
        airplay.features=0x405C4A00,0x1C340
        airplay.model=AudioAccessory5,1
    """.trimIndent()

    @Test fun parses() {
        val i = ServiceInfo.parse(sample)
        assertEquals(7000, i.port)
        assertEquals("AABBCCDDEEFF@Echo Show", i.raopName)
        assertEquals("Echo Show", i.airplayName)
        assertTrue(i.ap2)
        assertEquals(listOf("cn" to "0,1", "et" to "0,3,5", "ft" to "0x405C4A00,0x1C340"), i.raopTxt)
        assertEquals("AudioAccessory5,1", i.airplayTxt.toMap()["model"])
        // Values containing '=' survive (only the first '=' splits).
        assertEquals("a=b", ServiceInfo.parse("raop.x=a=b").raopTxt.single().second)
    }

    @Test fun txtRecordsFitMdnsLimits() {
        for ((k, v) in ServiceInfo.parse(sample).raopTxt) assertTrue(k.length + v.length + 1 <= 255)
    }
}

class SenderNameTest {
    @Test fun prettyHostNames() {
        assertEquals("Mayas iPhone", SenderNameResolver.prettyHostName("Mayas-iPhone.lan", "10.0.0.5"))
        assertEquals("MacBook Pro", SenderNameResolver.prettyHostName("MacBook-Pro.local", "10.0.0.5"))
        assertNull(SenderNameResolver.prettyHostName("10.0.0.5", "10.0.0.5"))
        assertNull(SenderNameResolver.prettyHostName("", "10.0.0.5"))
    }
}

class AirPlaySettingsTest {
    @Test fun sectionCoversEveryUserSetting() {
        val section = AirPlayFeature.settingsSection()
        val byKey = section.settings.associateBy { it.key }
        assertEquals(
            setOf(AirPlayFeature.KEY_ENABLED, AirPlayFeature.KEY_NAME, AirPlayFeature.KEY_MODE, AirPlayFeature.KEY_MDNS),
            byKey.keys,
        )
        assertEquals("true", byKey.getValue(AirPlayFeature.KEY_ENABLED).default)
        assertEquals("", byKey.getValue(AirPlayFeature.KEY_NAME).default)
        assertTrue(byKey.getValue(AirPlayFeature.KEY_NAME).help!!.contains("device name"))
        val mode = byKey.getValue(AirPlayFeature.KEY_MODE)
        assertEquals(AirPlayFeature.MODE_AP1, mode.default)
        assertEquals(listOf(AirPlayFeature.MODE_AP1, AirPlayFeature.MODE_AP2), mode.options.map { it.first })
        assertFalse(mode.advanced)
        assertTrue(byKey.getValue(AirPlayFeature.KEY_MDNS).advanced)
        // Every CHOICE default is one of its options.
        for (s in section.settings.filter { it.options.isNotEmpty() }) assertTrue(s.options.any { it.first == s.default })
    }
}
