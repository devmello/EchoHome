package com.echobedside.feature.system.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudQueueTest {
    private val vol = { v: Float -> Hud.Level(LevelKind.VOLUME, v) }
    private val micOff = Hud.Message(MessageKind.MIC_OFF)
    private val micOn = Hud.Message(MessageKind.MIC_ON)
    private val camOff = Hud.Message(MessageKind.CAMERA_OFF_SHUTTER)

    @Test fun levelUpdatesInPlaceAndExpiresAfterLastChange() {
        val q = HudQueue()
        q.post(vol(0.3f), 0)
        q.post(vol(0.4f), 1000)
        assertEquals(vol(0.4f), q.current)
        assertFalse(q.tick(2000))               // 1000 + 1500 not reached
        assertTrue(q.tick(2500))
        assertNull(q.current)
    }

    @Test fun messageReplacesLevelImmediately() {
        val q = HudQueue()
        q.post(vol(0.3f), 0)
        q.post(micOff, 100)
        assertEquals(micOff, q.current)
        q.tick(100 + HudQueue.MESSAGE_MS)
        assertNull("the interrupted level isn't resurrected", q.current)
    }

    @Test fun levelWaitsBehindMessageNewestOnly() {
        val q = HudQueue()
        q.post(micOff, 0)
        q.post(vol(0.2f), 100)
        q.post(vol(0.5f), 200)
        assertEquals(micOff, q.current)
        q.tick(HudQueue.MESSAGE_MS)
        assertEquals(vol(0.5f), q.current)
        assertEquals(HudQueue.MESSAGE_MS + HudQueue.LEVEL_MS, q.deadline())
    }

    @Test fun messagesQueueInOrder() {
        val q = HudQueue()
        q.post(micOff, 0)
        q.post(camOff, 10)
        assertEquals(micOff, q.current)
        q.tick(HudQueue.MESSAGE_MS)
        assertEquals(camOff, q.current)
        q.tick(2 * HudQueue.MESSAGE_MS)
        assertNull(q.current)
    }

    @Test fun sameSubjectReplacesInsteadOfQueueing() {
        val q = HudQueue()
        q.post(micOff, 0)
        q.post(micOn, 500)
        assertEquals(micOn, q.current)
        assertEquals(500 + HudQueue.MESSAGE_MS, q.deadline())
        // queued one is replaced too
        q.post(camOff, 600)
        q.post(Hud.Message(MessageKind.CAMERA_OFF), 700)
        q.tick(500 + HudQueue.MESSAGE_MS)
        assertEquals(Hud.Message(MessageKind.CAMERA_OFF), q.current)
        q.tick(10_000)
        assertNull(q.current)
    }

    @Test fun badgeSuppressionFollowsTheCapsule() {
        val q = HudQueue()
        q.post(micOff, 0)
        assertTrue(q.showing(MessageKind.Subject.MIC))
        assertFalse(q.showing(MessageKind.Subject.CAMERA))
        q.tick(HudQueue.MESSAGE_MS)
        assertFalse(q.showing(MessageKind.Subject.MIC))
    }
}
