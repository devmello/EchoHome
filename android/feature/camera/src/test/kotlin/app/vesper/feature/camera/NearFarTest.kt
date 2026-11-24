package app.vesper.feature.camera

import app.vesper.core.model.Presence
import app.vesper.core.model.Presence.EMPTY
import app.vesper.core.model.Presence.PRESENT
import app.vesper.core.model.Presence.UNKNOWN
import app.vesper.core.model.ViewDistance
import app.vesper.core.model.ViewDistance.FAR
import app.vesper.core.model.ViewDistance.NEAR
import app.vesper.feature.camera.presence.MotionDetector
import app.vesper.feature.camera.presence.NearFar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearFarTest {
    /** A small script runner: time in ms, every input followed by an update like the pipeline does. */
    private class Run(val nf: NearFar = NearFar()) {
        var now = 0L
        var presence = PRESENT
        val changes = ArrayList<Pair<Long, ViewDistance>>()
        private var last = nf.state
        fun at(t: Long) = apply { now = t }
        fun update(): ViewDistance { val s = nf.update(now, presence); if (s != last) { changes += now to s; last = s }; return s }
        fun touch() = apply { nf.onTouch(now); update() }
        fun sample(motion: Boolean, area: Float = 0f, rows: Float = 0f) = apply { nf.onSample(now, motion, area, rows); update() }
        fun far() = sample(true, 0.04f, 0.3f)
        fun close() = sample(true, 0.35f, 0.9f)
        fun still() = sample(false)
        fun presence(p: Presence) = apply { presence = p; update() }
        /** 1 fps samples from [from] to [to] (inclusive), each made by [s]. */
        fun every(from: Long, to: Long, s: Run.() -> Unit) = apply { var t = from; while (t <= to) { at(t); s(); t += 1000 } }
        val state get() = nf.state
    }

    @Test fun startsNearWithoutEvidence() {
        val r = Run()
        r.every(0, 120_000) { still() }
        assertEquals(NEAR, r.state)
        assertTrue(r.changes.isEmpty())
    }

    @Test fun emptyRoomIsFar() {
        val r = Run().presence(EMPTY)
        assertEquals(FAR, r.state)
    }

    @Test fun unknownIsAlwaysNear() {
        val r = Run().presence(EMPTY)
        assertEquals(FAR, r.state)
        r.at(1000).presence(UNKNOWN)
        assertEquals(NEAR, r.state)
        // far motion while the camera can't tell changes nothing
        r.at(2000).far()
        assertEquals(NEAR, r.state)
    }

    @Test fun disabledIsAlwaysNear() {
        val r = Run()
        r.nf.enabled = false
        r.presence(EMPTY).at(1000).far()
        assertEquals(NEAR, r.state)
        r.nf.enabled = true
        r.at(2000).update()
        assertEquals(FAR, r.state)
    }

    @Test fun smallMotionAcrossTheRoomIsFar() {
        val r = Run()
        r.at(1000).far()
        assertEquals(FAR, r.state)
    }

    @Test fun sidewaysWalkAcrossTheRoomStaysFar() {
        // Wide change (walking sideways, 1 s apart) but only half the frame's height.
        val r = Run().presence(EMPTY)
        r.every(1000, 6000) { sample(true, 0.24f, 0.45f) }
        assertEquals(FAR, r.state)
    }

    @Test fun touchIsNearAtOnceAndHolds() {
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).far()
        assertEquals(FAR, r.state)
        r.at(2000).touch()
        assertEquals(NEAR, r.state)
        // they walk away right after: far evidence, but the touch holds NEAR for 60 s
        r.every(3000, 61_000) { far() }
        assertEquals(NEAR, r.state)
        r.at(62_000).far()
        assertEquals(FAR, r.state)
        assertEquals(listOf(0L to FAR, 2000L to NEAR, 62_000L to FAR), r.changes)
    }

    @Test fun holdEndsOnlyWithFarEvidence() {
        // Touched, then nothing seen (camera resting, or a person sitting still): stays NEAR.
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).touch()
        r.every(2000, 300_000) { still() }
        assertEquals(NEAR, r.state)
        r.at(301_000).presence(EMPTY)
        assertEquals(FAR, r.state)
    }

    @Test fun nextCheckAtFiresTheHoldEnd() {
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).touch()
        r.at(5000).far()
        assertEquals(NEAR, r.state)
        assertEquals(61_000L, r.nf.nextCheckAt(5000))
        r.at(61_000).update()
        assertEquals(FAR, r.state)
        assertNull(r.nf.nextCheckAt(61_000))
    }

    @Test fun oneCloseFrameIsNotEnough() {
        val r = Run()
        r.at(1000).far()
        r.at(2000).close()
        assertEquals(FAR, r.state)
        r.every(3000, 10_000) { far() }
        assertEquals(FAR, r.state)
    }

    @Test fun twoCloseFramesAreNear() {
        val r = Run()
        r.at(1000).far()
        r.at(2000).close()
        r.at(3000).close()
        assertEquals(NEAR, r.state)
    }

    @Test fun closeFramesTooFarApartDontPair() {
        val r = Run()
        r.at(1000).far()
        r.at(2000).close()
        r.at(10_000).close()
        assertEquals(FAR, r.state)
    }

    @Test fun walkingUpIsNear() {
        // The change grows frame to frame as someone walks straight at the Echo.
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).sample(true, 0.06f, 0.55f)
        assertEquals(FAR, r.state)
        r.at(2000).sample(true, 0.13f, 0.6f)   // approach: 2.2×, ≥ 0.12
        assertEquals(FAR, r.state)
        r.at(3000).sample(true, 0.22f, 0.75f)  // close
        assertEquals(NEAR, r.state)
        assertEquals("close motion", r.nf.reason)
    }

    @Test fun growingButStillSmallIsNotAnApproach() {
        val r = Run()
        r.at(1000).sample(true, 0.02f, 0.3f)
        r.at(2000).sample(true, 0.05f, 0.4f)
        r.at(3000).sample(true, 0.09f, 0.45f)
        assertEquals(FAR, r.state)
    }

    @Test fun nearHoldsAfterCameraEvidence() {
        val r = Run()
        r.at(1000).far()
        r.at(2000).close(); r.at(3000).close()
        assertEquals(NEAR, r.state)
        r.every(4000, 47_000) { far() }
        assertEquals(NEAR, r.state) // 45 s hold from 3 s
        r.at(48_000).far()
        assertEquals(FAR, r.state)
    }

    @Test fun neverFlickers() {
        // Two close frames every 50 s, far motion in between, for 10 minutes: NEAR always lasts a hold.
        val r = Run()
        r.every(0, 600_000) { if ((now / 1000) % 50 < 2) close() else far() }
        assertTrue(r.changes.size >= 10)
        for (i in 1 until r.changes.size) {
            val (t0, s0) = r.changes[i - 1]
            val (t1, _) = r.changes[i]
            if (s0 == NEAR) assertTrue("NEAR lasted ${t1 - t0} ms", t1 - t0 >= r.nf.seenHoldMs)
        }
        assertTrue(r.changes.size <= 2 * 600_000 / r.nf.seenHoldMs + 2)
    }

    @Test fun faceSizeDecides() {
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT)
        r.nf.onFace(1000, 24f, 0.9f); r.update()
        assertEquals(NEAR, r.state)
        assertEquals("face", r.nf.reason)
        // a small face later is far evidence once the hold is over
        r.nf.onFace(50_000, 8f, 0.9f)
        r.at(50_000).update()
        assertEquals(FAR, r.state)
    }

    @Test fun unsureFacesAreIgnored() {
        val r = Run()
        r.at(1000).far()
        r.nf.onFace(2000, 30f, 0.2f); r.at(2000).update() // low confidence
        assertEquals(FAR, r.state)
        r.nf.onFace(3000, 14f, 0.9f); r.at(3000).update() // in between
        assertEquals(FAR, r.state)
    }

    @Test fun unsureMotionChangesNothing() {
        val r = Run()
        r.every(1000, 120_000) { sample(true, 0.15f, 0.6f) }
        assertEquals(NEAR, r.state) // started NEAR: no evidence either way
        val f = Run().presence(EMPTY)
        f.every(1000, 120_000) { presence(PRESENT); sample(true, 0.15f, 0.6f) }
        assertEquals(FAR, f.state)
    }

    @Test fun lookBeforeRestOnlyWhenFarAndNotObviouslyFar() {
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).far()
        assertFalse("clearly far: rest", r.nf.wantsLook(1000))
        r.at(2000).sample(true, 0.15f, 0.6f)
        assertTrue("unsure while FAR: look", r.nf.wantsLook(2000))
        r.nf.lookTaken()
        assertFalse("one look per rest", r.nf.wantsLook(2100))
        r.nf.onRest()
        assertTrue(r.nf.wantsLook(2200))
        assertFalse("stale sample", r.nf.wantsLook(5000))
        r.at(6000).touch()
        r.at(7000).sample(true, 0.15f, 0.6f)
        assertFalse("already NEAR: rest", r.nf.wantsLook(7000))
    }

    @Test fun lookCatchesSomeoneWalkingUpAfterRest() {
        // FAR, the camera comes back from a rest: frame 1 is never motion, frame 2 is close, the
        // look gives frame 3 (close again) → NEAR.
        val r = Run().presence(EMPTY)
        r.at(1000).presence(PRESENT).far()
        r.at(61_000).still()
        r.at(62_000).close()
        assertTrue(r.nf.wantsLook(62_000))
        r.nf.lookTaken()
        r.at(63_000).close()
        assertEquals(NEAR, r.state)
    }

    @Test fun unknownForgetsFarEvidence() {
        val r = Run()
        r.at(1000).touch()
        r.at(2000).far()
        r.at(3000).presence(UNKNOWN)
        r.at(70_000).presence(PRESENT)
        assertEquals("far evidence from before the camera stopped doesn't count", NEAR, r.state)
        r.at(71_000).far()
        assertEquals(FAR, r.state)
    }
}

class MotionRowsTest {
    private fun grid(fill: (Int, Int) -> Int) = IntArray(80 * 60) { fill(it % 80, it / 80) }

    @Test fun tallChangeFillsRows() {
        val d = MotionDetector()
        d.processGrey(grid { x, _ -> if (x in 10..30) 220 else 60 })
        val r = d.processGrey(grid { x, _ -> if (x in 40..60) 220 else 60 })
        assertEquals(1f, r.rows, 0.001f)
    }

    @Test fun shortChangeFillsFewRows() {
        val d = MotionDetector()
        d.processGrey(grid { x, y -> if (x in 10..40 && y in 20..29) 220 else 60 })
        val r = d.processGrey(grid { x, y -> if (x in 45..75 && y in 20..29) 220 else 60 })
        assertEquals(10f / 60f, r.rows, 0.001f)
    }

    @Test fun strayPixelsDontCountAsRows() {
        val d = MotionDetector()
        d.processGrey(grid { _, _ -> 100 })
        val r = d.processGrey(grid { x, y -> if (x == 5 && y % 2 == 0) 250 else 100 })
        assertEquals(0f, r.rows, 0.001f)
    }
}
