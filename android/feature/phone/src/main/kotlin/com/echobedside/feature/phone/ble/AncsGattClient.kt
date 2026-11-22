package com.echobedside.feature.phone.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.util.Log
import com.echobedside.feature.phone.ancs.Ancs
import com.echobedside.feature.phone.ancs.AncsSession

/**
 * GATT client to the iPhone's ANCS, over the LE link the iPhone opened to the Echo (Android
 * reuses the existing connection for `connectGatt`, so the Echo is peripheral and client at once).
 *
 * Steps: connect → discover → MTU 185 → subscribe Data Source, then Notification Source (that
 * order, per Apple, so no response is missed) → ready. ANCS characteristics need an encrypted
 * link: a CCCD write rejected for authentication starts bonding, and the write is retried when
 * the bond completes ([onBonded]). Every GATT callback hops to [handler] (the main thread); GATT
 * operations run one at a time.
 */
class AncsGattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: Listener,
) : AncsSession.Io {

    interface Listener {
        /** Subscribed: notifications will flow. */
        fun onAncsReady(client: AncsGattClient)
        /** Connected, but the phone has no ANCS (not an iPhone, or iOS hid it). */
        fun onNoAncs(client: AncsGattClient)
        fun onDisconnected(client: AncsGattClient, status: Int)
        fun onNotificationSource(client: AncsGattClient, bytes: ByteArray)
        fun onDataSource(client: AncsGattClient, bytes: ByteArray)
        fun onControlPointResult(client: AncsGattClient, status: Int)
        fun onResponseTimeout(client: AncsGattClient)
        /** Pairing is needed (the phone refused an unencrypted subscription). */
        fun onNeedsBond(client: AncsGattClient)
    }

    enum class State { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, READY, CLOSED }

    var state = State.IDLE
        private set
    var mtu = 23
        private set

    private var gatt: BluetoothGatt? = null
    private var ns: BluetoothGattCharacteristic? = null
    private var cp: BluetoothGattCharacteristic? = null
    private var ds: BluetoothGattCharacteristic? = null
    /** CCCDs still to enable, in order. */
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()
    private var waitingForBond = false
    private var authRetries = 0
    private val timeout = Runnable { listener.onResponseTimeout(this) }

    fun connect() {
        if (state != State.IDLE) return
        state = State.CONNECTING
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { Log.w(TAG, "connectGatt", e); null }
        if (gatt == null) { state = State.CLOSED; listener.onDisconnected(this, -1) }
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        handler.removeCallbacks(timeout)
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }

    /** The bond with [device] completed: retry the subscription that needed it. */
    fun onBonded() {
        if (!waitingForBond) return
        waitingForBond = false
        subscribeNext()
    }

    // --- AncsSession.Io ---

    override fun writeControlPoint(bytes: ByteArray) {
        val g = gatt; val c = cp
        if (g == null || c == null || state != State.READY) { handler.post { listener.onControlPointResult(this, BluetoothGatt.GATT_FAILURE) }; return }
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = bytes
        if (!g.writeCharacteristic(c)) handler.post { listener.onControlPointResult(this, BluetoothGatt.GATT_FAILURE) }
    }

    override fun armTimeout(ms: Long) { handler.removeCallbacks(timeout); handler.postDelayed(timeout, ms) }
    override fun disarmTimeout() { handler.removeCallbacks(timeout) }

    // --- GATT flow ---

    private fun onServices(g: BluetoothGatt) {
        val svc = g.getService(Ancs.SERVICE)
        ns = svc?.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
        cp = svc?.getCharacteristic(Ancs.CONTROL_POINT)
        ds = svc?.getCharacteristic(Ancs.DATA_SOURCE)
        if (ns == null || cp == null || ds == null) {
            Log.i(TAG, "${device.address}: no ANCS (${g.services.size} services: ${g.services.joinToString { it.uuid.toString().take(8) }})")
            listener.onNoAncs(this)
            return
        }
        state = State.SUBSCRIBING
        // Bigger MTU = fewer Data Source fragments; iOS answers 185 at most.
        if (!g.requestMtu(185)) startSubscribing()
    }

    private fun startSubscribing() {
        toSubscribe.clear()
        toSubscribe.addLast(ds!!)
        toSubscribe.addLast(ns!!)
        subscribeNext()
    }

    private fun subscribeNext() {
        val g = gatt ?: return
        val c = toSubscribe.firstOrNull()
        if (c == null) {
            state = State.READY
            Log.i(TAG, "${device.address}: ANCS ready (mtu $mtu)")
            listener.onAncsReady(this)
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Ancs.CCCD)
        if (d == null) { toSubscribe.removeFirst(); subscribeNext(); return }
        d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!g.writeDescriptor(d)) Log.w(TAG, "writeDescriptor refused")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (state == State.CLOSED) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        state = State.DISCOVERING
                        if (!g.discoverServices()) { close(); listener.onDisconnected(this@AncsGattClient, status) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "${device.address}: GATT client disconnected, status $status")
                        close()
                        listener.onDisconnected(this@AncsGattClient, status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (state == State.CLOSED) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discovery failed $status"); listener.onNoAncs(this@AncsGattClient); return@post }
                onServices(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                if (state == State.SUBSCRIBING && toSubscribe.isEmpty()) startSubscribing()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (state != State.SUBSCRIBING) return@post
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> { toSubscribe.removeFirstOrNull(); subscribeNext() }
                    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> {
                        // Android usually pairs and retries by itself; if it didn't, ask for a bond and retry once bonded.
                        Log.i(TAG, "${device.address}: subscription needs encryption (status $status, bond ${device.bondState})")
                        if (++authRetries > 3) { Log.w(TAG, "giving up on encryption"); close(); listener.onDisconnected(this@AncsGattClient, status); return@post }
                        waitingForBond = true
                        if (device.bondState == BluetoothDevice.BOND_BONDED) handler.postDelayed({ onBonded() }, 500)
                        else listener.onNeedsBond(this@AncsGattClient)
                    }
                    else -> { Log.w(TAG, "CCCD write failed $status"); toSubscribe.removeFirstOrNull(); subscribeNext() }
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // The value buffer is reused by the stack: copy before hopping threads.
            val v = c.value?.copyOf() ?: return
            val uuid = c.uuid
            handler.post {
                if (state != State.READY) return@post
                when (uuid) {
                    Ancs.NOTIFICATION_SOURCE -> listener.onNotificationSource(this@AncsGattClient, v)
                    Ancs.DATA_SOURCE -> listener.onDataSource(this@AncsGattClient, v)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid != Ancs.CONTROL_POINT) return
            handler.post { listener.onControlPointResult(this@AncsGattClient, status) }
        }
    }

    companion object {
        const val TAG = "EchoPhone"
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
    }
}
