package app.vesper.feature.system.screenoff

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.AlarmPhase
import app.vesper.core.model.Presence
import app.vesper.core.model.observeWhileAttached
import app.vesper.feature.system.SystemSettings
import app.vesper.feature.system.SystemUi
import app.vesper.feature.system.ui.EASE

/**
 * "Turn the screen off when the room is empty" (overlay z 900, above everything).
 *
 * Presence EMPTY for N minutes -> a black overlay fades in (one `drawColor`, no overlapping
 * rendering, so the fade is the paint alpha, not an offscreen layer), then every view below it is
 * set INVISIBLE so nothing keeps drawing (an invisible view drops its invalidates: 0 frames), and
 * the backlight goes to minimum. Our window stays visible: the camera keeps sampling presence.
 *
 * Wakes on presence PRESENT, the first touch (swallowed, it only wakes), any key, an alarm phase,
 * an alert island activity, a new critical banner, Control Center opening. Never blanks while an
 * alarm / timer is ringing or a call / doorbell is active.
 */
@SuppressLint("ViewConstructor")
internal class ScreenOffOverlay(context: Context, private val ui: SystemUi) : View(context) {
    private val s = ui.services
    private val handler = Handler(Looper.getMainLooper())

    enum class State { AWAKE, BLANKING, BLANKED, WAKING }
    var state = State.AWAKE; private set

    // settings (live)
    private var enabled = true
    private var afterMs = 15 * 60_000L

    // inputs
    private var presence = Presence.UNKNOWN
    private var forcedPresence: Presence? = null
    private var debugAfterMs: Long? = null
    private var alarmActive = false
    private var emptySince = 0L // elapsedRealtime when the room became empty (or when we last woke)
    private val seenCritical = HashSet<String>()

    private val hiddenBelow = ArrayList<View>()
    private var savedBrightness = -1f
    private var minBrightness = -1f

    private val check = Runnable { evaluate("timer") }

    init {
        setWillNotDraw(false)
        alpha = 0f
        visibility = INVISIBLE
        isClickable = false
        observeWhileAttached(s.system.state) { st ->
            if (st.presence != presence) { presence = st.presence; presenceChanged() }
        }
        observeWhileAttached(s.alarms.phase) { p ->
            alarmActive = p !is AlarmPhase.Idle
            if (alarmActive) wake("alarm phase") else evaluate("alarm idle")
        }
        observeWhileAttached(s.island.activities) { list ->
            if (list.any { isAlert(it.kind, it.alert) }) wake("alert activity") else evaluate("activities")
        }
        observeWhileAttached(s.notifications.banners) { list ->
            val fresh = list.filter { it.critical && seenCritical.add(it.id) }
            if (fresh.isNotEmpty()) wake("critical banner")
            seenCritical.retainAll(list.map { it.id }.toSet())
        }
        observeWhileAttached(ui.ccOpen) { if (it) wake("control center") }
        observeWhileAttached(ui.ccRequests) { if (it) wake("control center request") }
        observeWhileAttached(s.settings.changes) { k -> if (k.startsWith("system.screenoff.")) { readSettings(); evaluate("setting") } }
        observeWhileAttached(ui.debug) { b -> b?.let { debug(it) } }
        readSettings()
    }

    // --- policy ---

    private fun isAlert(kind: ActivityKind, alert: Boolean) =
        alert || kind == ActivityKind.CALL || kind == ActivityKind.DOORBELL || kind == ActivityKind.ALARM_RINGING || kind == ActivityKind.TIMER_DONE

    private fun readSettings() {
        enabled = s.settings.getBoolean(SystemSettings.SCREENOFF_ENABLED, true)
        afterMs = (s.settings.getFloat(SystemSettings.SCREENOFF_MINUTES, 15f).coerceIn(1f, 240f) * 60_000f).toLong()
    }

    private fun effectivePresence() = forcedPresence ?: presence

    private fun busy(): Boolean {
        if (alarmActive) return true
        val now = SystemClock.elapsedRealtime()
        return s.island.activities.value.any { a -> isAlert(a.kind, a.alert) && (a.staleAfterElapsed?.let { it > now } ?: true) }
    }

    private fun presenceChanged() {
        if (effectivePresence() == Presence.EMPTY) { emptySince = SystemClock.elapsedRealtime(); evaluate("presence empty") }
        else if (effectivePresence() == Presence.PRESENT) wake("presence")
        else evaluate("presence unknown")
    }

    /** Blank when enabled, EMPTY for the whole delay and nothing is ringing; otherwise (re)arm the timer. */
    private fun evaluate(why: String) {
        handler.removeCallbacks(check)
        if (state != State.AWAKE) return
        if (!enabled || effectivePresence() != Presence.EMPTY || !isAttachedToWindow) return
        val delay = debugAfterMs ?: afterMs
        val left = emptySince + delay - SystemClock.elapsedRealtime()
        when {
            busy() -> handler.postDelayed(check, 15_000)
            left > 0 -> handler.postDelayed(check, left + 20)
            else -> blank("room empty ${delay / 1000} s ($why)")
        }
    }

    // --- blank / wake ---

