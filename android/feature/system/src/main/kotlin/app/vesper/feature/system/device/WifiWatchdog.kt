package app.vesper.feature.system.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.SettingsStore
import app.vesper.feature.system.SystemSettings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Gets the Echo back on Wi-Fi when it's stuck on an access point that no longer passes traffic
 * (see [WifiWatchdogPolicy] for why and when). Once a minute, on a background coroutine, it pings
 * the Wi-Fi network's router; the policy decides whether to drop and rejoin or restart Wi-Fi.
 *
 * `WifiManager.disconnect/reconnect/setWifiEnabled` only work for system apps on Android 10+:
 * EchoHome is a /system/priv-app (scripts/echo-system-install.sh). Setting `system.wifi.watchdog`
 * (default on) switches it off. Logs: tag `VesperSystem`, prefix "wifi watchdog".
 */
internal class WifiWatchdog(
    private val app: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val policy = WifiWatchdogPolicy()
    private var job: Job? = null
    private var lastGateway: String? = null

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            delay(WifiWatchdogPolicy.PROBE_MS) // nothing to judge during startup
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.w(TAG, "wifi watchdog: $e") }
                delay(WifiWatchdogPolicy.PROBE_MS)
            }
        }
    }

    /** Debug builds (`am broadcast -a app.vesper.system.DEBUG --es wifi rejoin|restart`): act now. */
    fun debugAct(what: String) {
        val a = when (what) { "rejoin" -> WifiWatchdogPolicy.Action.RECONNECT; "restart" -> WifiWatchdogPolicy.Action.RESTART_WIFI; else -> return }
        scope.launch(Dispatchers.IO) { app.getSystemService(WifiManager::class.java)?.let { perform(it, a) } }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun tick() {
        if (!settings.getBoolean(SystemSettings.WIFI_WATCHDOG, true)) return
        val wm = app.getSystemService(WifiManager::class.java) ?: return
        val enabled = wm.isWifiEnabled
        val wifi = wifiNetwork()
        val connected = enabled && wifi != null
        var gateway: String? = null
        var reachable = false
        if (connected) {
            val lp = cm()?.getLinkProperties(wifi)
            val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            if (gw != null && !gw.isAnyLocalAddress) {
                gateway = gw.hostAddress
                reachable = ping(gw, lp.interfaceName)
            }
        }
        if (gateway != lastGateway) { Log.i(TAG, "wifi watchdog: gateway $gateway"); lastGateway = gateway }
        val action = policy.onProbe(SystemClock.elapsedRealtime(), enabled, connected, gateway, reachable)
        if (connected && gateway != null && !reachable) Log.i(TAG, "wifi watchdog: gateway $gateway didn't answer (${policy.describe()})")
        perform(wm, action)
    }

    @Suppress("DEPRECATION")
    private fun perform(wm: WifiManager, action: WifiWatchdogPolicy.Action) {
        when (action) {
            WifiWatchdogPolicy.Action.NONE -> {}
            WifiWatchdogPolicy.Action.RECONNECT -> {
                Log.w(TAG, "wifi watchdog: no traffic, rejoining Wi-Fi (${policy.describe()})")
                val d = wm.disconnect()
                Thread.sleep(2_000)
                val r = wm.reconnect()
                Log.i(TAG, "wifi watchdog: disconnect=$d reconnect=$r")
            }
            WifiWatchdogPolicy.Action.RESTART_WIFI -> {
                Log.w(TAG, "wifi watchdog: still no traffic, restarting Wi-Fi (${policy.describe()})")
                val off = wm.setWifiEnabled(false)
                Thread.sleep(4_000)
                val on = wm.setWifiEnabled(true)
                Log.i(TAG, "wifi watchdog: wifi off=$off on=$on")
            }
        }
    }

    private fun cm() = app.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        val cm = cm() ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /** ICMP echo on the Wi-Fi interface (falls back to TCP port 7, where a refusal also counts). Two tries. */
    private fun ping(gw: InetAddress, iface: String?): Boolean {
        val nif = iface?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
        repeat(2) {
            val ok = runCatching { if (nif != null) gw.isReachable(nif, 0, PING_TIMEOUT_MS) else gw.isReachable(PING_TIMEOUT_MS) }
                .getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private companion object {
        const val TAG = "VesperSystem"
        const val PING_TIMEOUT_MS = 2_000
    }
}
