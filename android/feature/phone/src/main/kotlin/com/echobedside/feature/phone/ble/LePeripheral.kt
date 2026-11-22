package com.echobedside.feature.phone.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import com.echobedside.feature.phone.ancs.Ancs
import java.util.UUID

/**
 * The Echo as a Bluetooth LE peripheral, so an iPhone can find it and connect (ANCS needs the
 * iPhone to connect to the accessory; the Echo then acts as GATT client on that same link).
 *
 *  - Advertises connectable, our service UUID in the advertisement and the name in the scan
 *    response (the 31-byte legacy budget fits one 128-bit UUID plus flags). Advertising runs in
 *    the controller, so it costs no CPU on the Echo; LOW_POWER (~1 s interval) when waiting for a
 *    known phone to come back, BALANCED (~250 ms) while the pairing screen is open.
 *  - ANCS service solicitation (AD type 0x15, which makes the Echo show up in the iPhone's
 *    Settings › Bluetooth) needs `AdvertiseData.Builder.addServiceSolicitationUuid`, added in
 *    API 31. It's used through reflection when the ROM has it; on stock Android 11 it doesn't.
 *  - A tiny GATT server: one service with one characteristic that can only be read encrypted,
 *    so connecting with any BLE app on the iPhone (nRF Connect, LightBlue) and reading it starts
 *    pairing.
 */