    private fun blank(why: String) {
        if (state != State.AWAKE) return
        Log.i(TAG, "screen-off: BLANK ($why)")
        state = State.BLANKING
        visibility = VISIBLE
        animate().cancel()
        animate().alpha(1f).setDuration(FADE_MS).setInterpolator(EASE).withEndAction {
            if (state == State.BLANKING) {
                state = State.BLANKED
                hideBelow()
                Log.i(TAG, "screen-off: BLANKED, views below hidden=${hiddenBelow.size}")
            }
        }.start()
        // backlight to minimum, remembering what to restore (also across a crash: the stored value)
        val cur = s.system.state.value.brightness
        if (savedBrightness < 0f) {
            savedBrightness = cur
            s.settings.putFloat(SystemSettings.SCREENOFF_SAVED, cur)
        }
        s.systemControls.setBrightness(0f, showHud = false)
        minBrightness = s.system.state.value.brightness
    }

    fun wake(why: String) {
        if (state == State.AWAKE) { handler.removeCallbacks(check); return }
        if (state == State.WAKING) return
        Log.i(TAG, "screen-off: WAKE ($why)")
        handler.removeCallbacks(check)
        state = State.WAKING
        restoreBrightness()
        emptySince = SystemClock.elapsedRealtime() // someone is here: another full delay before the next blank
        animate().cancel()
        // the views below re-record on their first frame back: give them that frame before the fade
        val fade = {
            animate().alpha(0f).setDuration(WAKE_MS).setInterpolator(EASE).withEndAction {
                visibility = INVISIBLE
                state = State.AWAKE
                evaluate("woke")
            }.start()
        }
        if (showBelow()) postOnAnimation { fade() } else fade()
    }

    private fun restoreBrightness() {
        if (savedBrightness >= 0f) {
            // if something else changed the brightness while we were dark, leave theirs
            if (kotlin.math.abs(s.system.state.value.brightness - minBrightness) < 0.02f) s.systemControls.setBrightness(savedBrightness, showHud = false)
            savedBrightness = -1f
        }
        s.settings.remove(SystemSettings.SCREENOFF_SAVED)
    }

    /** Everything under us is covered by an opaque black: stop drawing it (INVISIBLE views drop invalidates). */
    private fun hideBelow() {
        val p = parent as? ViewGroup ?: return
        for (i in 0 until p.indexOfChild(this)) {
            val v = p.getChildAt(i)
            // Control Center keeps its recorded tiles while invisible-by-alpha; the camera overlay owns the presence dot
            val name = v.javaClass.simpleName
            if (name.startsWith("ControlCenter") || name.contains("Camera")) continue
            if (v.visibility == VISIBLE) { v.visibility = INVISIBLE; hiddenBelow += v }
        }
    }

    private fun showBelow(): Boolean {
        if (hiddenBelow.isEmpty()) return false
        for (v in hiddenBelow) v.visibility = VISIBLE
        hiddenBelow.clear()
        return true
    }

    // --- keys / touch ---

    /** Any key wakes (it still reaches the other handlers, e.g. volume or the mic button). */
    fun onKey() { if (state != State.AWAKE) wake("key") }

    override fun hasOverlappingRendering() = false

    override fun onDraw(canvas: Canvas) { canvas.drawColor(Color.BLACK) }

    private var swallowing = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = state == State.BLANKING || state == State.BLANKED
            if (swallowing) wake("touch")
        }
        if (swallowing && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) { swallowing = false; return true }
        return swallowing
    }

    // --- lifecycle ---

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // an earlier process died while dark: bring the backlight back
        val saved = s.settings.getFloat(SystemSettings.SCREENOFF_SAVED, -1f)
        if (saved >= 0f) { s.settings.remove(SystemSettings.SCREENOFF_SAVED); s.systemControls.setBrightness(saved, showHud = false) }
        ui.screenOff = this
        presence = s.system.state.value.presence
        emptySince = SystemClock.elapsedRealtime()
        evaluate("attached")
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        if (ui.screenOff === this) ui.screenOff = null
        if (state != State.AWAKE) { showBelow(); restoreBrightness(); state = State.AWAKE; alpha = 0f; visibility = INVISIBLE }
        super.onDetachedFromWindow()
    }

    // --- debug: --es screenoff empty|present|wake|off [--es screenoff_s 5] ---

    private fun debug(b: android.os.Bundle) {
        b.getString("screenoff_s")?.toLongOrNull()?.let { debugAfterMs = it * 1000 }
        when (b.getString("screenoff")) {
            "empty" -> { forcedPresence = Presence.EMPTY; emptySince = SystemClock.elapsedRealtime(); if (debugAfterMs == null) debugAfterMs = 5_000; evaluate("debug empty") }
            "present" -> { forcedPresence = Presence.PRESENT; wake("debug present") }
            "wake" -> wake("debug")
            "off" -> { forcedPresence = null; debugAfterMs = null; wake("debug off"); presence = s.system.state.value.presence }
        }
    }

    companion object { private const val TAG = "VesperSystem"; const val FADE_MS = 1200L; const val WAKE_MS = 250L }
}
