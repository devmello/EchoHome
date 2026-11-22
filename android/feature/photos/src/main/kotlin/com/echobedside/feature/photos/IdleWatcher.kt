package com.echobedside.feature.photos

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.observeWhileAttached
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * "Show after idle" (setting `photos.idleMinutes`): an invisible full-screen overlay that only
 * watches touches (it never draws and never consumes one), plus the key handler in
 * [PhotosFeature]. After N minutes without input on StandBy it opens the photo frame; a frame it
 * opened goes back to StandBy when Night mode starts if `photos.notAtNight` is on.
 *
 * Costs nothing while idle: one pending Handler callback, re-armed on each touch.
 */
@SuppressLint("ViewConstructor")
internal class IdleWatcher(context: Context, private val services: EchoServices) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastInput = SystemClock.elapsedRealtime()
    /** The frame is up because this watcher opened it. */
    private var autoShown = false

    private val check = Runnable { evaluate() }

    init {
        setWillNotDraw(true)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        current = java.lang.ref.WeakReference(this)
        observeWhileAttached(services.settings.changes) { key ->
            if (key == PhotoSettings.IDLE_MINUTES || key == PhotoSettings.NOT_AT_NIGHT) schedule()
        }
        observeWhileAttached(services.navigator.current) { id ->
            if (id != PHOTOS) autoShown = false
            schedule()
        }
        observeWhileAttached(services.system.state.map { it.nightActive }.distinctUntilChanged()) { night ->
            if (night && autoShown && PhotoSettings.notAtNight(services.settings) && services.navigator.current.value == PHOTOS) {
                autoShown = false
                services.navigator.home()
            }
            schedule()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInput()
        return false
    }

    fun onInput() {
        lastInput = SystemClock.elapsedRealtime()
        autoShown = false
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(check)
        val minutes = PhotoSettings.idleMinutes(services.settings)
        if (minutes <= 0 || !isAttachedToWindow) return
        val due = lastInput + minutes * 60_000L - SystemClock.elapsedRealtime()
        handler.postDelayed(check, due.coerceAtLeast(1_000L))
    }

    private fun evaluate() {
        val s = services.settings
        val minutes = PhotoSettings.idleMinutes(s)
        val idle = SystemClock.elapsedRealtime() - lastInput
        val screen = services.navigator.current.value
        val night = services.system.state.value.nightActive
        if (PhotoSettings.shouldAutoShow(idle, minutes, screen, night, PhotoSettings.notAtNight(s))) {
            autoShown = true
            services.navigator.show(PHOTOS)
        } else if (minutes > 0 && screen != PHOTOS) {
            // Not on StandBy or at night: look again in a minute (input resets the clock anyway).
            handler.postDelayed(check, 60_000L)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(check)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        schedule()
    }

    companion object {
        const val PHOTOS = "photos"
        /** The attached watcher, for the key handler (keys count as input too). */
        @Volatile var current: java.lang.ref.WeakReference<IdleWatcher>? = null
    }
}
