package com.echobedside.feature.phone

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.echobedside.core.model.ActivityKind
import com.echobedside.core.model.EchoServices
import com.echobedside.core.model.LiveActivity
import com.echobedside.core.model.LiveContent
import com.echobedside.feature.phone.ancs.AncsPresenter
import com.echobedside.feature.phone.ancs.AncsSession
import com.echobedside.feature.phone.audio.BluetoothMediaSource
import com.echobedside.feature.phone.ble.AncsGattClient
import com.echobedside.feature.phone.ble.LePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything iPhone-over-Bluetooth, on the main thread, driven only by Bluetooth callbacks and
 * broadcasts (no polling, no timers while idle):
 *
 *  - Notifications: LE advertising ([LePeripheral]) → the iPhone connects → [AncsGattClient]
 *    subscribes to ANCS → [AncsSession] → [AncsPresenter] → banners + call island. When the link
 *    drops it advertises again (low power), so the bonded iPhone reconnects by itself after
 *    reboots and when it comes back in range.
 *  - Audio: [BluetoothMediaSource] (the Bluetooth stack's AVRCP session) → MediaHub "bluetooth".
 *  - The "phone connected" island (BLUETOOTH_CONNECTED), shown for a few seconds when the phone
 *    connects; it stays in the activity list while connected (Control Center reads its name).
 */
class PhoneController(private val context: Context, private val services: EchoServices, private val handler: Handler) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val settings get() = services.settings

    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val peripheral: LePeripheral = LePeripheral(context, handler, object : LePeripheral.Listener {
        override fun onCentralConnected(device: BluetoothDevice) = centralConnected(device)
        override fun onCentralDisconnected(device: BluetoothDevice) {}
        override fun onAdvertisingChanged(on: Boolean, error: Int?) {
            val g = peripheral.gate
            _state.update { it.copy(advertising = on, advertiseOk = g?.started, advertiseError = error?.let { e -> LePeripheral.errorName(e) }) }
            refreshStatus()
        }
    })

    private var client: AncsGattClient? = null
    private var session: AncsSession? = null
    private var presenter: AncsPresenter? = null
    /** Centrals that turned out not to have ANCS this session (don't probe them again). */
    private val notIPhones = HashSet<String>()
    private var audioDevice: BluetoothDevice? = null
    private var started = false

    val media = BluetoothMediaSource(context, handler, { services.media }, { audioDevice?.let(::nameOf) ?: savedName() },
        disconnectAudio = ::disconnectAudio)

    /** The A2DP sink profile proxy (a binder handle, kept while Bluetooth is on). */
    private var a2dpSink: BluetoothProfile? = null

    // --- settings ---

    var notificationsEnabled: Boolean
        get() = settings.getBoolean(KEY_NOTIFICATIONS, true)
        set(v) { settings.putBoolean(KEY_NOTIFICATIONS, v); if (!v) dropAncs() ; updateMode(); publishState() }

    var audioEnabled: Boolean
        get() = settings.getBoolean(KEY_AUDIO, true)
        set(v) { settings.putBoolean(KEY_AUDIO, v); media.enabled = v; publishState() }

    private fun savedAddress() = settings.getString(KEY_ADDRESS, "")
    private fun savedName() = settings.getString(KEY_NAME, "iPhone")

    // --- lifecycle ---

    fun start() {
        if (started) return
        started = true
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(ACTION_A2DP_SINK_STATE)
        }
        context.registerReceiver(receiver, f, null, handler)
        media.enabled = audioEnabled
        services.scope.launch { services.notifications.banners.collect { presenter?.onFeedChanged() } }
        val g = peripheral.probeGate()
        Log.i(TAG, "LE gate: $g")
        _state.update { it.copy(advertiseOk = g.started) }
        if (adapter?.isEnabled == true) onBluetoothOn()
        publishState()
    }

    private fun onBluetoothOn() {
        media.start()
        findConnectedAudio()
        peripheral.onAdapterOn()
        updateMode()
    }

    private fun onBluetoothOff() {
        dropAncs()
        media.stop()
        audioDevice = null
        a2dpSink?.let { p -> runCatching { adapter?.closeProfileProxy(A2DP_SINK, p) } }
        a2dpSink = null
        peripheral.onAdapterOff()
        updateBluetoothActivity()
    }

    /**
     * The pairing screen is open: discoverable + fast advertising. The screen is also "hidden"
     * while the system's discoverable / pairing dialogs pause our activity, so turning off is
     * debounced and the discoverable prompt is asked once per window, not on every resume.
     */
    fun setPairingMode(on: Boolean, activity: Activity?) {
        handler.removeCallbacks(pairingOff)
        if (!on) { handler.postDelayed(pairingOff, 10_000); return }
        _state.update { it.copy(pairingMode = true) }
        val now = SystemClock.elapsedRealtime()
        if (activity != null && adapter?.isEnabled == true && adapter?.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE &&
            now - discoverableAskedAt > DISCOVERABLE_S * 1000L) {
            discoverableAskedAt = now
            requestDiscoverable(activity)
        }
        updateMode()
        refreshStatus()
    }

    private var discoverableAskedAt = -DISCOVERABLE_S * 1000L * 2
    private val pairingOff = Runnable {
        _state.update { it.copy(pairingMode = false, pairing = null) }
        updateMode()
        refreshStatus()
    }

    /**
     * Classic discoverability, so the iPhone can pair the Echo as a speaker. Goes through the
     * system's "make visible" prompt (Settings' RequestPermissionActivity): it also records the
     * discoverable window, which makes Settings show the pairing confirmation as a dialog instead
     * of a notification nobody can see in our kiosk.
     */
    fun requestDiscoverable(activity: Activity) {
        try {
            activity.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_S))
        } catch (e: Exception) { Log.w(TAG, "request discoverable", e) }
    }

    private fun updateMode() {
        val mode = when {
            adapter?.isEnabled != true -> LePeripheral.Mode.OFF
            !notificationsEnabled -> LePeripheral.Mode.OFF
            _state.value.pairingMode -> LePeripheral.Mode.PAIRING
            client?.state == AncsGattClient.State.READY -> LePeripheral.Mode.OFF
            savedAddress().isNotEmpty() -> LePeripheral.Mode.RECONNECT
            else -> LePeripheral.Mode.OFF
        }
        peripheral.setMode(mode)
    }

    // --- ANCS ---

    private fun centralConnected(device: BluetoothDevice) {
        if (!notificationsEnabled) return
        val c = client
        if (c != null && c.state != AncsGattClient.State.CLOSED) return
        if (device.address in notIPhones && device.address != savedAddress()) { peripheral.cancelConnection(device); return }
        connectAncs(device)
    }

    private fun connectAncs(device: BluetoothDevice) {
        Log.i(TAG, "probing ${device.address} for ANCS")
        val c = AncsGattClient(context, device, handler, clientListener)
        client = c
        c.connect()
        refreshStatus()
    }

    private val clientListener = object : AncsGattClient.Listener {
        override fun onAncsReady(client: AncsGattClient) {
            val d = client.device
            settings.putString(KEY_ADDRESS, d.address)
            settings.putString(KEY_NAME, nameOf(d))
            val p = AncsPresenter(services.notifications, services.island, d.address, { nameOf(d) }, ::performAction,
                showSilent = settings.getBoolean(KEY_SHOW_SILENT, false), clearOnDismiss = settings.getBoolean(KEY_CLEAR_ON_DISMISS, true))
            presenter = p
            session = AncsSession(client, p::onNotification)
            updateMode()
            updateBluetoothActivity(announce = true)
            publishState()
        }

        override fun onNoAncs(client: AncsGattClient) {
            notIPhones += client.device.address
            client.close()
            if (this@PhoneController.client === client) this@PhoneController.client = null
            peripheral.cancelConnection(client.device)
            updateMode(); refreshStatus()
        }

        override fun onDisconnected(client: AncsGattClient, status: Int) {
            if (this@PhoneController.client !== client) return
            dropAncs()
            updateMode()
            publishState()
        }

        override fun onNotificationSource(client: AncsGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) session?.onNotificationSource(bytes) }
        override fun onDataSource(client: AncsGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) session?.onDataSource(bytes) }
        override fun onControlPointResult(client: AncsGattClient, status: Int) { if (client === this@PhoneController.client) session?.onWriteResult(status) }
        override fun onResponseTimeout(client: AncsGattClient) { if (client === this@PhoneController.client) session?.onTimeout() }

        override fun onNeedsBond(client: AncsGattClient) {
            val d = client.device
            Log.i(TAG, "bonding with ${d.address}")
            if (d.bondState == BluetoothDevice.BOND_NONE) d.createBond()
            refreshStatus()
        }
    }

    private fun performAction(uid: Long, actionId: Int) { session?.performAction(uid, actionId) }

    private fun dropAncs() {
        client?.close()
        client = null
        session?.reset()
        session = null
        presenter?.clearAll()
        presenter = null
        updateBluetoothActivity()
    }

    // --- audio ---

    private fun findConnectedAudio() {
        val a = adapter ?: return
        if (a2dpSink != null) { connectedSinkDevice()?.let { audioConnected(it, true) }; return }
        a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                handler.post {
                    a2dpSink = proxy
                    if (services.config.debug) logHiddenApis()
                    // Already streaming when the app started: pick it up.
                    connectedSinkDevice()?.let { audioConnected(it, true) }
                }
            }
            override fun onServiceDisconnected(profile: Int) { handler.post { a2dpSink = null } }
        }, A2DP_SINK)
    }

    private fun connectedSinkDevice(): BluetoothDevice? = runCatching { a2dpSink?.connectedDevices?.firstOrNull() }.getOrNull()

    /**
     * Drops the A2DP link (the phone stays paired; it can pick the Echo again from its audio
     * picker). BluetoothA2dpSink.disconnect is a hidden API on Android 11: reflection, false if
     * the platform refuses (then the caller just pauses).
     */
    private fun disconnectAudio(): Boolean {
        val p = a2dpSink ?: return false
        val d = audioDevice ?: connectedSinkDevice() ?: return false
        return try {
            val ok = p.javaClass.getMethod("disconnect", BluetoothDevice::class.java).invoke(p, d) as? Boolean ?: false
            Log.i(TAG, "A2DP sink disconnect ${d.address}: $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "A2DP sink disconnect not allowed: ${e.cause ?: e}")
            false
        }
    }

    /** Debug: which hidden Bluetooth APIs this platform lets us call (logged once). */
    private fun logHiddenApis() {
        val sink = a2dpSink?.javaClass
        fun has(c: Class<*>?, name: String, vararg args: Class<*>) = c != null && runCatching { c.getMethod(name, *args) }.isSuccess
        Log.i(TAG, "hidden APIs: a2dpSink.disconnect=${has(sink, "disconnect", BluetoothDevice::class.java)} " +
            "device.removeBond=${has(BluetoothDevice::class.java, "removeBond")} " +
            "adapter.setScanMode=${has(BluetoothAdapter::class.java, "setScanMode", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)}")
    }

    private fun audioConnected(d: BluetoothDevice, connected: Boolean) {
        if (connected) audioDevice = d else if (audioDevice?.address == d.address) audioDevice = null
        media.refresh()
        updateBluetoothActivity(announce = connected)
        // The phone is in range: if notifications aren't connected yet, try reaching ANCS from our side.
        if (connected && notificationsEnabled && client == null && d.address == savedAddress() && d.bondState == BluetoothDevice.BOND_BONDED) connectAncs(d)
        publishState()
    }

    // --- forget ---

    fun forget(address: String) {
        if (client?.device?.address == address) dropAncs()
        adapter?.bondedDevices?.firstOrNull { it.address == address }?.let { d ->
            val ok = runCatching { d.javaClass.getMethod("removeBond").invoke(d) as Boolean }.getOrElse { Log.w(TAG, "removeBond", it); false }
            Log.i(TAG, "forget ${d.address}: removeBond=$ok")
        }
        if (savedAddress() == address) { settings.remove(KEY_ADDRESS); settings.remove(KEY_NAME) }
        notIPhones -= address
        updateMode()
        publishState()
    }

    // --- island ---

    private var activityShown: Pair<Boolean, Boolean>? = null

    private fun updateBluetoothActivity(announce: Boolean = false) {
        val ancs = client?.state == AncsGattClient.State.READY
        val audio = audioDevice != null
        if (!ancs && !audio) {
            if (activityShown != null) services.island.end(ACTIVITY_ID)
            activityShown = null
            return
        }
        val key = audio to ancs
        if (key == activityShown && !announce) return
        val name = (audioDevice ?: client?.device)?.let(::nameOf) ?: savedName()
        val prev = activityShown
        activityShown = key
        // Visible for a few seconds when something new connects, then only in the list (Control Center's Bluetooth cell).
        val fresh = prev == null || (audio && !prev.first) || (ancs && !prev.second)
        val stale = if (fresh) SystemClock.elapsedRealtime() + ANNOUNCE_MS else SystemClock.elapsedRealtime()
        services.island.publish(LiveActivity(ACTIVITY_ID, ActivityKind.BLUETOOTH_CONNECTED,
            LiveContent.BluetoothConnected(name, audio = audio, notifications = ancs), staleAfterElapsed = stale))
    }

    // --- state ---

    private fun nameOf(d: BluetoothDevice): String =
        runCatching { d.alias }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.name?.takeIf { it.isNotBlank() } ?: "iPhone"

    private fun publishState() {
        val a = adapter
        val on = a?.isEnabled == true
        val saved = savedAddress()
        val bonded = if (on) runCatching { a!!.bondedDevices.toList() }.getOrDefault(emptyList()) else emptyList()
        val ancsAddr = client?.takeIf { it.state == AncsGattClient.State.READY }?.device?.address
        val audioAddr = audioDevice?.address
        val phones = bonded.filter { it.address == saved || it.address == audioAddr || it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            .map { PhoneInfo(it.address, nameOf(it), true, it.address == ancsAddr, it.address == audioAddr) }
            .toMutableList()
        if (saved.isNotEmpty() && phones.none { it.address == saved }) phones += PhoneInfo(saved, savedName(), false, ancsAddr == saved, audioAddr == saved)
        _state.update {
            it.copy(
                bluetoothOn = on,
                echoName = runCatching { a?.name }.getOrNull() ?: it.echoName,
                discoverable = a?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                phones = phones,
                notificationsEnabled = notificationsEnabled,
                audioEnabled = audioEnabled,
            )
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val s = _state.value
        val c = client
        val status = when {
            !s.bluetoothOn -> "Bluetooth is off"
            s.pairing != null -> "Confirm the code on your iPhone"
            c != null && c.state == AncsGattClient.State.READY -> "Connected to ${nameOf(c.device)}"
            c != null -> "Connecting to ${nameOf(c.device)}…"
            s.pairingMode && s.advertiseOk == false -> "Bluetooth LE advertising isn't available"
            s.pairingMode -> "Ready to pair"
            s.advertising -> "Waiting for ${savedName()}"
            else -> "Not connected"
        }
        if (status != s.status) _state.update { it.copy(status = status) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> onBluetoothOn()
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> if (d != null) {
                    val variant = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                    val key = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, Int.MIN_VALUE)
                    val passkey = if (key == Int.MIN_VALUE) null else if (variant == VARIANT_DISPLAY_PIN) key.toString() else "%06d".format(key)
                    Log.i(TAG, "pairing request from ${d.address} variant=$variant")
                    _state.update { it.copy(pairing = PairingPrompt(nameOf(d), d.address, passkey, variant)) }
                    // Show the code on our screen too (the system dialog has the buttons).
                    if (services.navigator.current.value != PhoneFeature.SCREEN_ID) services.navigator.show(PhoneFeature.SCREEN_ID)
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (d != null) {
                    val bs = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    Log.i(TAG, "bond ${d.address} -> $bs")
                    if (bs != BluetoothDevice.BOND_BONDING) _state.update { if (it.pairing?.address == d.address) it.copy(pairing = null) else it }
                    if (bs == BluetoothDevice.BOND_BONDED && client?.device?.address == d.address) client?.onBonded()
                    if (bs == BluetoothDevice.BOND_NONE && savedAddress() == d.address) { /* forgotten from Android Settings */ settings.remove(KEY_ADDRESS); updateMode() }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (d != null) {
                    if (d.address == savedAddress() && client == null && notificationsEnabled && d.bondState == BluetoothDevice.BOND_BONDED &&
                        i.getIntExtra(EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_AUTO) == BluetoothDevice.TRANSPORT_LE) connectAncs(d)
                }
                ACTION_A2DP_SINK_STATE -> if (d != null) {
                    when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> audioConnected(d, true)
                        BluetoothProfile.STATE_DISCONNECTED -> audioConnected(d, false)
                    }
                }
            }
            publishState()
        }
    }

    companion object {
        const val TAG = "EchoPhone"
        const val ACTIVITY_ID = "phone:bluetooth"
        const val ANNOUNCE_MS = 6_000L
        const val DISCOVERABLE_S = 300
        const val KEY_NOTIFICATIONS = "phone.notifications"
        const val KEY_AUDIO = "phone.audio"
        const val KEY_ADDRESS = "phone.device.address"
        const val KEY_NAME = "phone.device.name"
        const val KEY_SHOW_SILENT = "phone.showSilent"
        const val KEY_CLEAR_ON_DISMISS = "phone.clearOnDismiss"

        /** Hidden in API 30: BluetoothProfile.A2DP_SINK and its connection-state broadcast. */
        const val A2DP_SINK = 11
        const val ACTION_A2DP_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        /** Hidden BluetoothDevice.EXTRA_TRANSPORT on ACTION_ACL_CONNECTED. */
        const val EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT"
        const val VARIANT_DISPLAY_PIN = 5
    }
}
