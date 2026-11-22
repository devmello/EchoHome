package com.echobedside.feature.home

import com.echobedside.feature.home.ha.DemoHomeAssistant
import com.echobedside.feature.home.home.HomeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeControllerTest {
    private fun TestScope.setup(): Pair<DemoHomeAssistant, HomeController> {
        val ha = DemoHomeAssistant(backgroundScope, latencyMs = 150)
        val c = HomeController(ha, MemSettings(), backgroundScope, defaults = DemoHomeAssistant::demoDefaults)
        c.start()
        runCurrent()
        return ha to c
    }

    private fun HomeController.toggleOn(id: String) = ui.value!!.toggles.first { it.entityId == id }.on

    @Test fun optimisticThenConfirmed() = runTest {
        val (ha, c) = setup()
        assertEquals("Bedroom", c.ui.value!!.title)
        assertFalse(c.toggleOn("light.ceiling"))
        c.toggle("light.ceiling")
        runCurrent()
        assertTrue(c.toggleOn("light.ceiling")) // at once, before HA answers
        assertEquals("off", ha.entities.value.getValue("light.ceiling").state)
        advanceTimeBy(200); runCurrent()
        assertEquals("on", ha.entities.value.getValue("light.ceiling").state)
        assertTrue(c.toggleOn("light.ceiling"))
    }

    @Test fun rollsBackAndShowsAnErrorWhenHaRefuses() = runTest {
        val (ha, c) = setup()
        ha.failCalls = true
        c.toggle("fan.air_purifier")
        runCurrent()
        assertFalse(c.toggleOn("fan.air_purifier"))
        advanceTimeBy(200); runCurrent()
        assertTrue(c.toggleOn("fan.air_purifier")) // rolled back
        assertEquals("Couldn't change Air purifier", c.ui.value!!.error)
        advanceTimeBy(3600); runCurrent()
        assertNull(c.ui.value!!.error)
    }

    @Test fun thermostatTapsAreDebouncedIntoOneCall() = runTest {
        val (ha, c) = setup()
        repeat(3) { c.nudgeTarget("climate.thermostat", +1); advanceTimeBy(300); runCurrent() }
        assertEquals(71.0, c.ui.value!!.thermostat!!.target!!, 0.0)
        assertEquals(68.0, ha.entities.value.getValue("climate.thermostat").attributes.getDouble("temperature"), 0.0) // not sent yet
        advanceTimeBy(1000); runCurrent()
        assertEquals(71.0, ha.entities.value.getValue("climate.thermostat").attributes.getDouble("temperature"), 0.0)
    }

    @Test fun brightnessZeroTurnsTheLampOffAndRoomsSwitch() = runTest {
        val (ha, c) = setup()
        c.setBrightness("light.bedside_lamp", 0)
        runCurrent()
        assertEquals(0, c.ui.value!!.lamp!!.pct)
        advanceTimeBy(200); runCurrent()
        assertEquals("off", ha.entities.value.getValue("light.bedside_lamp").state)
        c.selectRoom("kitchen"); runCurrent()
        assertEquals("Kitchen", c.ui.value!!.title)
        c.activateScene("scene.movie"); runCurrent()
        assertEquals("Movie", c.ui.value!!.scenes.single { it.active }.name)
    }
}
