package app.vesper.feature.phone

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
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import app.vesper.core.model.ActivityKind
import app.vesper.core.model.DeviceName
import app.vesper.core.model.VesperServices
import app.vesper.core.model.LiveActivity
import app.vesper.core.model.LiveContent
import app.vesper.core.model.PhoneStatus
import app.vesper.feature.phone.battery.BatteryPolicy
import app.vesper.feature.phone.ams.AmsMediaSource
import app.vesper.feature.phone.ams.ArtworkLookup
import app.vesper.feature.phone.ancs.AncsPresenter
import app.vesper.feature.phone.ancs.AncsSession
import app.vesper.feature.phone.audio.BluetoothMediaSource
import app.vesper.feature.phone.ble.AppleGattClient
import app.vesper.feature.phone.ble.LePeripheral
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything iPhone-over-Bluetooth, on the main thread, driven only by Bluetooth callbacks and
 * broadcasts (no polling, no timers while idle):
 *
 *  - Notifications and the iPhone's Now Playing: LE advertising ([LePeripheral]) → the iPhone connects (or the
 *    Echo connects out after a Classic speaker pairing) → [AppleGattClient] (ANCS + AMS)
 *    subscribes to ANCS → [AncsSession] → [AncsPresenter] → banners + call island. When the link
 *    drops it advertises again (low power), so the bonded iPhone reconnects by itself after
 *    reboots and when it comes back in range.
 *  - Audio: [BluetoothMediaSource] (the Bluetooth stack's AVRCP session) → MediaHub "bluetooth".
 *  - The "phone connected" island (BLUETOOTH_CONNECTED), shown for a few seconds when the phone
 *    connects; it stays in the activity list while connected (Control Center reads its name).
 */
class PhoneController(private val context: Context, private val services: VesperServices, private val handler: Handler) {

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

    private var client: AppleGattClient? = null

    // --- iPhone battery (Battery Service over the ANCS/AMS GATT link) and services.phone ---
    private val batteryPolicy = BatteryPolicy()
    private var batteryPercent: Int? = null
    private var batteryShownUntil = 0L
    private val _phoneStatus = MutableStateFlow<PhoneStatus?>(null)
    private var session: AncsSession? = null
    private var presenter: AncsPresenter? = null
    /** Centrals that turned out not to have ANCS this session (don't probe them again). */
    private val notIPhones = HashSet<String>()
    private var audioDevice: BluetoothDevice? = null
    private var started = false

    private val artwork = ArtworkLookup(services.scope, { settings.getBoolean(KEY_ART_LOOKUP, true) })

    val media: BluetoothMediaSource = BluetoothMediaSource(context, handler, { services.media }, { audioDevice?.let(::nameOf) ?: savedName() },
        disconnectAudio = ::disconnectAudio, remote = { ams.merged }, artwork = artwork)

    /** The iPhone's Now Playing over AMS (whatever plays, wherever its audio goes). */
    val ams: AmsMediaSource = AmsMediaSource({ services.media }, { client?.device?.let(::nameOf) ?: savedName() }, ::a2dpSamePhone, artwork,
        onMergedChanged = { media.refresh() })

    /** The phone streaming to the Echo is the one AMS talks to (one Now Playing entry, not two). */
    private fun a2dpSamePhone(): Boolean {
        val a = audioDevice ?: return false
        val c = client?.device ?: return false
        return a.address == c.address || nameOf(a) == nameOf(c)
    }

    /** The A2DP sink profile proxy (a binder handle, kept while Bluetooth is on). */
    private var a2dpSink: BluetoothProfile? = null

    // --- settings ---

    var notificationsEnabled: Boolean
        get() = settings.getBoolean(KEY_NOTIFICATIONS, true)
        set(v) { settings.putBoolean(KEY_NOTIFICATIONS, v); applySetting(KEY_NOTIFICATIONS) }

    var nowPlayingEnabled: Boolean
        get() = settings.getBoolean(KEY_NOW_PLAYING, true)
        set(v) { settings.putBoolean(KEY_NOW_PLAYING, v); applySetting(KEY_NOW_PLAYING) }

    /** The LE link is wanted for notifications (ANCS) or Now Playing (AMS). */
    private val leEnabled: Boolean get() = notificationsEnabled || nowPlayingEnabled

    var audioEnabled: Boolean
        get() = settings.getBoolean(KEY_AUDIO, true)
        set(v) { settings.putBoolean(KEY_AUDIO, v); applySetting(KEY_AUDIO) }

    /** Settings apply live (from the pairing screen, or the web settings page via SettingsStore.changes). */
    private fun applySetting(key: String) {
        when (key) {
            KEY_NOTIFICATIONS, KEY_NOW_PLAYING -> reconnectLe()
            KEY_ART_LOOKUP -> { ams.refresh(); media.refresh() }
            KEY_AUDIO -> { media.enabled = audioEnabled; applyAudioPolicy() }
            KEY_SHOW_SILENT -> presenter?.showSilent = settings.getBoolean(KEY_SHOW_SILENT, false)
            KEY_CLEAR_ON_DISMISS -> presenter?.clearOnDismiss = settings.getBoolean(KEY_CLEAR_ON_DISMISS, true)
            KEY_HIDDEN_APPS -> presenter?.hiddenApps = hiddenApps()
            KEY_ANSWER_CALLS -> presenter?.offerAnswer = settings.getBoolean(KEY_ANSWER_CALLS, false)
            DeviceName.KEY -> applyName()
            else -> return
        }
        publishState()
    }

    /** ANCS/AMS switched on or off: subscribe again with the new set (the bond stays). */
    private fun reconnectLe() {
        ams.enabled = nowPlayingEnabled
        val d = client?.device
        dropAncs()
        if (leEnabled && d != null && d.bondState == BluetoothDevice.BOND_BONDED) connectAncs(d)
        updateMode()
    }

    private fun hiddenApps(): Set<String> = AncsPresenter.parseAppList(settings.getString(KEY_HIDDEN_APPS, ""))

    /** The Bluetooth name is the Echo's name everywhere ([DeviceName], setting "device.name"). */
    private fun applyName() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        val want = DeviceName.get(settings)
        val have = runCatching { a.name }.getOrNull()
        if (have != want) Log.i(TAG, "Bluetooth name '$have' -> '$want': ${runCatching { a.setName(want) }.getOrDefault(false)}")
    }

    /** With BLUETOOTH_PRIVILEGED (priv-app install), the Echo can confirm pairing itself and refuse audio when it's off. */
    val privileged: Boolean get() = context.checkSelfPermission(PERMISSION_BLUETOOTH_PRIVILEGED) == PackageManager.PERMISSION_GRANTED

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
        ams.enabled = nowPlayingEnabled
        services.phone = _phoneStatus.asStateFlow()
        services.scope.launch { settings.changes.collect { applySetting(it) } }
        // Precedence between the iPhone's remote Now Playing and audio playing on the Echo.
        services.scope.launch { services.media.sessions.collect { ams.onSessions(it) } }
        services.scope.launch { services.notifications.userDismissals.collect { presenter?.onUserDismissed(it) } }
        // The assistant's island activity spans its turn (listening, working, speaking), the same
        // span as its AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK. The framework doesn't duck A2DP sink audio.
        services.scope.launch {
            services.island.activities.map { l -> l.any { it.kind == ActivityKind.ASSISTANT } }
                .distinctUntilChanged().collect { media.onAssistant(it) }
        }
        val g = peripheral.probeGate()
        Log.i(TAG, "LE gate: $g")
        _state.update { it.copy(advertiseOk = g.started) }
        if (adapter?.isEnabled == true) onBluetoothOn()
        publishState()
    }

    private fun onBluetoothOn() {
        applyName()
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
            !leEnabled -> LePeripheral.Mode.OFF
            _state.value.pairingMode -> LePeripheral.Mode.PAIRING
            client?.state == AppleGattClient.State.READY -> LePeripheral.Mode.OFF
            savedAddress().isNotEmpty() -> LePeripheral.Mode.RECONNECT
            else -> LePeripheral.Mode.OFF
        }
        peripheral.setMode(mode)
    }

    // --- ANCS ---

    private fun centralConnected(device: BluetoothDevice) {
        if (!leEnabled) return
        val c = client
        if (c != null && c.state != AppleGattClient.State.CLOSED) return
        if (device.address in notIPhones && device.address != savedAddress()) { peripheral.cancelConnection(device); return }
        connectAncs(device)
    }

    private fun connectAncs(device: BluetoothDevice) {
        Log.i(TAG, "probing ${device.address} for ANCS/AMS")
        val c = AppleGattClient(context, device, handler, clientListener, wantAncs = notificationsEnabled, wantAms = nowPlayingEnabled)
        client = c
        // AMS sends the current values right after its subscription, before the client is ready.
        if (nowPlayingEnabled) ams.attach(c, SystemClock::elapsedRealtime)
        c.connect()
        refreshStatus()
    }

    private val clientListener = object : AppleGattClient.Listener {
        override fun onReady(client: AppleGattClient) {
            val d = client.device
            settings.putString(KEY_ADDRESS, d.address)
            settings.putString(KEY_NAME, nameOf(d))
            if (!client.hasAms) ams.detach()
            if (!client.hasAncs) { updateMode(); updateBluetoothActivity(announce = true); publishState(); return }
            val p = AncsPresenter(services.notifications, services.island, d.address, { nameOf(d) }, ::performAction,
                showSilent = settings.getBoolean(KEY_SHOW_SILENT, false), clearOnDismiss = settings.getBoolean(KEY_CLEAR_ON_DISMISS, true))
            p.hiddenApps = hiddenApps()
            p.offerAnswer = settings.getBoolean(KEY_ANSWER_CALLS, false)
            presenter = p
            session = AncsSession(client, p::onNotification)
            updateMode()
            updateBluetoothActivity(announce = true)
            publishState()
        }

        override fun onNoAppleServices(client: AppleGattClient) {
            if (this@PhoneController.client === client) ams.detach()
            notIPhones += client.device.address
            client.close()
            if (this@PhoneController.client === client) this@PhoneController.client = null
            peripheral.cancelConnection(client.device)
            updateMode(); refreshStatus()
        }

        override fun onDisconnected(client: AppleGattClient, status: Int) {
            if (this@PhoneController.client !== client) return
            dropAncs()
            updateMode()
            publishState()
        }

        override fun onNotificationSource(client: AppleGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) session?.onNotificationSource(bytes) }
        override fun onDataSource(client: AppleGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) session?.onDataSource(bytes) }
        override fun onControlPointResult(client: AppleGattClient, status: Int) { if (client === this@PhoneController.client) session?.onWriteResult(status) }
        override fun onResponseTimeout(client: AppleGattClient) { if (client === this@PhoneController.client) session?.onTimeout() }
        override fun onAmsEntityUpdate(client: AppleGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) ams.session?.onEntityUpdate(bytes) }
        override fun onAmsCommands(client: AppleGattClient, bytes: ByteArray) { if (client === this@PhoneController.client) ams.session?.onCommands(bytes) }
        override fun onAmsAttribute(client: AppleGattClient, value: ByteArray?) { if (client === this@PhoneController.client) ams.session?.onAttributeRead(value) }

        override fun onBatteryLevel(client: AppleGattClient, percent: Int) {
            if (client === this@PhoneController.client) onBattery(percent)
        }

        override fun onNeedsBond(client: AppleGattClient) {
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
        ams.session?.reset()
        ams.detach()
        session?.reset()
        session = null
        presenter?.clearAll()
        presenter = null
        batteryPercent = null
        batteryPolicy.onConnectionChanged()
        endBatteryActivity()
        updateBluetoothActivity()
    }

    private var debugName: String? = null

    /** Debug demo: feeds fake battery levels through the real policy and island path (no phone needed). */
    fun debugBattery(name: String, levels: List<Int>, stepMs: Long = 4_000L) {
        debugName = name
        batteryPercent = null
        batteryPolicy.onConnectionChanged()
        levels.forEachIndexed { i, l -> handler.postDelayed({ onBattery(l) }, stepMs * i) }
        handler.postDelayed({ debugName = null }, stepMs * levels.size + 1000)
    }

    private fun onBattery(percent: Int) {
        if (percent == batteryPercent) return
        batteryPercent = percent
        // The Battery Service has no charging flag: null (unknown).
        val charging: Boolean? = null
        val d = batteryPolicy.onLevel(percent, charging)
        val name = debugName ?: client?.device?.let(::nameOf) ?: savedName()
        val now = SystemClock.elapsedRealtime()
        when {
            d.end -> endBatteryActivity()
            d.show != null -> {
                batteryShownUntil = if (d.durationMs != null) now + d.durationMs else Long.MAX_VALUE
                services.island.publish(LiveActivity(BATTERY_ACTIVITY_ID, ActivityKind.PHONE_BATTERY,
                    LiveContent.PhoneBattery(name, percent, charging), staleAfterElapsed = d.durationMs?.let { now + it }))
            }
            // Already on the island: keep its end time, update the number.
            now < batteryShownUntil -> services.island.publish(LiveActivity(BATTERY_ACTIVITY_ID, ActivityKind.PHONE_BATTERY,
                LiveContent.PhoneBattery(name, percent, charging), staleAfterElapsed = batteryShownUntil.takeIf { it != Long.MAX_VALUE }))
        }
        publishPhoneStatus()
    }

    private fun endBatteryActivity() {
        if (batteryShownUntil != 0L) services.island.end(BATTERY_ACTIVITY_ID)
        batteryShownUntil = 0L
    }

    /** services.phone: the paired / connected iPhone, null before any phone was ever paired. */
    private fun publishPhoneStatus() {
        val c = client
        val connected = (c?.state == AppleGattClient.State.READY) || audioDevice != null
        val hasPhone = savedAddress().isNotEmpty() || c != null || audioDevice != null
        _phoneStatus.value = if (!hasPhone) null else PhoneStatus(
            deviceName = (audioDevice ?: c?.device)?.let(::nameOf) ?: savedName(),
            connected = connected,
            batteryPercent = if (connected) batteryPercent else null,
            charging = null,
        )
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
        if (connected) audioDevice = d else if (audioDevice?.address == d.address) { audioDevice = null; outboundTried -= d.address }
        media.refresh()
        ams.refresh()
        updateBluetoothActivity(announce = connected)
        // The phone is in range: if ANCS/AMS aren't connected yet, try reaching them from our side. That's also
        // how a phone paired only as a speaker (iPhone Settings › Bluetooth) gets notifications, when
        // cross-transport key derivation gave the bond LE keys; tried once per connection.
        if (connected && leEnabled && client == null && d.bondState == BluetoothDevice.BOND_BONDED && outboundOk(d)) {
            outboundTried += d.address
            connectAncs(d)
        }
        publishState()
    }

    /** Phones we already tried to reach over LE from our side during this audio connection. */
    private val outboundTried = HashSet<String>()

    private fun outboundOk(d: BluetoothDevice): Boolean {
        if (d.address in outboundTried) return false
        if (d.address == savedAddress()) return true
        if (savedAddress().isNotEmpty() || d.address in notIPhones) return false
        // A first pairing from iPhone Settings › Bluetooth: only phones (an iPhone is a "smartphone").
        return d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
    }

    // --- pairing confirmation (privileged) ---

    private var pairingDevice: BluetoothDevice? = null

    /** Pair / Cancel on our own screen; needs BLUETOOTH_PRIVILEGED, otherwise the system dialog does it. */
    fun confirmPairing(accept: Boolean) {
        val d = pairingDevice ?: return
        val ok = runCatching { d.setPairingConfirmation(accept) }.onFailure { Log.w(TAG, "setPairingConfirmation", it) }.getOrDefault(false)
        Log.i(TAG, "pairing ${if (accept) "confirmed" else "rejected"} for ${d.address}: $ok")
        if (!accept) _state.update { it.copy(pairing = null) }
    }

    /**
     * Audio off: with BLUETOOTH_PRIVILEGED, forbid the A2DP sink connection for the paired phone
     * (it can't stream to the Echo at all); without it, the stream still plays but isn't shown.
     */
    private fun applyAudioPolicy() {
        val p = a2dpSink ?: return
        if (!privileged) return
        val policy = if (audioEnabled) 100 else 0 // CONNECTION_POLICY_ALLOWED / FORBIDDEN
        val targets = listOfNotNull(audioDevice) + (adapter?.bondedDevices?.filter { it.address == savedAddress() } ?: emptyList())
        for (d in targets.distinctBy { it.address }) {
            val ok = runCatching { p.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(p, d, policy) }
            Log.i(TAG, "A2DP sink policy $policy for ${d.address}: ${ok.getOrNull() ?: ok.exceptionOrNull()}")
        }
        if (!audioEnabled) disconnectAudio()
    }

    // --- forget ---

    /** The settings page's "Forget iPhone": the paired phone (ANCS) and any phone streaming audio. */
    fun forgetSaved(): String {
        val targets = listOfNotNull(savedAddress().ifEmpty { null }, audioDevice?.address).distinct()
        if (targets.isEmpty()) return "No iPhone is paired"
        targets.forEach(::forget)
        return "Forgot ${targets.size} device${if (targets.size > 1) "s" else ""}"
    }

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
        val ancs = client?.let { it.state == AppleGattClient.State.READY && it.hasAncs } == true
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
        val ancsAddr = client?.takeIf { it.state == AppleGattClient.State.READY && it.hasAncs }?.device?.address
        val audioAddr = audioDevice?.address
        val phones = bonded.filter { it.address == saved || it.address == audioAddr || it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }
            .map { PhoneInfo(it.address, nameOf(it), true, it.address == ancsAddr, it.address == audioAddr) }
            .toMutableList()
        if (saved.isNotEmpty() && phones.none { it.address == saved }) phones += PhoneInfo(saved, savedName(), false, ancsAddr == saved, audioAddr == saved)
        _state.update {
            it.copy(
                bluetoothOn = on,
                echoName = DeviceName.get(settings),
                discoverable = a?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                phones = phones,
                notificationsEnabled = notificationsEnabled,
                audioEnabled = audioEnabled,
            )
        }
        refreshStatus()
        publishPhoneStatus()
    }

    private fun refreshStatus() {
        val s = _state.value
        val c = client
        val status = when {
            !s.bluetoothOn -> "Bluetooth is off"
            s.pairing != null -> "Confirm the code on your iPhone"
            c != null && c.state == AppleGattClient.State.READY -> "Connected to ${nameOf(c.device)}"
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
                    pairingDevice = d
                    _state.update { it.copy(pairing = PairingPrompt(nameOf(d), d.address, passkey, variant, canConfirm = privileged && variant in CONFIRM_VARIANTS)) }
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
                    if (d.address == savedAddress() && client == null && leEnabled && d.bondState == BluetoothDevice.BOND_BONDED &&
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
        const val TAG = "VesperPhone"
        const val ACTIVITY_ID = "phone:bluetooth"
        const val BATTERY_ACTIVITY_ID = "phone:battery"
        const val ANNOUNCE_MS = 6_000L
        const val DISCOVERABLE_S = 300
        const val KEY_NOTIFICATIONS = "phone.notifications"
        const val KEY_AUDIO = "phone.audio"
        const val KEY_ADDRESS = "phone.device.address"
        const val KEY_NAME = "phone.device.name"
        const val KEY_SHOW_SILENT = "phone.showSilent"
        const val KEY_CLEAR_ON_DISMISS = "phone.clearOnDismiss"
        const val KEY_HIDDEN_APPS = "phone.hiddenApps"
        const val KEY_ANSWER_CALLS = "phone.answerCalls"
        const val KEY_NOW_PLAYING = "phone.nowPlaying"
        const val KEY_ART_LOOKUP = "phone.artLookup"
        const val PERMISSION_BLUETOOTH_PRIVILEGED = "android.permission.BLUETOOTH_PRIVILEGED"

        /** Hidden in API 30: BluetoothProfile.A2DP_SINK and its connection-state broadcast. */
        const val A2DP_SINK = 11
        const val ACTION_A2DP_SINK_STATE = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
        /** Hidden BluetoothDevice.EXTRA_TRANSPORT on ACTION_ACL_CONNECTED. */
        const val EXTRA_TRANSPORT = "android.bluetooth.device.extra.TRANSPORT"
        const val VARIANT_DISPLAY_PIN = 5
        /** PAIRING_VARIANT_PASSKEY_CONFIRMATION, PAIRING_VARIANT_CONSENT: answered with setPairingConfirmation. */
        val CONFIRM_VARIANTS = setOf(2, 3)
    }
}
