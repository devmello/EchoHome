package app.vesper.feature.camera

import app.vesper.core.model.Presence
import app.vesper.feature.camera.capture.YPlane
import app.vesper.feature.camera.capture.pickLowestFps
import app.vesper.feature.camera.capture.pickSmallest
import app.vesper.feature.camera.presence.MotionDetector
import app.vesper.feature.camera.presence.PresenceTracker
import java.nio.ByteBuffer
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    private fun grid(fill: (Int, Int) -> Int) = IntArray(80 * 60) { fill(it % 80, it / 80) }

    @Test fun firstFrameIsNeverMotion() {
        val d = MotionDetector()
        val r = d.processGrey(grid { _, _ -> 120 })
        assertFalse(r.motion)
        assertEquals(0f, r.changed)
    }

    @Test fun identicalFramesAreStill() {
        val d = MotionDetector()
        val g = grid { x, y -> (x * 3 + y * 2) % 200 + 20 }
        d.processGrey(g)
        val r = d.processGrey(g.copyOf())
        assertFalse(r.motion)
        assertEquals(0f, r.changed)
    }

    @Test fun sensorNoiseIsStill() {
        val d = MotionDetector()
        val rnd = Random(1)
        d.processGrey(grid { _, _ -> 120 + rnd.nextInt(-6, 7) })
        repeat(10) { assertFalse(d.processGrey(grid { _, _ -> 120 + rnd.nextInt(-6, 7) }).motion) }
    }

    @Test fun globalBrightnessShiftIsNotMotion() {
        // Auto-exposure or a lamp: every pixel moves by the same amount.
        val d = MotionDetector()
        d.processGrey(grid { x, _ -> 80 + x })
        val r = d.processGrey(grid { x, _ -> 120 + x })
        assertFalse(r.motion)
    }

    @Test fun movingBlockIsMotion() {
        val d = MotionDetector()
        d.processGrey(grid { x, y -> if (x in 10..19 && y in 10..19) 220 else 60 })
        val r = d.processGrey(grid { x, y -> if (x in 30..39 && y in 10..19) 220 else 60 })
        assertTrue(r.motion)
        assertTrue(r.changed > 0.03f)
    }

    @Test fun tinyChangeBelowFractionIsStill() {
        val d = MotionDetector()
        d.processGrey(grid { _, _ -> 100 })
        // 20 px of 4800 = 0.4 % < 1 %
        val r = d.processGrey(grid { x, y -> if (y == 0 && x < 20) 200 else 100 })
        assertFalse(r.motion)
    }

    @Test fun darkFramesAreFlaggedAndNeverMotion() {
        val d = MotionDetector()
        d.processGrey(grid { x, _ -> if (x < 40) 2 else 6 })
        val r = d.processGrey(grid { x, _ -> if (x < 40) 8 else 1 })
        assertTrue(r.dark)
        assertFalse(r.motion)
    }

    @Test fun downscaleReadsStridedYPlane() {
        // 176×144 with a row stride of 192 and pixel stride 1; left half dark, right half bright.
        val w = 176; val h = 144; val rs = 192
        val bytes = ByteArray(rs * h)
        for (y in 0 until h) for (x in 0 until w) bytes[y * rs + x] = (if (x < w / 2) 10 else 200).toByte()
        val out = IntArray(80 * 60)
        MotionDetector.downscale(YPlane(ByteBuffer.wrap(bytes), w, h, rs, 1, 0), out, 80, 60)
        assertEquals(10, out[0])
        assertEquals(200, out[79])
        assertEquals(10, out[59 * 80 + 10])
        assertEquals(200, out[59 * 80 + 70])
    }

    @Test fun downscaleHandlesPixelStride2() {
        val w = 160; val h = 120
        val bytes = ByteArray(w * 2 * h)
        for (y in 0 until h) for (x in 0 until w) bytes[y * w * 2 + x * 2] = 77
        val out = IntArray(80 * 60)
        MotionDetector.downscale(YPlane(ByteBuffer.wrap(bytes), w, h, w * 2, 2, 0), out, 80, 60)
        assertTrue(out.all { it == 77 })
    }

    @Test fun directBufferPathMatchesArrayPath() {
        // Camera2 hands out direct buffers; Camera1 wraps an array. Both paths must agree.
        val w = 176; val h = 144; val rs = 180
        val rnd = Random(3)
        val bytes = ByteArray(rs * h) { rnd.nextInt(256).toByte() }
        val direct = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        val a = IntArray(80 * 60); val b = IntArray(80 * 60)
        MotionDetector.downscale(YPlane(ByteBuffer.wrap(bytes), w, h, rs, 1, 0), a, 80, 60)
        MotionDetector.downscale(YPlane(direct, w, h, rs, 1, 0), b, 80, 60)
        assertTrue(a.contentEquals(b))
    }

    @Test fun picksCheapestCameraConfig() {
        val sizes = listOf(1600 to 1200, 640 to 480, 320 to 240, 176 to 144)
        assertEquals(176 to 144, pickSmallest(sizes, 160))
        assertEquals(320 to 240, pickSmallest(sizes, 320))
        assertEquals(1600 to 1200, pickSmallest(sizes, 4000))
        assertEquals(15 to 15, pickLowestFps(listOf(15 to 15, 20 to 20, 5 to 30, 30 to 30)))
    }
}

