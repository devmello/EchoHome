package app.vesper.feature.camera

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.IslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * "Checking face" + spinner in the island while the camera's face burst runs (2 s bursts, up to
 * three, 8 s apart). The island keeps it [holdMs] after a burst ends, so a burst that ends and
 * starts again doesn't make the island flicker. The "Hi <name>" greeting stays in the overlay.
 */
class FaceCheckIsland(
    private val island: IslandManager,
    private val scope: CoroutineScope,
    private val holdMs: Long = HOLD_MS,
) {
    private var ending: Job? = null

    fun bind(checking: Flow<Boolean>): Job = scope.launch {
        checking.distinctUntilChanged().collect { on -> if (on) show() else hideSoon() }
    }

    private fun show() {
        ending?.cancel(); ending = null
        if (island.activities.value.none { it.id == ID }) island.publish(LiveActivity(ID, ActivityKind.FACE_CHECK, LiveContent.FaceCheck))
    }

    private fun hideSoon() {
        if (island.activities.value.none { it.id == ID } || ending?.isActive == true) return
        ending = scope.launch { delay(holdMs); island.end(ID) }
    }

    companion object {
        const val ID = "camera:facecheck"
        const val HOLD_MS = 600L
    }
}
