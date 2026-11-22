package app.vesper.feature.home.glance

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits when the default network comes back as a different network (another Wi-Fi, or after a
 * drop): the moment a new public IP is likely. Not on the first callback after registering.
 * Event-driven (ConnectivityManager callback), no polling.
 */
object NetworkChanges {
    fun flow(context: Context): Flow<Unit> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var current: Network? = null
        var seen = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (seen && network != current) trySend(Unit)
                seen = true
                current = network
            }
            override fun onLost(network: Network) { if (network == current) current = null }
        }
        try { cm?.registerDefaultNetworkCallback(cb) } catch (_: SecurityException) { close(); return@callbackFlow }
        awaitClose { try { cm?.unregisterNetworkCallback(cb) } catch (_: IllegalArgumentException) { } }
    }
}
