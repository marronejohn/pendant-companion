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

/** Read-only connection state machine. Phase 2 never sends destructive commands. */
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
        val device = hit.device
        _state.value = State.Connecting(device.name ?: device.address)
        scope.launch(Dispatchers.Main) {
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) bond(device)
                connectGatt(device)
            } catch (e: Exception) {
                _state.value = State.Error("Connection failed: ${e.message}")
            }
        }
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

    private suspend fun connectGatt(device: BluetoothDevice) = suspendCancellableCoroutine { cont ->
        val c = PendantGattClient(getApplication(), object : PendantGattClient.Listener {
            override fun onReady(mtu: Int) {
                val s = PendantSession(client!!)
                session = s
                scope.launch(Dispatchers.Main) {
                    try {
                        val info = inspect(s)
                        _state.value = State.Connected(device.name ?: device.address, info)
                    } catch (e: Exception) {
                        _state.value = State.Error("Connected, but device inspection failed: ${e.message}")
                    }
                    if (cont.isActive) cont.resume(Unit) {}
                }
            }
            override fun onNotify(data: ByteArray) { session?.onNotify(data) }
            override fun onClosed(reason: String) {
                session?.close(); session = null; client = null
                if (_state.value !is State.Error) _state.value = State.Error(reason)
                if (cont.isActive) cont.resume(Unit) {}
            }
        })
        client = c
        c.connect(device)
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
