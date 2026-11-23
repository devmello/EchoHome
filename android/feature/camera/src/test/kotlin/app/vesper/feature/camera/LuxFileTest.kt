package app.vesper.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The camera's first light reading when the HAL sends none (same rule as Night's). */
class LuxFileTest {
    @Test fun parsesTheDriversLuxFile() {
        assertEquals(0f, CameraPipeline.parseLux("0 lux\n"))
        assertEquals(93f, CameraPipeline.parseLux("93 lux"))
        assertEquals(12.5f, CameraPipeline.parseLux("12.5"))
        assertNull(CameraPipeline.parseLux(""))
        assertNull(CameraPipeline.parseLux("-1 lux"))
        assertNull(CameraPipeline.parseLux("busy"))
    }

    @Test fun waitsForNightsReadingFirst() {
        // Night (`:feature:clock` NightController.FIRST_READING_MS) reads at 1.5 s and publishes ambientLux.
        assert(CameraPipeline.FIRST_LUX_MS > 1_500L)
    }
}
