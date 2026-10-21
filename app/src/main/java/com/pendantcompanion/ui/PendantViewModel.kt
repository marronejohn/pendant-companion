package com.pendantcompanion.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import com.pendantcompanion.ble.PendantGattClient
import com.pendantcompanion.ble.PendantScanHit
import com.pendantcompanion.ble.PendantScanner
import com.pendantcompanion.protocol.PendantSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Read-only connection state machine. Phase 2 never sends destructive commands.
 *
 * Connect flow: connect the GATT first, and only initiate OS bonding when the
 * pendant rejects a command with an encryption/authorization status (5, 8 or 15).
 * Forcing createBond() up front is rejected by some pendants that only accept
 * bonding negotiated during an active GATT connection.
 */
class PendantViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface State {
        object Idle : State
        object Scanning : State
        data class ScanResults(val hits: List<PendantScanHit>) : State
        data class Connecting(val deviceName: String) : State
        data class Connected(val deviceName: String, val info: DeviceInfoView) : State
        data class Error(val message: String) : State
    }

    data class DeviceInfoView(
        val serial: String,
        val firmware: String,
        val batteryPercent: Int,
        val oldestFlashPage: Int,
        val newestFlashPage: Int,
        val deviceId: Long,
        val audioEncryptionEnabled: Boolean,
    )

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanner: PendantScanner? = null
    private var client: PendantGattClient? = null
    private var session: PendantSession? = null
    private var scanJob: Job? = null
    private var bondAttempted = false

    fun startScan() {
        if (_state.value is State.Scanning) return
        _state.value = State.Scanning
        scanner = scanner ?: PendantScanner(getApplication())
        val hits = mutableListOf<PendantScanHit>()
        scanJob = scope.launch {
            try {
                scanner!!.scan(10).collect {
                    hits.removeAll { h -> h.device.address == it.device.address }
                    hits.add(it)
                    _state.value = State.ScanResults(hits.sortedByDescending { h -> h.rssi })
                }
            } catch (e: Exception) {
                val cur = _state.value
                if (cur is State.Scanning || cur is State.ScanResults) {
                    _state.value = if (hits.isEmpty()) State.Error("Scan failed: ${e.message}")
                                   else State.ScanResults(hits)
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        if (_state.value is State.Scanning) _state.value = State.Idle
    }

    fun pairAndConnect(hit: PendantScanHit) {
        scanJob?.cancel()
        bondAttempted = false
        val device = hit.device
        _state.value = State.Connecting(device.name ?: device.address)
        scope.launch(Dispatchers.Main) {
            try {
                var reason = connectAndInspect(device)
                if (reason != null && needsBond(reason) && device.bondState != BluetoothDevice.BOND_BONDED && !bondAttempted) {
                    bondAttempted = true
                    _state.value = State.Connecting(device.name ?: device.address + " (pairing...)")
                    bond(device)
                    reason = connectAndInspect(device)
                }
                if (reason != null && _state.value !is State.Connected && _state.value !is State.Error) {
                    _state.value = State.Error("Connection failed: $reason")
                }
            } catch (e: Exception) {
                _state.value = State.Error("Connection failed: ${e.message}")
            }
        }
    }

    private fun needsBond(reason: String): Boolean =
        reason.contains("status 5") || reason.contains("status 8") || reason.contains("status 15")

    /** Returns null when connected and inspected; otherwise the failure reason. */
    private suspend fun connectAndInspect(device: BluetoothDevice): String? =
        suspendCancellableCoroutine { cont ->
            val c = PendantGattClient(getApplication(), object : PendantGattClient.Listener {
                override fun onReady(mtu: Int) {
                    val s = PendantSession(client!!)
                    session = s
                    scope.launch(Dispatchers.Main) {
                        var fail: String? = null
                        try {
                            val info = inspect(s)
                            _state.value = State.Connected(device.name ?: device.address, info)
                        } catch (e: Exception) {
                            fail = "Connected, but device inspection failed: ${e.message}"
                            _state.value = State.Error(fail!!)
                        }
                        if (cont.isActive) cont.resume(fail) {}
                    }
                }
                override fun onNotify(data: ByteArray) { session?.onNotify(data) }
                override fun onClosed(reason: String) {
                    session?.close(); session = null; client = null
                    if (cont.isActive) cont.resume(reason) {}
                    else if (_state.value !is State.Error && _state.value !is State.Idle) {
                        _state.value = State.Error(reason)
                    }
                }
            })
            client = c
            c.connect(device)
        }

    private suspend fun bond(device: BluetoothDevice) = suspendCancellableCoroutine { cont ->
        val app = getApplication<Application>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != device.address) return
                when (d.bondState) {
                    BluetoothDevice.BOND_BONDED -> { app.unregisterReceiver(this); cont.resume(Unit) {} }
                    BluetoothDevice.BOND_NONE -> {
                        app.unregisterReceiver(this)
                        cont.cancel(RuntimeException("Pairing was rejected or failed"))
                    }
                }
            }
        }
        app.registerReceiver(receiver, android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        cont.invokeOnCancellation { try { app.unregisterReceiver(receiver) } catch (_: Exception) {} }
        if (!device.createBond()) {
            try { app.unregisterReceiver(receiver) } catch (_: Exception) {}
            cont.cancel(RuntimeException("Phone refused to start pairing"))
        }
    }

    /**
     * Read-only inspection. Send GetDeviceInfo; on timeout run the verified
     * handshake (SetCurrentTime only, no recordings touched) and retry once.
     */
    private suspend fun inspect(s: PendantSession): DeviceInfoView {
        val info = try {
            s.getDeviceInfo().deviceInfo
        } catch (e: Exception) {
            s.setCurrentTime()
            s.getDeviceInfo().deviceInfo
        }
        return DeviceInfoView(
            serial = info.serialNum,
            firmware = info.firmwareVer,
            batteryPercent = info.batteryPercent,
            oldestFlashPage = info.oldestFlashPage,
            newestFlashPage = info.newestFlashPage,
            deviceId = info.deviceId,
            audioEncryptionEnabled = info.deviceAudioEncryptionPubKey.size() > 0,
        )
    }

    fun disconnect() {
        session?.close(); session = null
        client?.disconnect("Disconnected")
        client = null
        _state.value = State.Idle
    }

    override fun onCleared() {
        scope.cancel()
        session?.close()
        client?.disconnect("App closed")
    }
}
