package app.vesper.feature.camera

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.DefaultIslandManager
import app.vesper.core.model.LiveContent
import app.vesper.core.model.Profile
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
    private val maya = Profile("maya", "Maya", 0xFFFF9F0A.toInt())

    private fun kinds(island: DefaultIslandManager) = island.activities.value.map { it.kind }
    private fun content(island: DefaultIslandManager) = island.activities.value.single().content

    @Test fun showsWhileCheckingAndEndsAfterTheHold() = runTest {
        val island = DefaultIslandManager()
        val state = MutableStateFlow<FaceIsland>(FaceIsland.Idle)
        val job = FaceCheckIsland(island, backgroundScope, holdMs = 600).bind(state)
        runCurrent()
        assertTrue(island.activities.value.isEmpty())

        state.value = FaceIsland.Checking; runCurrent()
        assertEquals(listOf(ActivityKind.FACE_CHECK), kinds(island))
        assertEquals(LiveContent.FaceCheck, content(island))

        state.value = FaceIsland.Idle; runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals("still held", listOf(ActivityKind.FACE_CHECK), kinds(island))
        advanceTimeBy(200); runCurrent()
        assertTrue(island.activities.value.isEmpty())
        job.cancel()
    }

    @Test fun aNewBurstInsideTheHoldKeepsItUp() = runTest {
        val island = DefaultIslandManager()
        val state = MutableStateFlow<FaceIsland>(FaceIsland.Checking)
        val job = FaceCheckIsland(island, backgroundScope, holdMs = 600).bind(state)
        runCurrent()
        state.value = FaceIsland.Idle; runCurrent()
        advanceTimeBy(300); runCurrent()
        state.value = FaceIsland.Checking; runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(ActivityKind.FACE_CHECK), kinds(island))
        job.cancel()
    }

    @Test fun aMatchSaysHelloForThreeSecondsThenGoesBack() = runTest {
        val island = DefaultIslandManager()
        val state = MutableStateFlow<FaceIsland>(FaceIsland.Checking)
        val job = FaceCheckIsland(island, backgroundScope, holdMs = 600, helloMs = 3_000, now = { 42L }).bind(state)
        runCurrent()
        state.value = FaceIsland.Hello(maya); runCurrent()
        assertEquals(LiveContent.FaceHello("Maya", 0xFFFF9F0A.toInt(), 42L), content(island))
        // the hold from the burst before must not end the hello early
        advanceTimeBy(2_900); runCurrent()
        assertEquals(LiveContent.FaceHello("Maya", 0xFFFF9F0A.toInt(), 42L), content(island))
        // 3 s: the hello ends although the greeting (4 s) is still up
        advanceTimeBy(200); runCurrent()
        assertTrue(island.activities.value.isEmpty())
        state.value = FaceIsland.Idle; runCurrent()
        assertTrue(island.activities.value.isEmpty())
        job.cancel()
    }

    @Test fun notMeEndsTheHelloAtOnce() = runTest {
        val island = DefaultIslandManager()
        val state = MutableStateFlow<FaceIsland>(FaceIsland.Hello(maya))
        val job = FaceCheckIsland(island, backgroundScope, helloMs = 3_000).bind(state)
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        state.value = FaceIsland.Idle; runCurrent()
        assertTrue(island.activities.value.isEmpty())
        job.cancel()
    }

    @Test fun stateFromTheCamera() {
        assertEquals(FaceIsland.Idle, FaceIsland.of(CameraUi(phase = CameraPhase.PRESENCE)))
        assertEquals(FaceIsland.Checking, FaceIsland.of(CameraUi(phase = CameraPhase.DETECTING)))
        assertEquals(FaceIsland.Hello(maya), FaceIsland.of(CameraUi(phase = CameraPhase.RECOGNIZED, greeting = maya)))
        assertEquals(FaceIsland.Idle, FaceIsland.of(CameraUi(phase = CameraPhase.ENROLLING)))
    }
}
