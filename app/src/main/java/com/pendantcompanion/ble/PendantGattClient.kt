package com.pendantcompanion.ble

import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one GATT connection to the pendant. The pendant is NOT a headset:
 * no audio profiles, no automatic streaming. We discover the verified service,
 * negotiate a large MTU, subscribe to notifications on 632de003 and write
 * to 632de002. Writes are serialized: Android requires waiting for each
 * onCharacteristicWrite before issuing the next.
 */
class PendantGattClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        /** Services discovered, notifications enabled, MTU negotiated. */
        fun onReady(mtu: Int)
        fun onNotify(data: ByteArray)
        fun onClosed(reason: String)
    }

    private var gatt: BluetoothGatt? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private val writing = AtomicBoolean(false)
    private var closed = false
    @Volatile var negotiatedMtu: Int = 23
        private set

    fun connect(device: BluetoothDevice) {
        closed = false
        val g = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, callback)
        }
        gatt = g
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
        ch.value = next
        if (!g.writeCharacteristic(ch)) {
            writing.set(false)
            closeGatt()
            listener.onClosed("GATT write rejected by the stack")
        }
    }

    private fun closeGatt() {
        closed = true
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(498)
                BluetoothProfile.STATE_DISCONNECTED -> {
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
            if (!g.writeDescriptor(cccd)) {
                closeGatt()
                listener.onClosed("Could not subscribe to pendant notifications")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt()
                listener.onClosed("Subscription failed (status $status)")
                return
            }
            listener.onReady(negotiatedMtu)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writing.set(false)
                closeGatt()
                listener.onClosed("Write failed (status $status)")
                return
            }
            writing.set(false)
            drain()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == PendantUuids.NOTIFY) listener.onNotify(c.value ?: ByteArray(0))
        }
    }
}
