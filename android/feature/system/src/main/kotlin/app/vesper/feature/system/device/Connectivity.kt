package app.vesper.feature.system.device

import app.vesper.feature.system.ui.Snapshot
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Radio state for Control Center's connectivity tile (not in SystemState). */
data class RadioState(
    val wifiOn: Boolean = false,
    /** null when unknown (Android hides the SSID from apps without location permission). */
    val wifiName: String? = null,
    val wifiConnected: Boolean = false,
    val bluetoothOn: Boolean = false,
    val bluetoothAvailable: Boolean = false,
)

/**
 * Watches Wi-Fi and Bluetooth with sticky broadcasts (no polling).
 * Wi-Fi can't be switched by a normal app on Android 10+ (NOTES §5.5): needs the priv-app install.
 */
internal class Connectivity(private val app: Context, @Suppress("unused") private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    fun start() {
        if (registered) return
        registered = true
        val f = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        app.registerReceiver(receiver, f)
        refresh()
    }

    /** [refresh] on the background thread: its binder calls can stall the UI thread for tens of ms (CC opening). */
    fun refreshAsync() = Snapshot.offMain({ refresh() }) {}

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun refresh() {
        val wm = app.getSystemService(WifiManager::class.java)
        val bt = BluetoothAdapter.getDefaultAdapter()
        val info = try { wm?.connectionInfo } catch (_: Exception) { null }
        val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && !it.startsWith("<") }
        _state.update {
            it.copy(
                wifiOn = wm?.isWifiEnabled == true,
                wifiConnected = (info?.networkId ?: -1) != -1,
                wifiName = ssid,
                bluetoothAvailable = bt != null,
                bluetoothOn = try { bt?.isEnabled == true } catch (_: SecurityException) { false },
            )
        }
    }
}
