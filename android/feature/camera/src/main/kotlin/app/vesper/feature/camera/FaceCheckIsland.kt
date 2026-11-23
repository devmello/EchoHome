package app.vesper.feature.camera

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.IslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What the island says about the camera's face check. */
sealed interface FaceIsland {
    data object Idle : FaceIsland
    data object Checking : FaceIsland
    /** Matched: "Hi <name>" while the greeting is up (the camera's greeting lasts 4 s). */
    data class Hello(val profile: Profile) : FaceIsland

    companion object {
        /** From the camera's state: the greeting wins, then a running face burst. */
        fun of(ui: CameraUi): FaceIsland = when {
            ui.greeting != null -> Hello(ui.greeting)
            ui.phase == CameraPhase.DETECTING -> Checking
            else -> Idle
        }
    }
}

/**
 * "Checking face" + spinner in the island while the camera's face burst runs (2 s bursts, up to
 * three, 8 s apart), then "Hi <name>" with the person's avatar when it matches. The island keeps
 * "Checking face" [holdMs] after a burst ends, so bursts don't make it flicker; the hello ends
 * with the greeting. The overlay's greeting card (chips, "Not me") stays at the bottom.
 */
class FaceCheckIsland(
    private val island: IslandManager,
    private val scope: CoroutineScope,
    private val holdMs: Long = HOLD_MS,
) {
    private var ending: Job? = null

    fun bind(states: Flow<FaceIsland>): Job = scope.launch {
        states.distinctUntilChanged().collect { s ->
            when (s) {
                FaceIsland.Checking -> show(LiveContent.FaceCheck)
                is FaceIsland.Hello -> show(LiveContent.FaceHello(s.profile.name, s.profile.color))
                FaceIsland.Idle -> hide()
            }
        }
    }

    private fun current() = island.activities.value.firstOrNull { it.id == ID }

    private fun show(content: LiveContent) {
        ending?.cancel(); ending = null
        if (current()?.content != content) island.publish(LiveActivity(ID, ActivityKind.FACE_CHECK, content))
    }

    private fun hide() {
        val shown = current() ?: return
        if (shown.content !is LiveContent.FaceCheck) { ending?.cancel(); island.end(ID); return }
        if (ending?.isActive == true) return
        ending = scope.launch { delay(holdMs); island.end(ID) }
    }

    companion object {
        const val ID = "camera:facecheck"
        const val HOLD_MS = 600L
    }
}
