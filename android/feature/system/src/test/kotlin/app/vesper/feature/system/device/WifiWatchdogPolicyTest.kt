package app.vesper.feature.system.device

import app.vesper.feature.system.device.WifiWatchdogPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class WifiWatchdogPolicyTest {
    private val min = 60_000L
    private val gw = "192.168.10.1"

    /** Feeds one probe per minute starting at [t0]; returns the actions taken, by minute index. */
    private fun WifiWatchdogPolicy.run(t0: Long, minutes: Int, connected: Boolean = true, reachable: Boolean = false,
                                       enabled: Boolean = true, gateway: String? = gw): Map<Int, Action> =
        (0 until minutes).associateWith { onProbe(t0 + it * min, enabled, connected, gateway, reachable) }
            .filterValues { it != Action.NONE }

    @Test fun healthyRouterNeverActs() {
        val p = WifiWatchdogPolicy()
        assertEquals(emptyMap<Int, Action>(), p.run(0, 120, reachable = true))
    }

    @Test fun untrustedGatewayIsIgnored() {
        // A router that has never answered a ping (it may just drop ICMP): never act on it.
        val p = WifiWatchdogPolicy()
        assertEquals(emptyMap<Int, Action>(), p.run(0, 120))
    }

    @Test fun threeMissesRejoinThenRestartsWithGrowingGaps() {
        val p = WifiWatchdogPolicy()
        p.onProbe(0, true, true, gw, true) // trusted
        val acts = p.run(min, 200)
        // misses at minutes 0,1,2 → rejoin at minute 2; then 5, 10, 20, 40, 60 min gaps → restarts
        assertEquals(Action.RECONNECT, acts[2])
        val restarts = acts.filterValues { it == Action.RESTART_WIFI }.keys.sorted()
        assertEquals(listOf(7, 17, 37, 77, 137, 197), restarts)
    }

    @Test fun recoveryResetsTheLadder() {
        val p = WifiWatchdogPolicy()
        p.onProbe(0, true, true, gw, true)
        assertEquals(Action.RECONNECT, p.run(min, 3)[2])
        p.onProbe(10 * min, true, true, gw, true) // the rejoin worked
        assertEquals(mapOf(2 to Action.RECONNECT), p.run(11 * min, 3))
    }

    @Test fun twoMissesThenAnAnswerDoesNothing() {
        val p = WifiWatchdogPolicy()
        p.onProbe(0, true, true, gw, true)
        p.run(min, 2)
        p.onProbe(3 * min, true, true, gw, true)
        assertEquals(emptyMap<Int, Action>(), p.run(4 * min, 2))
    }

    @Test fun wifiSwitchedOffNeverActs() {
        val p = WifiWatchdogPolicy()
        p.onProbe(0, true, true, gw, true)
        assertEquals(emptyMap<Int, Action>(), p.run(min, 120, enabled = false, connected = false))
    }

    @Test fun disconnectedForFiveMinutesActs() {
        val p = WifiWatchdogPolicy()
        val acts = p.run(0, 6, connected = false, gateway = null)
        assertEquals(mapOf(5 to Action.RECONNECT), acts)
    }

    @Test fun noGatewayKnownDoesNothing() {
        val p = WifiWatchdogPolicy()
        assertEquals(emptyMap<Int, Action>(), p.run(0, 60, gateway = null))
    }

    @Test fun gaps() {
        assertEquals(listOf(5L, 10L, 20L, 40L, 60L, 60L), (1..6).map { WifiWatchdogPolicy.gapAfter(it) / min })
    }
}
