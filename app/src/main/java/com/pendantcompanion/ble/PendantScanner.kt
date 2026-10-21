package com.pendantcompanion.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import java.util.concurrent.TimeUnit

data class PendantScanHit(
    val device: BluetoothDevice,
    val name: String?,
    val rssi: Int,
    val advertisesPendantService: Boolean,
)

/**
 * Scans for BLE devices and keeps only likely LS-PT-01 pendants. The pendant does
 * not always advertise its service UUID, so we match either the advertised service
 * or a "pendant" name, and label which evidence matched. Purely observational:
 * scanning changes nothing on the device.
 */
class PendantScanner(context: Context) {

    private val adapter: BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter

    fun scan(durationSeconds: Long = 10): Flow<PendantScanHit> = callbackFlow {
        require(adapter.isEnabled) { "Bluetooth is off" }
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("This phone has no BLE scanner")
        val seen = HashMap<String, PendantScanHit>()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val service = result.scanRecord?.serviceUuids
                    ?.any { it.uuid.toString().startsWith("632de001") } == true
                val name = result.device?.name ?: result.scanRecord?.deviceName
                val nameMatch = name?.lowercase()?.contains("pendant") == true
                if (!service && !nameMatch) return
                val key = result.device?.address ?: return
                val hit = PendantScanHit(result.device, name, result.rssi, service)
                val prev = seen[key]
                if (prev == null || hit.rssi > prev.rssi) {
                    seen[key] = hit
                    trySend(hit)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed (code $errorCode)"))
            }
        }
        scanner.startScan(cb)
        val t = Handler(Looper.getMainLooper())
        t.postDelayed({ close() }, TimeUnit.SECONDS.toMillis(durationSeconds))
        awaitClose {
            t.removeCallbacksAndMessages(null)
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
    }.mapNotNull { it }
}
