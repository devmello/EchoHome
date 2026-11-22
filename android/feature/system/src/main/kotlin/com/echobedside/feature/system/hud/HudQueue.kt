package com.echobedside.feature.system.hud

/** What the indicator capsule (HUDs.dc.html) shows. */
sealed interface Hud {
    /** Volume / brightness: icon + bar + percentage, 360 wide. [value] 0..1. */
    data class Level(val kind: LevelKind, val value: Float, val muted: Boolean = false) : Hud
    data class Message(val kind: MessageKind) : Hud
}

enum class LevelKind { VOLUME, BRIGHTNESS }

/**
 * Message capsules: width and tint from HUDs.dc.html. [subject] groups messages about the same
 * thing (a newer mic state replaces an older one instead of queueing behind it).
 */
enum class MessageKind(val subject: Subject, val title: String, val sub: String, val widthPx: Int, val tint: Int) {
    MIC_OFF(Subject.MIC, "Microphone off", "Nova can’t hear you", 300, RED),
    MIC_ON(Subject.MIC, "Microphone on", "Listening for “Hey Nova”", 330, GREEN),
    CAMERA_OFF_SHUTTER(Subject.CAMERA, "Camera off", "Shutter closed", 260, RED),
    CAMERA_OFF(Subject.CAMERA, "Camera off", "Turned off", 260, RED),
    /** The hardware button gates mics and camera together. */
    MIC_CAMERA_OFF(Subject.MIC, "Microphone off", "Mic and camera off", 300, RED),
    ;
    enum class Subject { MIC, CAMERA }
}

/** rgba(255,69,58,0.55) */
private const val RED = 0x8CFF453A.toInt()
/** rgba(48,209,88,0.5) */
private const val GREEN = 0x8030D158.toInt()

/**
 * The indicator queue. One capsule at a time:
 *  - Levels update in place (holding a volume key keeps one capsule up) and stay [LEVEL_MS] after the last change.
 *  - A message replaces a level immediately; a level never interrupts a message (it waits, newest value only).
 *  - Messages are shown [MESSAGE_MS] each, in order; a newer message about the same subject replaces
 *    the current/queued one (mic off → mic on quickly shows only "on").
 * Pure logic with injected time, so it's unit-tested; [HudOverlay] drives it with a Handler.
 */
class HudQueue {
    var current: Hud? = null; private set
    private var shownAt = 0L
    private var updatedAt = 0L
    private val pendingMessages = ArrayDeque<Hud.Message>()
    private var pendingLevel: Hud.Level? = null

    fun post(h: Hud, now: Long) {
        val cur = current
        when {
            cur == null -> show(h, now)
            cur is Hud.Level && h is Hud.Level -> { current = h; updatedAt = now }
            cur is Hud.Level && h is Hud.Message -> { pendingLevel = null; show(h, now) }
            cur is Hud.Message && h is Hud.Level -> pendingLevel = h
            cur is Hud.Message && h is Hud.Message -> {
                if (cur.kind.subject == h.kind.subject) {
                    pendingMessages.removeAll { it.kind.subject == h.kind.subject }
                    show(h, now)
                } else {
                    pendingMessages.removeAll { it.kind.subject == h.kind.subject }
                    pendingMessages.addLast(h)
                }
            }
        }
    }

    /** Advances time; returns true if [current] changed. */
    fun tick(now: Long): Boolean {
        val cur = current ?: return false
        val deadline = deadline() ?: return false
        if (now < deadline) return false
        val next = pendingMessages.removeFirstOrNull() ?: pendingLevel.also { pendingLevel = null }
        if (next != null) show(next, now) else current = null
        return cur != current || next != null
    }

    /** When [current] expires; null when nothing is shown. */
    fun deadline(): Long? = when (val c = current) {
        null -> null
        is Hud.Level -> updatedAt + LEVEL_MS
        is Hud.Message -> shownAt + MESSAGE_MS
    }

    /** True while the capsule is about [subject] (its persistent badge is hidden meanwhile). */
    fun showing(subject: MessageKind.Subject): Boolean = (current as? Hud.Message)?.kind?.subject == subject

    fun clear() { current = null; pendingMessages.clear(); pendingLevel = null }

    private fun show(h: Hud, now: Long) { current = h; shownAt = now; updatedAt = now }

    companion object {
        /** Levels stay this long after the last change. */
        const val LEVEL_MS = 1500L
        /** HUDs.dc.html: visible 1.9 s of each 2.6 s cycle. */
        const val MESSAGE_MS = 1900L
    }
}