class LePeripheral(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onCentralConnected(device: BluetoothDevice)
        fun onCentralDisconnected(device: BluetoothDevice)
        fun onAdvertisingChanged(on: Boolean, error: Int?)
    }

    enum class Mode { OFF, RECONNECT, PAIRING }

    /** The advertising gate, recorded once per process. */
    data class Gate(
        val multipleAdvertisementSupported: Boolean,
        val advertiserPresent: Boolean,
        val solicitationApi: Boolean,
        val leExtendedAdvertising: Boolean,
        val started: Boolean? = null,
        val errorCode: Int? = null,
    ) {
        val ok: Boolean get() = started == true
        override fun toString() = "multiAdv=$multipleAdvertisementSupported advertiser=$advertiserPresent " +
            "solicitationApi=$solicitationApi extAdv=$leExtendedAdvertising started=$started error=${errorCode?.let { errorName(it) }}"
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private var mode = Mode.OFF
    private var advertising = false
    private var startingMode: Mode? = null

    var gate: Gate? = null
        private set

    fun probeGate(): Gate {
        val a = adapter
        val g = Gate(
            multipleAdvertisementSupported = a?.isMultipleAdvertisementSupported == true,
            advertiserPresent = a?.bluetoothLeAdvertiser != null,
            solicitationApi = solicitationMethod != null,
            leExtendedAdvertising = a?.isLeExtendedAdvertisingSupported == true,
            started = gate?.started,
            errorCode = gate?.errorCode,
        )
        gate = g
        return g
    }

    fun setMode(m: Mode) {
        if (m == mode && (m == Mode.OFF || advertising || startingMode == m)) return
        mode = m
        stopAdvertising()
        if (m == Mode.OFF) { closeServer(); return }
        openServer()
        startAdvertising(m)
    }

    fun cancelConnection(device: BluetoothDevice) {
        try { server?.cancelConnection(device) } catch (e: SecurityException) { Log.w(TAG, "cancelConnection", e) }
    }

    /** Bluetooth turned off: everything is gone, start again when it's back. */
    fun onAdapterOff() {
        advertising = false; startingMode = null
        server = null
        val m = mode; mode = Mode.OFF
        pendingMode = m
    }

    private var pendingMode: Mode = Mode.OFF

    fun onAdapterOn() {
        val m = pendingMode.takeIf { it != Mode.OFF } ?: return
        pendingMode = Mode.OFF
        setMode(m)
    }

    // --- GATT server ---

    private fun openServer() {
        if (server != null) return
        val s = try { manager?.openGattServer(context, serverCallback) } catch (e: SecurityException) { Log.w(TAG, "openGattServer", e); null } ?: return
        val svc = BluetoothGattService(PAIRING_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val ch = BluetoothGattCharacteristic(PAIRING_CHAR, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
        svc.addCharacteristic(ch)
        s.addService(svc)
        server = s
    }

    private fun closeServer() {
        server?.close()
        server = null
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                Log.i(TAG, "central ${device.address} state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) listener.onCentralConnected(device)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) listener.onCentralDisconnected(device)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = PAIRING_VALUE
            val s = server ?: return
            if (offset > value.size) { s.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null); return }
            s.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    // --- advertising ---

    private fun startAdvertising(m: Mode) {
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) {
            gate = (gate ?: probeGate()).copy(started = false, errorCode = ERROR_NO_ADVERTISER)
            listener.onAdvertisingChanged(false, ERROR_NO_ADVERTISER)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (m == Mode.PAIRING) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        val sol = solicitationMethod
        if (sol != null) {
            // API 31+: solicit ANCS like an Apple Watch-style accessory (UUID budget: one 128-bit).
            runCatching { sol.invoke(data, ParcelUuid(Ancs.SERVICE)) }.onFailure { data.addServiceUuid(ParcelUuid(PAIRING_SERVICE)) }
        } else {
            data.addServiceUuid(ParcelUuid(PAIRING_SERVICE))
        }
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        startingMode = m
        try {
            adv.startAdvertising(settings, data.build(), scan, advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising", e)
            startingMode = null
            gate = (gate ?: probeGate()).copy(started = false, errorCode = ERROR_EXCEPTION)
            listener.onAdvertisingChanged(false, ERROR_EXCEPTION)
        }
    }

    private fun stopAdvertising() {
        if (!advertising && startingMode == null) return
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (e: Exception) { Log.w(TAG, "stopAdvertising", e) }
        advertising = false
        // A start still in flight finishes later; onStartSuccess sees startingMode == null and undoes it.
        startingMode = null
        listener.onAdvertisingChanged(false, null)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            handler.post {
                val wanted = startingMode
                startingMode = null
                advertising = true
                // Stopped (or switched mode) while the start was in flight: undo it.
                if (wanted == null || wanted != mode) {
                    val m = mode
                    stopAdvertising()
                    if (m != Mode.OFF) startAdvertising(m)
                    return@post
                }
                gate = (gate ?: probeGate()).copy(started = true, errorCode = null)
                Log.i(TAG, "advertising (${mode}) mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel} connectable=${settingsInEffect.isConnectable}")
                listener.onAdvertisingChanged(true, null)
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                startingMode = null
                // Already started (e.g. after a mode change race) counts as running.
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) { advertising = true; listener.onAdvertisingChanged(true, null); return@post }
                advertising = false
                gate = (gate ?: probeGate()).copy(started = false, errorCode = errorCode)
                Log.w(TAG, "advertising failed: ${errorName(errorCode)}")
                listener.onAdvertisingChanged(false, errorCode)
            }
        }
    }

    companion object {
        const val TAG = "EchoPhone"
        /** "Echo Bedside" pairing service: 128-bit, random, ours. */
        val PAIRING_SERVICE: UUID = UUID.fromString("E5B0A1C2-3D4E-4F60-8A9B-0C1D2E3F4A5B")
        val PAIRING_CHAR: UUID = UUID.fromString("E5B0A1C3-3D4E-4F60-8A9B-0C1D2E3F4A5B")
        private val PAIRING_VALUE = "Echo Bedside".toByteArray()

        const val ERROR_NO_ADVERTISER = -1
        const val ERROR_EXCEPTION = -2

        private val solicitationMethod by lazy {
            runCatching { AdvertiseData.Builder::class.java.getMethod("addServiceSolicitationUuid", ParcelUuid::class.java) }.getOrNull()
        }

        fun errorName(code: Int) = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            ERROR_NO_ADVERTISER -> "NO_ADVERTISER"
            ERROR_EXCEPTION -> "EXCEPTION"
            else -> "error $code"
        }
    }
}
