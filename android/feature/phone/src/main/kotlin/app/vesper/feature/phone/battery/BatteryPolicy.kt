package app.vesper.feature.phone.battery

import java.util.UUID

/** Bluetooth SIG Battery Service (0x180F) and its Battery Level characteristic (0x2A19, uint8 percent). */
object Battery {
    val SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805f9b34fb")
    val LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805f9b34fb")

    /** A Battery Level value: one byte, 0..100. Anything else (empty, 255, ...) is not a level. */
    fun parse(b: ByteArray?): Int? {
        if (b == null || b.isEmpty()) return null
        val v = b[0].toInt() and 0xFF
        return if (v in 0..100) v else null
    }
}

/**
 * Decides when the iPhone's battery shows on the island (pure, single-threaded).
 *
 *  - CONNECT: the first level after the phone connects, shown for [CONNECT_MS].
 *  - LOW at <= 20 % and CRITICAL at <= 10 %, once each per discharge. A discharge ends when the
 *    phone reports charging, or its level climbs back above [RESET_ABOVE] (the Battery Service
 *    has no charging flag, so that's the fallback).
 *  - Charging (when known) ends whatever is showing.
 */
class BatteryPolicy {
    enum class Kind { CONNECT, LOW, CRITICAL }

    /** [show] a battery activity for [durationMs] (null = until [end]); [end] = take it down. */
    data class Decision(val show: Kind? = null, val durationMs: Long? = null, val end: Boolean = false)

    private var connectPending = true
    private var lowShown = false
    private var criticalShown = false

    /** The phone's link came up or dropped: the next level counts as a fresh connection. */
    fun onConnectionChanged() { connectPending = true }

    fun onLevel(percent: Int, charging: Boolean?): Decision {
        if (charging == true) {
            lowShown = false; criticalShown = false; connectPending = false
            return Decision(end = true)
        }
        if (percent > RESET_ABOVE) { lowShown = false; criticalShown = false }
        val critical = percent <= CRITICAL_AT
        val low = percent <= LOW_AT
        val threshold = when {
            critical && !criticalShown -> Kind.CRITICAL
            low && !critical && !lowShown -> Kind.LOW
            else -> null
        }
        if (low) lowShown = true
        if (critical) criticalShown = true
        val first = connectPending
        connectPending = false
        val shown = if (first) Kind.CONNECT else threshold
        val ms = when {
            shown == null -> null
            shown == Kind.CONNECT -> CONNECT_MS
            // A phone that says it isn't charging keeps the warning until it charges; otherwise a while.
            charging == false -> null
            else -> LOW_MS
        }
        return if (shown == null) Decision() else Decision(shown, ms)
    }

    companion object {
        const val LOW_AT = 20
        const val CRITICAL_AT = 10
        const val RESET_ABOVE = 25
        const val CONNECT_MS = 5_000L
        const val LOW_MS = 20_000L
    }
}
