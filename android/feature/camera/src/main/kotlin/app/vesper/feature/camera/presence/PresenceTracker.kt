package app.vesper.feature.camera.presence

import app.vesper.core.model.Presence

/**
 * Turns motion samples into PRESENT / EMPTY with hysteresis:
 *  - PRESENT needs [enterMotions] motion samples within [enterWindowMs] (one noisy frame isn't
 *    a person), or a touch.
 *  - EMPTY needs [emptyAfterMs] without any motion (people sit still; the room must be quiet
 *    for a while before the screen dims).
 *  - UNKNOWN while the camera is off (blocked, dark, not visible).
 *
 * Pure logic with injected time, so it's unit-tested without a camera.
 */
class PresenceTracker(
    var enterMotions: Int = 2,
    var enterWindowMs: Long = 2_000,
    var emptyAfterMs: Long = 3 * 60_000,
) {
    var state: Presence = Presence.UNKNOWN; private set
    /** True once, right after a change to PRESENT from EMPTY / UNKNOWN (someone arrived). */
    var arrived: Boolean = false; private set

    private var lastMotionAt = -1L
    private var sinceAt = -1L
    private val recent = ArrayDeque<Long>()

    /** Feeds one sample; returns the (possibly new) state. */
    fun onSample(motion: Boolean, nowMs: Long): Presence {
        arrived = false
        if (sinceAt < 0) sinceAt = nowMs
        if (motion) {
            recent.addLast(nowMs)
            while (recent.isNotEmpty() && nowMs - recent.first() > enterWindowMs) recent.removeFirst()
            if (state == Presence.PRESENT || recent.size >= enterMotions) {
                lastMotionAt = nowMs
                set(Presence.PRESENT)
            }
        } else {
            while (recent.isNotEmpty() && nowMs - recent.first() > enterWindowMs) recent.removeFirst()
        }
        val quietSince = if (lastMotionAt >= 0) lastMotionAt else sinceAt
        if (state != Presence.EMPTY && nowMs - quietSince >= emptyAfterMs) set(Presence.EMPTY)
        return state
    }

    /** A touch on the screen: certainly someone's there. */
    fun onTouch(nowMs: Long): Presence {
        arrived = false
        lastMotionAt = nowMs
        if (sinceAt < 0) sinceAt = nowMs
        set(Presence.PRESENT)
        return state
    }

    /** The camera stopped: we no longer know. The next start counts quiet time from scratch. */
    fun reset() {
        state = Presence.UNKNOWN
        arrived = false
        lastMotionAt = -1L
        sinceAt = -1L
        recent.clear()
    }

    private fun set(s: Presence) {
        if (s == state) return
        if (s == Presence.PRESENT) arrived = true
        state = s
    }
}
