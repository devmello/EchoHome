package app.vesper.feature.camera.presence

import app.vesper.core.model.Presence
import app.vesper.core.model.ViewDistance

/**
 * Near / far (NOTES §6.5 item 2): is whoever looks at the Echo close enough for the normal
 * layout, or across the room (StandBy's big far layout)? Built only from signals that already
 * exist, so it adds no camera time of its own except one bounded look (see [wantsLook]):
 *
 *  - **Touch**: certainly near. Held [touchHoldMs] after the last touch.
 *  - **Motion size** from the presence frames (the 80×60 differencing, ~1 fps): someone within
 *    about a metre fills the frame ([nearArea] changed and [nearRows] of the rows), someone across
 *    the room changes a small patch ([farArea]) or only part of the height ([farRows]).
 *    **Approach** = the change grew by [approachGrowth]× since the previous sample, to at least
 *    [approachArea]. A near (or approach) sample counts only when a second one follows within
 *    [pairWindowMs] (one odd frame, a lamp, a pet passing close, isn't a person walking up).
 *  - **Eye distance** from the arrival face check (320 px wide frames, only when people are
 *    enrolled): ≥ [nearEyePx] is near (≈ 1 m with this lens, to be calibrated), ≤ [farEyePx] far.
 *  - **Presence**: EMPTY is far evidence (whoever comes in next starts across the room);
 *    UNKNOWN (camera off, blocked, dark, hidden) or [enabled] off is always NEAR.
 *
 * Hysteresis: FAR → NEAR is immediate on near evidence. NEAR → FAR needs both the hold to be
 * over (at least [seenHoldMs] after near evidence) and far evidence *after* the last near
 * evidence. "No motion" is no evidence either way, so when unsure it stays where it is, and it
 * starts NEAR. So it can change at most once per hold, and never flickers.
 *
 * Pure logic with injected time (main thread), unit-tested without a camera.
 */
class NearFar(
    var touchHoldMs: Long = 60_000,
    var seenHoldMs: Long = 45_000,
    var nearArea: Float = 0.20f,
    var nearRows: Float = 0.70f,
    var farArea: Float = 0.10f,
    var farRows: Float = 0.50f,
    var approachArea: Float = 0.12f,
    var approachGrowth: Float = 1.6f,
    var pairWindowMs: Long = 3_000,
    var nearEyePx: Float = 18f,
    var farEyePx: Float = 11f,
    var minFaceConfidence: Float = 0.5f,
    /** Extra looks (each [LOOK_MS]) before the camera rests, per rest. */
    var maxLooks: Int = 1,
) {
    enum class Kind { NONE, STILL, FAR, UNSURE, NEAR }

    var enabled = true
    var state = ViewDistance.NEAR; private set
    /** What made the last change (for the log). */
    var reason = "start"; private set
    /** The last sample's class (for [wantsLook] and the log). */
    var lastKind = Kind.NONE; private set

    private var nearAt = NEVER
    private var nearUntil = NEVER
    private var farAt = NEVER
    private var nearSampleAt = NEVER
    private var prevAt = NEVER
    private var prevArea = 0f
    private var prevMotion = false
    private var lastSampleAt = NEVER
    private var looks = 0

    /** A touch anywhere on the screen. */
    fun onTouch(now: Long) = near(now, touchHoldMs, "touch")

    /** One presence sample (lit frames only): [area] = changed fraction, [rows] = its vertical extent. */
    fun onSample(now: Long, motion: Boolean, area: Float, rows: Float) {
        val approach = motion && prevMotion && now - prevAt <= pairWindowMs &&
            area >= approachArea && area >= prevArea * approachGrowth && rows >= farRows
        val close = motion && area >= nearArea && rows >= nearRows
        val kind = when {
            !motion -> Kind.STILL
            close || approach -> Kind.NEAR
            area < farArea || rows < farRows -> Kind.FAR
            else -> Kind.UNSURE
        }
        when (kind) {
            Kind.NEAR -> {
                if (now - nearSampleAt <= pairWindowMs) near(now, seenHoldMs, if (close) "close motion" else "approach")
                nearSampleAt = now
            }
            Kind.FAR -> farAt = now
            else -> {}
        }
        lastKind = kind
        lastSampleAt = now
        prevAt = now; prevArea = area; prevMotion = motion
    }

    /** A face from the face check: eye distance in px of a 320 px wide frame. */
    fun onFace(now: Long, eyePx: Float, confidence: Float) {
        if (confidence < minFaceConfidence) return
        if (eyePx >= nearEyePx) near(now, seenHoldMs, "face")
        else if (eyePx <= farEyePx) farAt = now
    }

    /** Re-decides at [now]; call after every input and at [nextCheckAt]. */
    fun update(now: Long, presence: Presence): ViewDistance {
        if (!enabled || presence == Presence.UNKNOWN) {
            forget()
            set(ViewDistance.NEAR, if (!enabled) "off" else "camera can't tell")
            return state
        }
        if (presence == Presence.EMPTY) farAt = now
        if (state == ViewDistance.NEAR && now >= nearUntil && farAt > nearAt) set(ViewDistance.FAR, if (presence == Presence.EMPTY) "empty room" else "far away")
        return state
    }

    /** When [update] could next change on its own (the hold ends with far evidence in), else null. */
    fun nextCheckAt(now: Long): Long? =
        if (state == ViewDistance.NEAR && farAt > nearAt && nearUntil > now) nearUntil else null

    /**
     * Someone moved while we say FAR, and it didn't look far: the camera is about to rest (60 s
     * without frames), so take one more short look first ([LOOK_MS], ~2 frames) to see if they're
     * walking up. Bounded: [maxLooks] per rest.
     */
    fun wantsLook(now: Long): Boolean =
        enabled && state == ViewDistance.FAR && looks < maxLooks && now - lastSampleAt <= LOOK_FRESH_MS &&
            (lastKind == Kind.UNSURE || lastKind == Kind.NEAR)

    fun lookTaken() { looks++ }

    /** The camera starts resting: a new look budget for the next time it's on. */
    fun onRest() { looks = 0 }

    private fun near(now: Long, holdMs: Long, why: String) {
        nearAt = now
        nearUntil = maxOf(nearUntil, now + holdMs)
        set(ViewDistance.NEAR, why)
    }

    /** The camera stopped knowing: start over (a touch hold stays). */
    private fun forget() {
        farAt = NEVER; nearSampleAt = NEVER; prevAt = NEVER; prevMotion = false; lastSampleAt = NEVER
        lastKind = Kind.NONE
    }

    private fun set(s: ViewDistance, why: String) {
        if (s == state) return
        state = s
        reason = why
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        /** One look: ≥ 2 more presence samples at 1 fps. */
        const val LOOK_MS = 2_200L
        private const val LOOK_FRESH_MS = 1_500L
    }
}
