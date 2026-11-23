package app.vesper.feature.phone

import app.vesper.feature.phone.battery.Battery
import app.vesper.feature.phone.battery.BatteryPolicy
import app.vesper.feature.phone.battery.BatteryPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPolicyTest {
    private val p = BatteryPolicy()

    @Test fun parsesBatteryLevel() {
        assertEquals(87, Battery.parse(byteArrayOf(87)))
        assertEquals(100, Battery.parse(byteArrayOf(100, 1)))
        assertNull(Battery.parse(byteArrayOf()))
        assertNull(Battery.parse(byteArrayOf(0xFF.toByte())))
        assertNull(Battery.parse(null))
    }

    @Test fun firstLevelAfterConnectShowsFiveSeconds() {
        val d = p.onLevel(80, null)
        assertEquals(Kind.CONNECT, d.show)
        assertEquals(5_000L, d.durationMs)
        assertNull(p.onLevel(79, null).show)
    }

    @Test fun reconnectAnnouncesAgain() {
        p.onLevel(80, null)
        p.onConnectionChanged()
        assertEquals(Kind.CONNECT, p.onLevel(80, null).show)
    }

    @Test fun lowThenCriticalOncePerDischarge() {
        p.onLevel(50, null)
        assertEquals(Kind.LOW, p.onLevel(20, null).show)
        assertNull(p.onLevel(19, null).show)
        assertEquals(Kind.CRITICAL, p.onLevel(10, null).show)
        assertNull(p.onLevel(9, null).show)
        assertNull(p.onLevel(11, null).show)
        assertNull(p.onLevel(10, null).show)
    }

    @Test fun jumpingStraightToCriticalShowsOnlyCritical() {
        p.onLevel(50, null)
        assertEquals(Kind.CRITICAL, p.onLevel(8, null).show)
        assertNull(p.onLevel(7, null).show)
    }

    @Test fun chargingBackUpStartsANewDischarge() {
        p.onLevel(50, null)
        p.onLevel(20, null)
        assertNull(p.onLevel(26, null).show)
        assertNull(p.onLevel(24, null).show) // above 20 again, no warning
        assertEquals(Kind.LOW, p.onLevel(20, null).show)
    }

    @Test fun chargingEndsTheActivityAndRearms() {
        p.onLevel(50, null)
        p.onLevel(15, null)
        assertTrue(p.onLevel(16, true).end)
        assertEquals(Kind.LOW, p.onLevel(19, false).show)
    }

    @Test fun lowStaysUntilChargingWhenThePhoneReportsNotCharging() {
        p.onLevel(50, false)
        val d = p.onLevel(20, false)
        assertEquals(Kind.LOW, d.show)
        assertNull(d.durationMs)
    }

    @Test fun lowWithoutChargingInfoTimesOut() {
        p.onLevel(50, null)
        assertEquals(BatteryPolicy.LOW_MS, p.onLevel(20, null).durationMs)
    }

    @Test fun connectingAlreadyLowDoesNotWarnAgainRightAway() {
        assertEquals(Kind.CONNECT, p.onLevel(15, null).show)
        assertNull(p.onLevel(14, null).show)
        assertEquals(Kind.CRITICAL, p.onLevel(10, null).show)
    }
}
