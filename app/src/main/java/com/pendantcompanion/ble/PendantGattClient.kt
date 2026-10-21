package com.pendantcompanion.ble

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one GATT connection to the pendant. Ported from the verified reference
 * (MAkcanca/pendant-cli ble.py):
 *
 * - The pendant requires an encrypted/authenticated link before it accepts
 *   writes to 632de002 (otherwise GATT error 5, Insufficient Authentication).
 *   Bonding happens WHILE the connection is open, then we reconnect so the
 *   stack brings the link up encrypted. Standalone createBond() without a
 *   connection is refused by the pendant.
 * - Write mode follows the characteristic's actual properties (some builds
 *   expose only write-without-response).
 * - Writes are serialized; with write-without-response a short fallback timer
 *   keeps the queue moving when no callback arrives.
 */
class PendantGattClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(mtu: Int)
        fun onNotify(data: ByteArray)
        fun onClosed(reason: String)
    }

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private val writing = AtomicBoolean(false)
    private var closed = false
    private var bondRequested = false
    private var bondReconnecting = false
    private var bondReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile var negotiatedMtu: Int = 23
        private set
    private val writesSent = java.util.concurrent.atomic.AtomicLong(0)
    private val notifyCount = java.util.concurrent.atomic.AtomicLong(0)

    fun writesSentCount(): Long = writesSent.get()
    fun notifyCountTotal(): Long = notifyCount.get()

    @Volatile private var lastNotifyHex: String = ""
    @Volatile private var lastWriteStatus: Int = Int.MIN_VALUE
    @Volatile private var cccdStatus: Int = Int.MIN_VALUE

    fun diagnostics(): String {
        val last = if (lastWriteStatus == Int.MIN_VALUE) "n/a" else lastWriteStatus.toString()
        val cccd = if (cccdStatus == Int.MIN_VALUE) "n/a" else cccdStatus.toString()
        val reply = if (lastNotifyHex.isEmpty()) "" else ", last reply " + lastNotifyHex
        return "sent " + writesSent.get() + " writes (last status " + last + "), got " + notifyCount.get() + " replies" + reply + " [notify-sub status " + cccd + "]"
    }

    fun connect(device: BluetoothDevice) {
        this.device = device
        closed = false
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
    }

    private fun reconnect() {
        val d = device ?: return
        closed = false
        gatt = d.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun send(data: ByteArray): Boolean {
        if (closed) return false
        synchronized(writeQueue) { writeQueue.addLast(data) }
        drain()
        return true
    }

    fun disconnect(reason: String) {
        closeGatt()
        listener.onClosed(reason)
    }

    private fun drain() {
        if (!writing.compareAndSet(false, true)) return
        val next = synchronized(writeQueue) { writeQueue.removeFirstOrNull() }
        if (next == null) {
            writing.set(false)
            return
        }
        val g = gatt
        val ch = g?.getService(PendantUuids.SERVICE)?.getCharacteristic(PendantUuids.WRITE)
        if (g == null || ch == null) {
            writing.set(false)
            closeGatt()
            listener.onClosed("Write characteristic missing; connection unusable")
            return
        }
        val noResponse =
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0 &&
            (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        ch.writeType = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = next
        writesSent.incrementAndGet()
        if (!g.writeCharacteristic(ch)) {
            writing.set(false)
            closeGatt()
            listener.onClosed("GATT write rejected by the stack")
            return
        }
        if (noResponse) {
            handler.postDelayed({
                if (writing.compareAndSet(true, false)) drain()
            }, 120)
        }
    }

    private fun closeGatt() {
        closed = true
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private fun startBond(d: BluetoothDevice) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val dd = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (dd.address != d.address) return
                when (dd.bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        context.unregisterReceiver(this); bondReceiver = null
                        // Reconnect so the stack brings the link up encrypted.
                        bondReconnecting = true
                        closeGatt()
                        handler.postDelayed({ reconnect() }, 300)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        context.unregisterReceiver(this); bondReceiver = null
                        closeGatt()
                        listener.onClosed("Pendant refused pairing (bond rejected)")
                    }
                }
            }
        }
        bondReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        if (!d.createBond()) {
            context.unregisterReceiver(receiver); bondReceiver = null
            closeGatt()
            listener.onClosed("Phone refused to start pairing")
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(498)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (bondReconnecting) {
                        bondReconnecting = false
                        return
                    }
                    val reason = "Disconnected (status $status)"
                    closeGatt()
                    listener.onClosed(reason)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            if (!g.discoverServices()) {
                closeGatt()
                listener.onClosed("Service discovery could not start")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                listener.onClosed("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(PendantUuids.SERVICE)
            val notify = service?.getCharacteristic(PendantUuids.NOTIFY)
            if (service == null || notify == null) {
                closeGatt()
                listener.onClosed("Pendant service or notify characteristic not found; this may not be an LS-PT-01")
                return
            }
            if (!g.setCharacteristicNotification(notify, true)) {
                closeGatt()
                listener.onClosed("Could not enable notifications")
                return
            }
            val cccd = notify.getDescriptor(PendantUuids.CCCD)
            if (cccd == null) {
                closeGatt()
                listener.onClosed("Notify descriptor missing")
                return
            }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            cccdStatus = -99
            if (!g.writeDescriptor(cccd)) {
                closeGatt()
                listener.onClosed("Could not subscribe to pendant notifications")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            cccdStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                listener.onClosed("Subscription failed (status $status)")
                return
            }
            val d = device
            if (d != null && d.bondState != BluetoothDevice.BOND_BONDED && !bondRequested) {
                // Mirror the verified reference order: MTU + discovery +
                // subscription first, THEN bond over the live connection.
                bondRequested = true
                startBond(d)
            } else {
                listener.onReady(negotiatedMtu)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastWriteStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writing.set(false)
                closeGatt()
                listener.onClosed("Write failed (status $status). " + diagnostics())
                return
            }
            writing.set(false)
            drain()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == PendantUuids.NOTIFY) {
                notifyCount.incrementAndGet()
                val v = c.value ?: ByteArray(0)
                if (v.isNotEmpty()) lastNotifyHex = v.take(12).joinToString("") { "%02x".format(it) }
                listener.onNotify(v)
            }
        }
    }

    fun release() {
        bondReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        bondReceiver = null
        closeGatt()
    }
}
