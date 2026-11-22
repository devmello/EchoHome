package com.echobedside.feature.phone.ams

import android.util.Log
import com.echobedside.core.model.MediaControls
import com.echobedside.core.model.MediaHub
import com.echobedside.core.model.MediaSession
import com.echobedside.core.model.MediaState
import com.echobedside.core.model.fake.FakeMediaSource

/**
 * The iPhone's Now Playing over AMS → [MediaHub] source [AmsMapping.SOURCE_ID] ("iphone"), with
 * Remote Command controls. When the same iPhone also streams to the Echo over A2DP, this entry
 * steps aside and the Bluetooth one carries the app name ([merged]); when audio plays on the Echo
 * from anything else, a remote-only iPhone entry is hidden ([AmsMapping.decide]).
 *
 * Call [onSessions] on every hub change (precedence depends on the other sources).
 */
class AmsMediaSource(
    private val hub: () -> MediaHub,
    private val phoneName: () -> String,
    /** The A2DP source is connected and it's this iPhone. */
    private val a2dpSamePhone: () -> Boolean,
    private val artwork: ArtworkLookup,
    /** The merged info changed (the Bluetooth source should republish). */
    private val onMergedChanged: () -> Unit,
) {
    var session: AmsSession? = null
        private set
    private var published: MediaState? = null
    var enabled = true
        set(v) { field = v; refresh() }

    /** AMS info for the A2DP entry when [AmsMapping.Show.MERGED], else null. */
    var merged: AmsNowPlaying? = null
        private set

    fun attach(io: AmsSession.Io, now: () -> Long): AmsSession {
        val s = AmsSession(io, { refresh() }, now)
        session = s
        return s
    }

    fun detach() {
        session = null
        refresh()
    }

    fun onSessions(@Suppress("UNUSED_PARAMETER") sessions: List<MediaSession>) = refresh()

    fun refresh() {
        val np = session?.state?.takeIf { enabled }
        // The sample media (demo data while no real source exists) steps aside for any real one, so it never wins here.
        val others = hub().sessions.value.filter { it.sourceId != AmsMapping.SOURCE_ID && it.sourceId != FakeMediaSource.ID }
        val show = if (np == null) AmsMapping.Show.HIDDEN else AmsMapping.decide(np, others, a2dpSamePhone())
        val m = if (show == AmsMapping.Show.MERGED) np else null
        if (m != merged) { merged = m; onMergedChanged() }
        if (show != AmsMapping.Show.OWN || np == null) { clear(); return }
        // Not while a truncated title or artist is still being read: never look up (or send) a cut-off name.
        val art = if (session?.settled == true) artwork.get(np.artist, np.title) { refresh() } else null
        val state = AmsMapping.toState(np, phoneName(), art) ?: run { clear(); return }
        if (AmsMapping.sameForUi(published, state)) return
        if (published?.title != state.title) Log.i(TAG, "AMS: now playing from ${np.app.ifBlank { "?" }}, playing=${np.playing}")
        published = state
        hub().publish(AmsMapping.SOURCE_ID, state, controls)
    }

    private fun clear() {
        if (published != null) { published = null; hub().clear(AmsMapping.SOURCE_ID) }
    }

    val controls = object : MediaControls {
        private val c get() = session?.state?.commands.orEmpty()
        override fun play() { AmsMapping.playCommand(c, play = true)?.let { session?.command(it) } }
        override fun pause() { AmsMapping.playCommand(c, play = false)?.let { session?.command(it) } }
        override fun next() { session?.command(Ams.CMD_NEXT_TRACK) }
        override fun previous() { session?.command(Ams.CMD_PREVIOUS_TRACK) }
        override fun seekTo(positionMs: Long) { /* AMS has no seek-to */ }
        /** The iPhone's own volume, in its 16 steps. */
        override fun setVolume(volume: Float) {
            val s = session ?: return
            val n = AmsMapping.volumeSteps(s.state.volume, volume)
            val id = if (n > 0) Ams.CMD_VOLUME_UP else Ams.CMD_VOLUME_DOWN
            repeat(kotlin.math.abs(n)) { if (!s.command(id)) return }
        }
        override val canSeek: Boolean get() = false
        override val canSkip: Boolean get() = AmsMapping.canSkip(c)
        override val canPause: Boolean get() = AmsMapping.canPause(c)
        /** Nothing to disconnect for a remote: just pause the iPhone. */
        override fun disconnect() { pause() }
    }

    companion object { const val TAG = "EchoPhone" }
}
