package com.echobedside.feature.media

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaHub
import com.echobedside.core.model.MediaSession
import com.echobedside.core.model.MediaState
import com.echobedside.core.model.fake.FakeMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sample media while no real source exists (`services.config.demoData`): runs core's
 * [FakeMediaSource] (the design's three tracks) through a hub wrapper that adds procedural
 * artwork ([DemoArt]) and moves to the next track when one ends, the way the design's demo does.
 * Stops as soon as a real session (AirPlay, another app) shows up, and stays off after the user
 * taps Disconnect.
 */
class DemoMedia(private val hub: MediaHub, private val scope: CoroutineScope) {
    private val main = Handler(Looper.getMainLooper())
    private val artHub = ArtworkHub(hub)
    private val source = FakeMediaSource(artHub)
    var running = false; private set
    private var stoppedByUser = false

    fun watch() {
        scope.launch {
            // Pre-render the three covers off the main thread, and give real sources a moment to register.
            withContext(Dispatchers.Default) { for (t in TRACKS) DemoArt.forAlbum(t.second, t.first) }
            delay(500)
            hub.sessions.collect { list ->
                val real = list.any { it.sourceId != FakeMediaSource.ID }
                when {
                    real && running -> stop()
                    !real && !running && !stoppedByUser -> start()
                }
            }
        }
    }

    private fun start() { running = true; Log.i(TAG, "demo media on"); source.start() }
    private fun stop() { running = false; artHub.cancelAdvance(); source.stop() }

    /** Disconnect on the demo: back to the empty state until the process restarts or [restart]. */
    fun stopByUser() { stoppedByUser = true; if (running) stop() }

    fun restart() { stoppedByUser = false; if (!running && hub.sessions.value.none { it.sourceId != FakeMediaSource.ID }) start() }

    fun isDemo(sourceId: String?) = sourceId == FakeMediaSource.ID

    /** Adds artwork to the fake's states and schedules the end-of-track advance. */
    private inner class ArtworkHub(private val real: MediaHub) : MediaHub {
        override val sessions: StateFlow<List<MediaSession>> get() = real.sessions
        override val current: StateFlow<MediaSession?> get() = real.current
        private var advance: Runnable? = null

        override fun publish(sourceId: String, state: MediaState, controls: MediaControls) {
            val withArt = if (state.artwork == null && state.title.isNotEmpty()) state.copy(artwork = DemoArt.forAlbum(state.album, state.title)) else state
            real.publish(sourceId, withArt, controls)
            cancelAdvance()
            if (withArt.playing && withArt.durationMs > 0) {
                val left = withArt.durationMs - withArt.positionNow()
                advance = Runnable { controls.next() }.also { main.postDelayed(it, left.coerceAtLeast(0) + 300) }
            }
        }

        override fun clear(sourceId: String) { cancelAdvance(); real.clear(sourceId) }

        fun cancelAdvance() { advance?.let { main.removeCallbacks(it) }; advance = null }
    }

    companion object {
        private const val TAG = "EchoMedia"
        private val TRACKS = listOf("Low Tide Radio" to "Coastline Hours", "Paper Satellites" to "Small Orbits", "Afterglow (Slow)" to "Night Swim")
    }
}
