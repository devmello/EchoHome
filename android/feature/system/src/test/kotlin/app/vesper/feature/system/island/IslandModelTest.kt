package app.vesper.feature.system.island

import app.vesper.core.model.ActivityKind
import app.vesper.core.model.DefaultIslandManager
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.MediaSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IslandModelTest {
    private val timer = LiveActivity("timer:1", ActivityKind.TIMER, LiveContent.Timer("Pasta", 600_000, 1_000_000))
    private val music = LiveActivity("music", ActivityKind.MUSIC, LiveContent.Music("Low Tide Radio", "Marisol Vey", null, true, MediaSourceKind.AIRPLAY), ownerScreen = "nowplaying")
    private val assistant = LiveActivity("assistant", ActivityKind.ASSISTANT, LiveContent.Assistant(app.vesper.core.model.AssistantPhase.LISTENING))
    private val door = LiveActivity("door", ActivityKind.DOORBELL, LiveContent.Doorbell("Front door"))
    private val call = LiveActivity("call", ActivityKind.CALL, LiveContent.Call("Maya", "Video call", true))

    private fun visible(vararg a: LiveActivity, screen: String? = "standby", now: Long = 0): List<LiveActivity> {
        val m = DefaultIslandManager()
        a.forEach { m.publish(it) }
        return m.visible(screen, now)
    }

    @Test fun hiddenWhenNothingRuns() {
        val l = IslandModel().update(emptyList())
        assertEquals(IslandMode.HIDDEN, l.mode)
    }

    @Test fun compactSizesFromTheDesign() {
        assertEquals(IslandBox(230f, 44f, 22f), IslandModel().update(visible(timer)).box)
        assertEquals(IslandBox(250f, 44f, 22f), IslandModel().update(visible(music)).box)
        assertEquals(IslandBox(220f, 44f, 22f), IslandModel().update(visible(assistant)).box)
    }

    @Test fun splitPutsSecondActivityInTheBead() {
        val l = IslandModel().update(visible(timer, music))
        assertEquals(IslandMode.COMPACT, l.mode)
        assertEquals("music", l.main?.id)          // music outranks a running timer
        assertEquals("timer:1", l.bead?.id)
        assertEquals(480f + 125f + 8f, l.beadX)
    }

    @Test fun atMostTwoByPriority() {
        val l = IslandModel().apply { update(visible(timer, music, assistant)); collapse() }.layout
        assertEquals("assistant", l.main?.id)
        assertEquals("music", l.bead?.id)
    }

    @Test fun ownerScreenHidesItsActivity() {
        val l = IslandModel().update(visible(timer, music, screen = "nowplaying"))
        assertEquals("timer:1", l.main?.id)
        assertNull(l.bead)
    }

    @Test fun tapExpandsTapOutsideCollapses() {
        val m = IslandModel()
        m.update(visible(music, timer))
        val e = m.tapMain()
        assertEquals(IslandMode.EXPANDED, e.mode)
        assertEquals(IslandBox(660f, 160f, 48f), e.box)
        assertNull("no bead while expanded", e.bead)
        assertEquals(IslandMode.COMPACT, m.collapse().mode)
        val b = m.tapBead()
        assertEquals("timer:1", b.main?.id)
        assertEquals(IslandBox(460f, 120f, 44f), b.box)
    }

    @Test fun assistantDoesNotExpand() {
        val m = IslandModel()
        m.update(visible(assistant))
        assertEquals(IslandMode.COMPACT, m.tapMain().mode)
    }

    @Test fun alertsExpandOnTheirOwnOnce() {
        val m = IslandModel()
        m.update(visible(music))
        val l = m.update(visible(music, door))
        assertEquals(IslandMode.EXPANDED, l.mode)
        assertEquals(IslandBox(640f, 196f, 50f), l.box)
        m.collapse()
        // the same alert updating doesn't re-expand
        assertEquals(IslandMode.COMPACT, m.update(visible(music, door.copy(content = LiveContent.Doorbell("Front door 2")))).mode)
        // a call is a new alert and outranks the doorbell
        val c = m.update(visible(music, door, call))
        assertEquals("call", c.main?.id)
        assertEquals(IslandBox(580f, 116f, 58f), c.box)
    }

    @Test fun expandedActivityEndingCollapses() {
        val m = IslandModel()
        m.update(visible(music, timer))
        m.tapBead()
        val l = m.update(visible(music))
        assertEquals(IslandMode.COMPACT, l.mode)
        assertEquals("music", l.main?.id)
    }

    @Test fun staleActivitiesAreDropped() {
        val stale = timer.copy(staleAfterElapsed = 100)
        assertEquals(IslandMode.HIDDEN, IslandModel().update(visible(stale, now = 200)).mode)
    }

    @Test fun bluetoothConnectedIsCompactOnlyAndBelowTimers() {
        val bt = LiveActivity("bt", ActivityKind.BLUETOOTH_CONNECTED, LiveContent.BluetoothConnected("Maya’s iPhone", audio = true, notifications = true))
        val m = IslandModel()
        val l = m.update(visible(bt, timer))
        assertEquals("timer:1", l.main?.id)
        assertEquals("bt", l.bead?.id)
        assertEquals(IslandBox(250f, 44f, 22f), IslandModel.compactBox(ActivityKind.BLUETOOTH_CONNECTED))
        assertEquals(IslandMode.COMPACT, m.tapBead().mode)
        assertEquals(IslandMode.COMPACT, IslandModel().apply { update(visible(bt)) }.tapMain().mode)
    }
}
