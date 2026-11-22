package com.echobedside.core.model

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Collects [flow] on the main thread while [this] view is attached to a window, and stops when
 * it detaches. The no-AndroidX replacement for lifecycle-aware collection:
 *
 *     services.media.current.observeWhileAttached(this) { session -> bind(session) }
 */
fun <T> View.observeWhileAttached(flow: Flow<T>, block: (T) -> Unit) {
    var scope: CoroutineScope? = null
    fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { flow.collect { block(it) } }
        }
    }
    fun stop() { scope?.cancel(); scope = null }
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = start()
        override fun onViewDetachedFromWindow(v: View) = stop()
    })
    if (isAttachedToWindow) start()
}

/** Same, written flow-first. */
fun <T> Flow<T>.observeWhileAttached(view: View, block: (T) -> Unit) = view.observeWhileAttached(this, block)

/** A coroutine tied to the view's attachment; cancelled on detach. */
fun View.launchWhileAttached(block: suspend CoroutineScope.() -> Unit): Job? {
    if (!isAttachedToWindow) return null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { scope.cancel(); removeOnAttachStateChangeListener(this) }
    })
    return scope.launch(block = block)
}