class PresenceTrackerTest {
    @Test fun startsUnknown() = assertEquals(Presence.UNKNOWN, PresenceTracker().state)

    @Test fun singleMotionDoesNotEnter() {
        val t = PresenceTracker(enterMotions = 2, enterWindowMs = 2000)
        assertEquals(Presence.UNKNOWN, t.onSample(true, 0))
        assertEquals(Presence.UNKNOWN, t.onSample(false, 500))
        assertEquals(Presence.UNKNOWN, t.onSample(false, 1000))
    }

    @Test fun twoMotionsInWindowEnterAndFlagArrival() {
        val t = PresenceTracker(enterMotions = 2, enterWindowMs = 2000)
        t.onSample(true, 0)
        assertEquals(Presence.PRESENT, t.onSample(true, 1500))
        assertTrue(t.arrived)
        t.onSample(true, 2000)
        assertFalse("arrival fires once", t.arrived)
    }

    @Test fun motionsTooFarApartDoNotEnter() {
        val t = PresenceTracker(enterMotions = 2, enterWindowMs = 2000)
        t.onSample(true, 0)
        assertEquals(Presence.UNKNOWN, t.onSample(true, 2600))
    }

    @Test fun staysPresentUntilQuietForEmptyAfter() {
        val t = PresenceTracker(enterMotions = 2, enterWindowMs = 2000, emptyAfterMs = 60_000)
        t.onSample(true, 0); t.onSample(true, 500)
        var now = 500L
        while (now < 60_000) { now += 500; assertEquals(Presence.PRESENT, t.onSample(false, now)) }
        assertEquals(Presence.EMPTY, t.onSample(false, 60_500))
    }

    @Test fun oneMotionKeepsPresentAlive() {
        val t = PresenceTracker(enterMotions = 2, enterWindowMs = 2000, emptyAfterMs = 10_000)
        t.onSample(true, 0); t.onSample(true, 500)
        t.onSample(true, 9_000) // a single motion is enough once present
        assertEquals(Presence.PRESENT, t.onSample(false, 15_000))
        assertEquals(Presence.EMPTY, t.onSample(false, 19_000))
    }

    @Test fun quietRoomFromStartBecomesEmpty() {
        val t = PresenceTracker(emptyAfterMs = 10_000)
        t.onSample(false, 0)
        assertEquals(Presence.UNKNOWN, t.onSample(false, 9_999))
        assertEquals(Presence.EMPTY, t.onSample(false, 10_000))
    }

    @Test fun emptyToPresentIsAnArrival() {
        val t = PresenceTracker(enterMotions = 2, emptyAfterMs = 1000)
        t.onSample(false, 0); t.onSample(false, 1000)
        assertEquals(Presence.EMPTY, t.state)
        t.onSample(true, 2000)
        assertEquals(Presence.EMPTY, t.state)
        t.onSample(true, 2500)
        assertEquals(Presence.PRESENT, t.state)
        assertTrue(t.arrived)
    }

    @Test fun touchEntersImmediately() {
        val t = PresenceTracker()
        assertEquals(Presence.PRESENT, t.onTouch(100))
        assertTrue(t.arrived)
    }

    @Test fun resetForgetsEverything() {
        val t = PresenceTracker(emptyAfterMs = 1000)
        t.onTouch(0)
        t.reset()
        assertEquals(Presence.UNKNOWN, t.state)
        t.onSample(false, 5000)
        assertEquals("quiet time restarts after a reset", Presence.UNKNOWN, t.onSample(false, 5500))
    }
}
