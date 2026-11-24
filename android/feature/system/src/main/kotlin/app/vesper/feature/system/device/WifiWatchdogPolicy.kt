package app.vesper.feature.system.device

/**
 * When the Wi-Fi watchdog ([WifiWatchdog]) steps in. Pure logic, no Android: unit-tested.
 *
 * Why: on Nov 23, 2026 the Echo stayed associated with a mesh access point that had stopped passing
 * traffic, for 9 hours (13:21 → 22:15). Android logged a data stall and "lost internet" at 15:25
 * but never moved to another access point by itself, so AirPlay, Spotify, adb and the settings
 * page were all unreachable.
 *
 * The probe is the Wi-Fi network's default gateway (the router), once a minute. An internet
 * outage with a healthy router changes nothing (reconnecting wouldn't help). Rules:
 * - Only a gateway that has answered at least once is trusted: a router that ignores pings must
 *   not make the Echo restart its Wi-Fi forever.
 * - [FAILURES_TO_ACT] misses in a row while connected → act. Wi-Fi switched on but not connected
 *   for [DISCONNECTED_GRACE_MS] → act too (Android normally rejoins well before that).
 * - First action: [Action.RECONNECT] (drop and rejoin, so the supplicant picks an access point
 *   again). Every later one, until the gateway answers again: [Action.RESTART_WIFI]. The gap
 *   between actions doubles from [MIN_GAP_MS] to [MAX_GAP_MS].
 * - Wi-Fi switched off (by the user) resets everything and never acts.
 */
internal class WifiWatchdogPolicy {
    enum class Action { NONE, RECONNECT, RESTART_WIFI }

    /** Gateway addresses that have answered at least once (by address string). */
    private val trusted = HashSet<String>()
    private var misses = 0
    private var disconnectedSince = -1L
    private var actions = 0
    private var lastActionAt = 0L

    /** For logs and the debug dump. */
    fun describe() = "misses=$misses actions=$actions trusted=${trusted.size}"

    /**
     * One probe result.
     * @param gateway the Wi-Fi gateway's address, or null when none is known.
     * @param reachable whether it answered; ignored when [gateway] is null.
     */
    fun onProbe(now: Long, wifiEnabled: Boolean, connected: Boolean, gateway: String?, reachable: Boolean): Action {
        if (!wifiEnabled) { reset(); disconnectedSince = -1; return Action.NONE }
        if (!connected) {
            misses = 0
            if (disconnectedSince < 0) disconnectedSince = now
            return if (now - disconnectedSince >= DISCONNECTED_GRACE_MS) act(now) else Action.NONE
        }
        disconnectedSince = -1
        if (gateway == null) return Action.NONE
        if (reachable) { trusted += gateway; reset(); return Action.NONE }
        if (gateway !in trusted) return Action.NONE
        misses++
        return if (misses >= FAILURES_TO_ACT) act(now) else Action.NONE
    }

    private fun act(now: Long): Action {
        if (actions > 0 && now - lastActionAt < gapAfter(actions)) return Action.NONE
        val a = if (actions == 0) Action.RECONNECT else Action.RESTART_WIFI
        actions++
        lastActionAt = now
        misses = 0
        return a
    }

    private fun reset() { misses = 0; actions = 0; lastActionAt = 0 }

    companion object {
        const val PROBE_MS = 60_000L
        const val FAILURES_TO_ACT = 3
        const val DISCONNECTED_GRACE_MS = 5 * 60_000L
        const val MIN_GAP_MS = 5 * 60_000L
        const val MAX_GAP_MS = 60 * 60_000L

        /** Wait after the [n]th action (1-based) before the next: 5, 10, 20, 40, 60, 60… min. */
        fun gapAfter(n: Int): Long {
            var g = MIN_GAP_MS
            repeat((n - 1).coerceIn(0, 10)) { g = (g * 2).coerceAtMost(MAX_GAP_MS) }
            return g
        }
    }
}
