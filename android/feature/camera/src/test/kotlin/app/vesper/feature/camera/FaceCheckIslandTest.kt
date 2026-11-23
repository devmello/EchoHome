package app.vesper.feature.camera

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.DefaultIslandManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaceCheckIslandTest {
    private fun kinds(island: DefaultIslandManager) = island.activities.value.map { it.kind }

    @Test fun showsWhileCheckingAndEndsAfterTheHold() = runTest {
        val island = DefaultIslandManager()
        val checking = MutableStateFlow(false)
        val job = FaceCheckIsland(island, backgroundScope, holdMs = 600).bind(checking)
        runCurrent()
        assertTrue(island.activities.value.isEmpty())

        checking.value = true; runCurrent()
        assertEquals(listOf(ActivityKind.FACE_CHECK), kinds(island))

        checking.value = false; runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals("still held", listOf(ActivityKind.FACE_CHECK), kinds(island))
        advanceTimeBy(200); runCurrent()
        assertTrue(island.activities.value.isEmpty())
        job.cancel()
    }

    @Test fun aNewBurstInsideTheHoldKeepsItUp() = runTest {
        val island = DefaultIslandManager()
        val checking = MutableStateFlow(true)
        val job = FaceCheckIsland(island, backgroundScope, holdMs = 600).bind(checking)
        runCurrent()
        checking.value = false; runCurrent()
        advanceTimeBy(300); runCurrent()
        checking.value = true; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(ActivityKind.FACE_CHECK), kinds(island))
        assertEquals(1, island.activities.value.size)
        job.cancel()
    }
}
