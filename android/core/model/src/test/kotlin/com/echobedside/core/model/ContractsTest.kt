package com.echobedside.core.model

import com.echobedside.core.model.fake.nextTrigger
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContractsTest {
    private fun act(id: String, kind: ActivityKind, owner: String? = null, stale: Long? = null) =
        LiveActivity(id, kind, LiveContent.AirPlayConnected("x"), ownerScreen = owner, staleAfterElapsed = stale)

    @Test fun islandShowsTopTwoByPriority() {
        val m = DefaultIslandManager()
        m.publish(act("t", ActivityKind.TIMER))
        m.publish(act("m", ActivityKind.MUSIC))
        m.publish(act("d", ActivityKind.DOORBELL))
        assertEquals(listOf("d", "m"), m.visible(null, now = 0).map { it.id })
    }

    @Test fun islandHidesOwnScreenAndStale() {
        val m = DefaultIslandManager()
        m.publish(act("m", ActivityKind.MUSIC, owner = "nowplaying"))
        m.publish(act("t", ActivityKind.TIMER, stale = 100))
        m.publish(act("a", ActivityKind.AIRPLAY_CONNECTED))
        assertEquals(listOf("a"), m.visible("nowplaying", now = 200).map { it.id })
    }

    @Test fun publishReplacesSameId() {
        val m = DefaultIslandManager()
        m.publish(act("x", ActivityKind.TIMER))
        m.publish(act("x", ActivityKind.TIMER_DONE))
        assertEquals(1, m.activities.value.size)
        assertEquals(ActivityKind.TIMER_DONE, m.activities.value.single().kind)
    }

    private object NoControls : MediaControls {
        override fun play() {}; override fun pause() {}; override fun next() {}; override fun previous() {}
        override fun seekTo(positionMs: Long) {}; override fun setVolume(volume: Float) {}
    }

    @Test fun mediaHubPrefersPlaying() {
        val hub = DefaultMediaHub()
        hub.publish("a", MediaState("A", "x", source = MediaSourceKind.AIRPLAY, playing = true, positionSampledAt = 0), NoControls)
        hub.publish("b", MediaState("B", "y", source = MediaSourceKind.OTHER_APP, playing = false, positionSampledAt = 0), NoControls)
        assertEquals("a", hub.current.value?.sourceId)
        hub.clear("a")
        assertEquals("b", hub.current.value?.sourceId)
        hub.clear("b")
        assertNull(hub.current.value)
    }

    @Test fun positionExtrapolatesWhilePlaying() {
        val s = MediaState("t", "a", durationMs = 10_000, positionMs = 1_000, positionSampledAt = 500, playing = true, source = MediaSourceKind.AIRPLAY)
        assertEquals(3_000, s.positionNow(now = 2_500))
        assertEquals(10_000, s.positionNow(now = 99_000))
        assertEquals(1_000, s.copy(playing = false).positionNow(now = 2_500))
    }

    @Test fun nextTriggerHonoursDays() {
        val fri = LocalDateTime.of(2026, 11, 20, 8, 0) // Friday 08:00
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val t = nextTrigger(Alarm("a", 7, 0, weekdays), fri, ZoneOffset.UTC)
        assertEquals(LocalDateTime.of(2026, 11, 23, 7, 0).toEpochSecond(ZoneOffset.UTC) * 1000, t) // Monday
        val once = nextTrigger(Alarm("b", 9, 30), fri, ZoneOffset.UTC)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30).toEpochSecond(ZoneOffset.UTC) * 1000, once)
    }

    @Test fun systemStateDerivedFlags() {
        assertEquals(true, SystemState(micMutedHardware = true).cameraBlocked)
        assertEquals(true, SystemState(micMutedSoftware = true).micMuted)
        assertEquals(false, SystemState().cameraBlocked)
    }
}
