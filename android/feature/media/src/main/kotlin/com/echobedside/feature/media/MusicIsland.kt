package com.echobedside.feature.media

import android.os.SystemClock
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.IslandManager
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.MediaHub
import com.echobedside.core.model.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Publishes the MUSIC live activity for the island (agent B renders it, NOTES §5.4) from
 * `services.media.current`. Updates only on identity / play-state / artwork changes, not on
 * position (the renderer reads the live position from the hub). The island hides it on Now
 * Playing via [LiveActivity.ownerScreen]. A paused session goes stale after [PAUSED_STALE_MS].
 */
class MusicIsland(private val hub: MediaHub, private val island: IslandManager, private val scope: CoroutineScope) {

    private data class Key(val sourceId: String, val title: String, val artist: String, val playing: Boolean, val artId: Int, val source: Any)

    fun start() {
        scope.launch {
            hub.current
                .map { s -> s?.let { Key(it.sourceId, it.state.title, it.state.artist, it.state.playing, System.identityHashCode(it.state.artwork), it.state.source) to it } }
                .distinctUntilChanged { a, b -> a?.first == b?.first }
                .collect { pair ->
                    if (pair == null) island.end(ID) else island.publish(activityFor(pair.second))
                    android.util.Log.d("EchoMedia", "island music: " + (pair?.first?.let { "${it.title} playing=${it.playing}" } ?: "ended"))
                }
        }
    }

    companion object {
        const val ID = "media.music"
        const val PAUSED_STALE_MS = 10 * 60_000L

        fun activityFor(session: MediaSession, now: Long = SystemClock.elapsedRealtime()): LiveActivity {
            val st = session.state
            val c = session.controls
            return LiveActivity(
                id = ID,
                kind = ActivityKind.MUSIC,
                content = LiveContent.Music(st.title, st.artist, st.artwork, st.playing, st.source),
                // Only what the source supports (AirPlay 2 senders can't skip, some can't pause).
                actions = listOfNotNull(
                    IslandAction("previous", "Previous") { c.previous() }.takeIf { c.canSkip },
                    if (st.playing) IslandAction("pause", "Pause") { c.pause() }.takeIf { c.canPause } else IslandAction("play", "Play") { c.play() },
                    IslandAction("next", "Next") { c.next() }.takeIf { c.canSkip },
                ),
                staleAfterElapsed = if (st.playing) null else now + PAUSED_STALE_MS,
                ownerScreen = NowPlayingScreen.ID,
            )
        }
    }
}
