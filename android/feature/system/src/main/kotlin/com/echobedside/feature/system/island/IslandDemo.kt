package com.echobedside.feature.system.island

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.AssistantPhase
import com.echobedside.core.model.IslandAction
import com.echobedside.core.model.IslandManager
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.core.model.MediaSourceKind

/**
 * Debug-only demo activities for the island (the producers are other agents' features), matching
 * Island.dc.html's scenes. Launch with `--es island <scene>` or
 * `adb shell am broadcast -a com.echobedside.system.DEBUG --es island <scene>`:
 * story · timer · music+timer · assistant · doorbell · call · nowplaying · off.
 */
internal class IslandDemo(private val island: IslandManager) {
    private val handler = Handler(Looper.getMainLooper())
    private var storyStart = 0L
    private var storyStep = ""
    private val story = object : Runnable {
        override fun run() {
            val t = ((SystemClock.elapsedRealtime() - storyStart) % 26_000) / 1000f
            val s = when {
                t < 4 -> "timer"; t < 9 -> "music+timer"; t < 14 -> "doorbell"; t < 17 -> "music+timer"; t < 21 -> "assistant"; else -> "call"
            }
            if (s != storyStep) { storyStep = s; show(s, keepStory = true) }
            handler.postDelayed(this, 250)
        }
    }
    private val timerEnds = SystemClock.elapsedRealtime() + (7 * 60 + 42) * 1000L

    fun play(scene: String) {
        Log.i("EchoSystem", "island demo: $scene")
        handler.removeCallbacks(story)
        if (scene == "story" || scene == "demo") {
            storyStart = SystemClock.elapsedRealtime(); storyStep = ""
            handler.post(story)
        } else show(scene, keepStory = false)
    }

    private fun show(scene: String, @Suppress("UNUSED_PARAMETER") keepStory: Boolean) {
        val want = when (scene) {
            "timer" -> listOf(timer())
            "music+timer", "split" -> listOf(music(), timer())
            "assistant" -> listOf(assistant(), timer())
            "doorbell" -> listOf(music(), doorbell())
            "call" -> listOf(music(), call())
            "nowplaying", "music" -> listOf(music(), timer())
            else -> emptyList()
        }
        val ids = want.map { it.id }.toSet()
        island.activities.value.filter { it.id.startsWith(PREFIX) && it.id !in ids }.forEach { island.end(it.id) }
        want.forEach { a -> if (island.activities.value.none { it.id == a.id && it.content == a.content }) island.publish(a) }
    }

    private fun timer() = LiveActivity("${PREFIX}timer", ActivityKind.TIMER,
        LiveContent.Timer("Pasta", 10 * 60_000L, timerEnds), ownerScreen = "timers")

    private fun music() = LiveActivity("${PREFIX}music", ActivityKind.MUSIC,
        LiveContent.Music("Low Tide Radio", "Marisol Vey", null, true, MediaSourceKind.AIRPLAY), ownerScreen = "nowplaying")

    private fun assistant() = LiveActivity("${PREFIX}assistant", ActivityKind.ASSISTANT,
        LiveContent.Assistant(AssistantPhase.LISTENING, 0f), ownerScreen = "assistant")

    private fun doorbell() = LiveActivity("${PREFIX}doorbell", ActivityKind.DOORBELL, LiveContent.Doorbell("Front door"),
        actions = listOf(
            IslandAction("talk", "Talk", IslandAction.Style.ACCEPT) { Log.i("EchoSystem", "demo: talk") },
            IslandAction("unlock", "Unlock") { Log.i("EchoSystem", "demo: unlock") },
            IslandAction("dismiss", "Dismiss") { island.end("${PREFIX}doorbell") },
        ))

    private fun call() = LiveActivity("${PREFIX}call", ActivityKind.CALL, LiveContent.Call("Maya", "Video call", true),
        actions = listOf(
            IslandAction("decline", "Decline", IslandAction.Style.DESTRUCTIVE) { island.end("${PREFIX}call") },
            IslandAction("accept", "Accept", IslandAction.Style.ACCEPT) { island.end("${PREFIX}call") },
        ))

    companion object { const val PREFIX = "demo:" }
}
