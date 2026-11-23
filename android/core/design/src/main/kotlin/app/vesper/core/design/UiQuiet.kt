package app.vesper.core.design

import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Says when the screen is quiet: no finger down and no frame drawn for [QUIET_MS].
 *
 * Work done ahead of time (building a page or a widget before it's needed, recording its display
 * list) belongs in quiet moments. A plain `MessageQueue.IdleHandler` isn't enough: the queue is
 * also idle between the frames of an animation, so a 20-60 ms build there drops frames (the
 * first StandBy ⇄ Widgets swipes did exactly that). `:app` installs it on the window and feeds it
 * every touch; without that (unit tests, another host) it is always quiet unless a finger is down.
 */
object UiQuiet {
    /** About three frames: an animation draws every 16 ms, even a slow one well within this. */
    const val QUIET_MS = 50L

    private var lastFrame = 0L
    private var touching = false

    /** Tracks the frames [root]'s window draws. Call once, from the activity's onCreate. */
    fun install(root: View) {
        root.viewTreeObserver.addOnDrawListener { lastFrame = SystemClock.uptimeMillis() }
    }

    /** Every touch the window dispatches (Activity.dispatchTouchEvent). */
    fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; lastFrame = SystemClock.uptimeMillis() }
        }
    }

    fun isQuiet(): Boolean = quiet(SystemClock.uptimeMillis(), lastFrame, touching)

    /** How long to wait before asking again. */
    fun msUntilQuiet(): Long = waitMs(SystemClock.uptimeMillis(), lastFrame, touching)

    internal fun quiet(now: Long, lastFrame: Long, touching: Boolean) = !touching && now - lastFrame >= QUIET_MS

    internal fun waitMs(now: Long, lastFrame: Long, touching: Boolean): Long =
        if (touching) QUIET_MS * 2 else (QUIET_MS - (now - lastFrame)).coerceIn(1L, QUIET_MS)
}

/**
 * Runs [step] on the main thread, one call per idle moment while [UiQuiet] says the screen is
 * quiet, until [step] returns false (nothing left) or [stop] is called. Each step should be one
 * small unit (build one view, record one page); a step that draws makes the next one wait for
 * quiet again, so the work spreads out instead of stacking up. [start] again when there's new work.
 */
class QuietWork(private val step: () -> Boolean) {
    private val queue: MessageQueue = Looper.getMainLooper().queue
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var idleAdded = false
    private val retry = Runnable { addIdle() }

    private val idle = MessageQueue.IdleHandler {
        idleAdded = false
        if (!running) return@IdleHandler false
        if (!UiQuiet.isQuiet()) {
            handler.postDelayed(retry, UiQuiet.msUntilQuiet())
            return@IdleHandler false
        }
        if (step()) handler.post(retry) else running = false
        false
    }

    val isRunning: Boolean get() = running

    fun start() {
        running = true
        handler.removeCallbacks(retry)
        addIdle()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(retry)
        if (idleAdded) { queue.removeIdleHandler(idle); idleAdded = false }
    }

    private fun addIdle() {
        if (!running || idleAdded) return
        idleAdded = true
        queue.addIdleHandler(idle)
    }
}
