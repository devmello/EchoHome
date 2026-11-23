package app.vesper.feature.phone.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.util.Log
import app.vesper.feature.phone.ams.Ams
import app.vesper.feature.phone.ams.AmsSession
import app.vesper.feature.phone.ancs.Ancs
import app.vesper.feature.phone.ancs.AncsSession
import app.vesper.feature.phone.battery.Battery

/**
 * GATT client to the iPhone's ANCS (notifications) and AMS (Now Playing), on one LE link and one
 * bond. Usually the iPhone opened the link to the Echo (Android reuses it for `connectGatt`, so
 * the Echo is peripheral and client at once); after a Classic speaker pairing the Echo may also
 * connect out itself.
 *
 * Steps: connect → discover → MTU 185 → subscribe (ANCS Data Source, then Notification Source,
 * per Apple; AMS Remote Command, then Entity Update) → write the AMS Track and Player
 * subscriptions → ready. Either service may be missing or switched off ([wantAncs]/[wantAms]).
 * Both need an encrypted link: a CCCD write refused for authentication starts bonding, and the
 * write is retried when the bond completes ([onBonded]).
 *
 * Every GATT operation goes through one queue (GATT allows one in flight), with a watchdog so a
 * lost callback can't stall it. Callbacks hop to [handler] (the main thread).
 */
class AppleGattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: Listener,
    private val wantAncs: Boolean = true,
    private val wantAms: Boolean = true,
) : AncsSession.Io, AmsSession.Io {

    interface Listener {
        /** Subscribed: notifications will flow. [hasAncs]/[hasAms] say which. */
        fun onReady(client: AppleGattClient)
        /** Connected, but the phone has neither service (not an iPhone, or iOS hid them). */
        fun onNoAppleServices(client: AppleGattClient)
        fun onDisconnected(client: AppleGattClient, status: Int)
        fun onNotificationSource(client: AppleGattClient, bytes: ByteArray)
        fun onDataSource(client: AppleGattClient, bytes: ByteArray)
        fun onControlPointResult(client: AppleGattClient, status: Int)
        fun onResponseTimeout(client: AppleGattClient)
        fun onAmsEntityUpdate(client: AppleGattClient, bytes: ByteArray)
        fun onAmsCommands(client: AppleGattClient, bytes: ByteArray)
        /** The Entity Attribute read answered; null = it failed. */
        fun onAmsAttribute(client: AppleGattClient, value: ByteArray?)
        /** Pairing is needed (the phone refused an unencrypted subscription). */
        fun onNeedsBond(client: AppleGattClient)
        /** The phone's Battery Level (percent), read or notified. */
        fun onBatteryLevel(client: AppleGattClient, percent: Int)
    }

    enum class State { IDLE, CONNECTING, DISCOVERING, SUBSCRIBING, READY, CLOSED }

    var state = State.IDLE
        private set
    var mtu = 23
        private set
    val hasAncs: Boolean get() = cp != null
    val hasAms: Boolean get() = eu != null

    private var gatt: BluetoothGatt? = null
    private var ns: BluetoothGattCharacteristic? = null
    private var cp: BluetoothGattCharacteristic? = null
    private var ds: BluetoothGattCharacteristic? = null
    private var rc: BluetoothGattCharacteristic? = null
    private var eu: BluetoothGattCharacteristic? = null
    private var ea: BluetoothGattCharacteristic? = null
    private var bl: BluetoothGattCharacteristic? = null
    /** The phone exposes a Battery Level (Battery Service 0x180F). Logged once per connection. */
    val hasBattery: Boolean get() = bl != null

    private sealed class Op {
        class Subscribe(val c: BluetoothGattCharacteristic) : Op()
        class Write(val c: BluetoothGattCharacteristic, val bytes: ByteArray, val done: (Int) -> Unit) : Op()
        class Read(val c: BluetoothGattCharacteristic, val done: (ByteArray?) -> Unit) : Op()
        class Run(val block: () -> Unit) : Op()
    }

    private val ops = ArrayDeque<Op>()
    private var inFlight: Op? = null
    private var waitingForBond = false
    private var authRetries = 0
    private val ancsTimeout = Runnable { listener.onResponseTimeout(this) }
    private val watchdog = Runnable { Log.w(TAG, "GATT op timed out"); complete(BluetoothGatt.GATT_FAILURE, null) }

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
        handler.removeCallbacks(ancsTimeout); handler.removeCallbacks(watchdog)
        ops.clear(); inFlight = null
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }

    /** The bond with [device] completed: retry the subscription that needed it. */
    fun onBonded() {
        if (!waitingForBond) return
        waitingForBond = false
        pump()
    }

    // --- AncsSession.Io ---

    override fun writeControlPoint(bytes: ByteArray) {
        val c = cp
        if (c == null || state != State.READY) { handler.post { listener.onControlPointResult(this, BluetoothGatt.GATT_FAILURE) }; return }
        enqueue(Op.Write(c, bytes) { listener.onControlPointResult(this, it) })
    }

    override fun armTimeout(ms: Long) { handler.removeCallbacks(ancsTimeout); handler.postDelayed(ancsTimeout, ms) }
    override fun disarmTimeout() { handler.removeCallbacks(ancsTimeout) }

    // --- AmsSession.Io ---

    override fun readAttribute(entity: Int, attribute: Int) {
        val c = ea
        if (c == null || !linkUp) { handler.post { listener.onAmsAttribute(this, null) }; return }
        enqueue(Op.Write(c, Ams.attributeRequest(entity, attribute)) { st ->
            if (st != BluetoothGatt.GATT_SUCCESS) { listener.onAmsAttribute(this, null); return@Write }
            // Runs before anything queued behind it: the read must follow its write.
            ops.addFirst(Op.Read(c) { listener.onAmsAttribute(this, it) })
        })
    }

    override fun command(id: Int) {
        val c = rc ?: return
        if (!linkUp) return
        enqueue(Op.Write(c, Ams.command(id)) { st -> if (st != BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "AMS command $id: status $st") })
    }

    /** AMS starts talking as soon as its subscription is written, before [State.READY]. */
    private val linkUp get() = state == State.READY || state == State.SUBSCRIBING

    // --- queue ---

    private fun enqueue(op: Op) { ops.addLast(op); pump() }

    private fun pump() {
        val g = gatt ?: return
        if (inFlight != null || waitingForBond) return
        val op = ops.removeFirstOrNull() ?: return
        inFlight = op
        val started = when (op) {
            is Op.Run -> { inFlight = null; op.block(); pump(); return }
            is Op.Subscribe -> {
                g.setCharacteristicNotification(op.c, true)
                val d = op.c.getDescriptor(Ancs.CCCD)
                if (d == null) { inFlight = null; pump(); return }
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
            is Op.Write -> {
                op.c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                op.c.value = op.bytes
                g.writeCharacteristic(op.c)
            }
            is Op.Read -> g.readCharacteristic(op.c)
        }
        if (!started) { Log.w(TAG, "GATT op refused"); handler.post { complete(BluetoothGatt.GATT_FAILURE, null) }; return }
        handler.postDelayed(watchdog, OP_TIMEOUT_MS)
    }

    private fun complete(status: Int, value: ByteArray?) {
        val op = inFlight ?: return
        handler.removeCallbacks(watchdog)
        inFlight = null
        when (op) {
            is Op.Subscribe -> if (!onSubscribed(op, status)) return
            is Op.Write -> op.done(status)
            is Op.Read -> op.done(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
            is Op.Run -> {}
        }
        pump()
    }

    /** False = waiting for a bond (the op is back at the front of the queue). */
    private fun onSubscribed(op: Op.Subscribe, status: Int): Boolean {
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> {}
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> {
                // Android usually pairs and retries by itself; if it didn't, ask for a bond and retry once bonded.
                Log.i(TAG, "${device.address}: subscription needs encryption (status $status, bond ${device.bondState})")
                if (++authRetries > 3) { Log.w(TAG, "giving up on encryption"); close(); listener.onDisconnected(this, status); return false }
                ops.addFirst(op)
                waitingForBond = true
                if (device.bondState == BluetoothDevice.BOND_BONDED) handler.postDelayed({ onBonded() }, 500)
                else listener.onNeedsBond(this)
                return false
            }
            else -> Log.w(TAG, "CCCD write failed $status")
        }
        return true
    }

    // --- GATT flow ---

    private fun onServices(g: BluetoothGatt) {
        val ancs = if (wantAncs) g.getService(Ancs.SERVICE) else null
        ns = ancs?.getCharacteristic(Ancs.NOTIFICATION_SOURCE)
        cp = ancs?.getCharacteristic(Ancs.CONTROL_POINT)
        ds = ancs?.getCharacteristic(Ancs.DATA_SOURCE)
        if (ns == null || ds == null) cp = null
        val ams = if (wantAms) g.getService(Ams.SERVICE) else null
        rc = ams?.getCharacteristic(Ams.REMOTE_COMMAND)
        eu = ams?.getCharacteristic(Ams.ENTITY_UPDATE)
        ea = ams?.getCharacteristic(Ams.ENTITY_ATTRIBUTE)
        if (rc == null) eu = null
        bl = g.getService(Battery.SERVICE)?.getCharacteristic(Battery.LEVEL)
        if (!hasAncs && !hasAms) {
            val found = (g.getService(Ancs.SERVICE) != null) to (g.getService(Ams.SERVICE) != null)
            Log.i(TAG, "${device.address}: no ANCS/AMS in use (present: ancs=${found.first} ams=${found.second} battery=$hasBattery; ${g.services.size} services)")
            listener.onNoAppleServices(this)
            return
        }
        state = State.SUBSCRIBING
        // Bigger MTU = fewer Data Source fragments and fewer truncated AMS values; iOS answers 185 at most.
        if (!g.requestMtu(185)) startSubscribing()
    }

    private var subscribing = false

    private fun startSubscribing() {
        if (subscribing) return
        subscribing = true
        if (hasAncs) { enqueue(Op.Subscribe(ds!!)); enqueue(Op.Subscribe(ns!!)) }
        if (hasAms) {
            enqueue(Op.Subscribe(rc!!)); enqueue(Op.Subscribe(eu!!))
            enqueue(Op.Write(eu!!, Ams.SUBSCRIBE_TRACK) { if (it != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "AMS track subscription: $it") })
            enqueue(Op.Write(eu!!, Ams.SUBSCRIBE_PLAYER) { if (it != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "AMS player subscription: $it") })
        }
        enqueue(Op.Run {
            state = State.READY
            Log.i(TAG, "${device.address}: ready (ancs=$hasAncs ams=$hasAms battery=$hasBattery mtu $mtu)")
            listener.onReady(this)
        })
        bl?.let { b ->
            // After READY so a slow answer can't hold it up: notify when the phone offers it, then one read for the current value.
            if (b.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) enqueue(Op.Subscribe(b))
            enqueue(Op.Read(b) { v -> Battery.parse(v)?.let { listener.onBatteryLevel(this, it) } ?: Log.i(TAG, "battery level read: none") })
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (state == State.CLOSED) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        state = State.DISCOVERING
                        if (!g.discoverServices()) { close(); listener.onDisconnected(this@AppleGattClient, status) }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "${device.address}: GATT client disconnected, status $status")
                        close()
                        listener.onDisconnected(this@AppleGattClient, status)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (state == State.CLOSED) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { Log.w(TAG, "discovery failed $status"); listener.onNoAppleServices(this@AppleGattClient); return@post }
                onServices(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                if (state == State.SUBSCRIBING) startSubscribing()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post { if (inFlight is Op.Subscribe) complete(status, null) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (inFlight is Op.Write) complete(status, null) }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            val v = c.value?.copyOf()
            handler.post { if (inFlight is Op.Read) complete(status, v) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            // The value buffer is reused by the stack: copy before hopping threads.
            val v = c.value?.copyOf() ?: return
            val uuid = c.uuid
            handler.post {
                if (state != State.READY && state != State.SUBSCRIBING) return@post
                when (uuid) {
                    Ancs.NOTIFICATION_SOURCE -> if (state == State.READY) listener.onNotificationSource(this@AppleGattClient, v)
                    Ancs.DATA_SOURCE -> listener.onDataSource(this@AppleGattClient, v)
                    Ams.ENTITY_UPDATE -> listener.onAmsEntityUpdate(this@AppleGattClient, v)
                    Ams.REMOTE_COMMAND -> listener.onAmsCommands(this@AppleGattClient, v)
                    Battery.LEVEL -> Battery.parse(v)?.let { listener.onBatteryLevel(this@AppleGattClient, it) }
                }
            }
        }
    }

    companion object {
        const val TAG = "VesperPhone"
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val OP_TIMEOUT_MS = 10_000L
    }
}
