package app.vesper.feature.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightLuxFileTest {
    @Test fun parsesTheDriversLuxFile() {
        assertEquals(0f, NightController.parseLux("0 lux\n"))
        assertEquals(93f, NightController.parseLux("93 lux"))
        assertEquals(12.5f, NightController.parseLux("12.5"))
        assertNull(NightController.parseLux(""))
        assertNull(NightController.parseLux("-1 lux"))
        assertNull(NightController.parseLux("busy"))
    }
}
