package com.pendantcompanion.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import com.pendantcompanion.ble.PendantGattClient
import com.pendantcompanion.ble.PendantScanHit
import com.pendantcompanion.ble.PendantScanner
import com.pendantcompanion.protocol.FlashPageParser
import com.pendantcompanion.protocol.OpusDecoder
import com.pendantcompanion.protocol.PendantSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

    data class StorageView(
        val oldestPage: Int, val newestPage: Int, val currentSession: Int,
        val freeCapturePages: Int, val totalCapturePages: Int, val syncMode: Int,
    )

    data class SyncProgress(
        val pagesSaved: Int, val framesSeen: Long, val bytesSaved: Long,
        val lastPageIndex: Int, val finished: Boolean, val note: String = "",
    )

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
    private var syncJob: Job? = null
    private val syncProgress = MutableStateFlow<SyncProgress?>(null)
    val sync: StateFlow<SyncProgress?> = syncProgress

    /** One pendant recording session found among the saved flash pages. */
    data class SessionInfo(
        val session: Int, val pages: Int, val frames: Long, val durationSec: Long,
    )

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    sealed interface PlayerState {
        object Idle : PlayerState
        data class Decoding(val session: Int, val done: Int, val total: Int) : PlayerState
        data class Playing(val session: Int) : PlayerState
        data class PlayerError(val message: String) : PlayerState
    }

    private val _player = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val player: StateFlow<PlayerState> = _player
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var decodeJob: Job? = null

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
                val diag = client?.diagnostics() ?: ""
                _state.value = State.Error("Connection failed: ${e.message} $diag")
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
                    s.onBleReady(mtu)
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
                    val diag = " " + (client?.diagnostics() ?: "")
                    session?.close(); session = null; client = null
                    if (cont.isActive) cont.resume(reason + diag) {}
                    else if (_state.value !is State.Error && _state.value !is State.Idle) {
                        _state.value = State.Error(reason + diag)
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
        // Verified handshake order (cli.py runs SetCurrentTime before any query;
        // some firmware will not answer queries until the clock is set).
        s.setCurrentTime()
        val info = s.getDeviceInfo().deviceInfo
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

    /** Read-only storage readout (GetDeviceStatus). Never touches recordings. */
    suspend fun storageStatus(s: PendantSession): StorageView {
        val st = s.getDeviceStatus().deviceStatus
        val ss = st.storageState
        return StorageView(
            oldestPage = ss.oldestFlashPage.toInt(),
            newestPage = ss.newestFlashPage.toInt(),
            currentSession = ss.currentStorageSession.toInt(),
            freeCapturePages = ss.freeCapturePages.toInt(),
            totalCapturePages = ss.totalCapturePages.toInt(),
            syncMode = st.syncModeValue,
        )
    }

    /**
     * READ-ONLY flash drain: switch the pendant to batch download, save every
     * pushed page to app-private storage, then switch back to record-to-flash.
     * NEVER acknowledges pages (no delete_flash_page), so the pendant keeps
     * everything; this is the hard default of the project spec.
     */
    fun syncReadOnly() {
        val s = session ?: return
        val c = client ?: return
        if (syncJob?.isActive == true) return
        syncJob = scope.launch(Dispatchers.Main) {
            var pages = 0
            var frames = 0L
            var bytes = 0L
            var lastIdx = -1
            var note = ""
            try {
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "flash_pages").apply { mkdirs() }
                syncProgress.value = SyncProgress(0, 0, 0, -1, false, "starting download mode")
                s.storageListener = { buf ->
                    val page = buf.flashPage.toByteArray()
                    if (page.isNotEmpty()) {
                        val fp = FlashPageParser.fromStorageBuffer(
                            buf.ingestTypeValue, buf.session, buf.run, buf.seq,
                            buf.index, page)
                        val f = java.io.File(dir, "page_%08d_session%d.bin".format(fp.index, fp.session))
                        f.writeBytes(fp.rawPageBytes)
                        pages++
                        frames += fp.opusFrames.size
                        bytes += fp.rawPageBytes.size
                        lastIdx = fp.index
                        syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, false,
                            "page ${fp.index} (${fp.rawPageBytes.size} B, ${fp.opusFrames.size} frames)")
                    }
                }
                s.beginBatchDownload()
                // Idle timeout: 6s with nothing new means the flash is drained
                // (reference uses 5s). While pages keep arriving, keep going.
                var idle = 0
                while (idle < 30) { // up to ~3 min of total silence
                    kotlinx.coroutines.delay(200)
                    val p0 = pages
                    kotlinx.coroutines.delay(5800)
                    if (pages == p0) idle++ else idle = 0
                    if (idle >= 1) break // 6s of silence
                }
                try { s.stopBatchDownload() } catch (_: Exception) { /* link may drop */ }
                note = "saved $pages pages, pendant copy untouched (no ack sent)"
                syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, true, note)
            } catch (e: Exception) {
                try { s.stopBatchDownload() } catch (_: Exception) {}
                syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, true,
                    "stopped: ${e.message ?: e.javaClass.simpleName}. $pages pages kept, pendant copy untouched")
            } finally {
                s.storageListener = null
                // re-read storage to prove nothing was freed
                try {
                    val st2 = storageStatus(s)
                    note = "after sync: pendant flash now $st2.oldestPage..$st2.newestPage (unchanged = success)"
                    syncProgress.value = syncProgress.value?.copy(note = note)
                } catch (_: Exception) {}
            }
        }
    }

    /** One-shot storage readout, reported through the same status flow as sync. */
    fun checkStorageNow() {
        val s = session ?: return
        if (syncJob?.isActive == true) return
        syncJob = scope.launch(Dispatchers.Main) {
            try {
                val v = storageStatus(s)
                val used = v.totalCapturePages - v.freeCapturePages
                syncProgress.value = SyncProgress(0, 0, 0, -1, true,
                    "flash: $used of ${v.totalCapturePages} capture pages used, session ${v.currentSession}, pages ${v.oldestPage}..${v.newestPage}")
            } catch (e: Exception) {
                syncProgress.value = SyncProgress(0, 0, 0, -1, true,
                    "storage check failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun flashPagesDir(): java.io.File =
        java.io.File(getApplication<Application>().filesDir, "flash_pages")

    private fun wavFileFor(session: Int): java.io.File =
        java.io.File(
            java.io.File(getApplication<Application>().filesDir, "exports").apply { mkdirs() },
            "session_$session.wav",
        )

    /** Groups the saved flash pages into recording sessions (no pendant contact). */
    fun refreshSessions() {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = flashPagesDir()
                val bySess = sortedMapOf<Int, LongArray>() // session -> [pages, frames]
                dir.listFiles()?.forEach { f ->
                    val m = Regex("page_(\\d+)_session(\\d+)\\.bin").matchEntire(f.name)
                        ?: return@forEach
                    val sess = m.groupValues[2].toInt()
                    val frames = FlashPageParser.extractOpusFrames(f.readBytes()).size.toLong()
                    val cur = bySess[sess] ?: longArrayOf(0, 0)
                    cur[0] += 1; cur[1] += frames
                    bySess[sess] = cur
                }
                val list = bySess.map { (sess, pf) ->
                    SessionInfo(sess, pf[0].toInt(), pf[1], pf[1] * 20 / 1000)
                }.sortedBy { it.session }
                _sessions.value = list
            } catch (e: Exception) {
                _player.value = PlayerState.PlayerError("Could not list recordings: ${e.message}")
            }
        }
    }

    private fun collectPackets(session: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        flashPagesDir().listFiles()
            ?.filter { it.name.endsWith("_session$session.bin") }
            ?.sortedBy { it.name }
            ?.forEach { out.addAll(FlashPageParser.extractOpusFrames(it.readBytes())) }
        return out
    }

    /** Decode (once) and play a recording session. Tap again to stop. */
    fun playSession(session: Int) {
        val current = _player.value
        if (current is PlayerState.Playing && current.session == session) { stopPlayback(); return }
        if (decodeJob?.isActive == true) return
        stopPlayback()
        decodeJob = scope.launch(Dispatchers.IO) {
            try {
                val out = wavFileFor(session)
                if (!out.exists() || out.length() <= 44L) {
                    val packets = collectPackets(session)
                    if (packets.isEmpty()) {
                        _player.value = PlayerState.PlayerError("No audio frames found for session $session")
                        return@launch
                    }
                    _player.value = PlayerState.Decoding(session, 0, packets.size)
                    val jobCtx = kotlinx.coroutines.currentCoroutineContext()
                    OpusDecoder.decodeToWav(
                        packets, out,
                        keepGoing = { jobCtx.isActive },
                        onProgress = { done, total -> _player.value = PlayerState.Decoding(session, done, total) },
                    )
                }
                ensureActive()
                withContextMain {
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(out.absolutePath)
                    mp.prepare()
                    mp.setOnCompletionListener {
                        _player.value = PlayerState.Idle
                        try { it.release() } catch (_: Exception) {}
                        if (mediaPlayer === it) mediaPlayer = null
                    }
                    mp.start()
                    mediaPlayer = mp
                    _player.value = PlayerState.Playing(session)
                }
            } catch (e: Exception) {
                _player.value = PlayerState.PlayerError("Playback failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Decode (if needed) and open the share sheet for the session WAV. */
    fun exportSession(session: Int) {
        if (decodeJob?.isActive == true) return
        decodeJob = scope.launch(Dispatchers.IO) {
            try {
                val out = wavFileFor(session)
                if (!out.exists() || out.length() <= 44L) {
                    val packets = collectPackets(session)
                    if (packets.isEmpty()) {
                        _player.value = PlayerState.PlayerError("No audio frames found for session $session")
                        return@launch
                    }
                    _player.value = PlayerState.Decoding(session, 0, packets.size)
                    val jobCtx = kotlinx.coroutines.currentCoroutineContext()
                    OpusDecoder.decodeToWav(
                        packets, out,
                        keepGoing = { jobCtx.isActive },
                        onProgress = { done, total -> _player.value = PlayerState.Decoding(session, done, total) },
                    )
                }
                ensureActive()
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    getApplication(), "com.pendantcompanion.fileprovider", out)
                val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "audio/wav"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                             android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>()
                    .startActivity(android.content.Intent.createChooser(share, "Export session $session"))
                _player.value = PlayerState.Idle
            } catch (e: Exception) {
                _player.value = PlayerState.PlayerError("Export failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun stopPlayback() {
        decodeJob?.cancel(); decodeJob = null
        val mp = mediaPlayer; mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        _player.value = PlayerState.Idle
    }

    private suspend fun withContextMain(block: suspend () -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.Main.immediate) { block() }

    fun stopSync() {
        syncJob?.cancel()
        session?.let { try { kotlinx.coroutines.runBlocking { it.stopBatchDownload() } } catch (_: Exception) {} }
    }

    fun disconnect() {
        stopPlayback()
        session?.close(); session = null
        client?.disconnect("Disconnected")
        client = null
        _state.value = State.Idle
    }

    override fun onCleared() {
        stopPlayback()
        syncJob?.cancel()
        scope.cancel()
        session?.close()
        client?.release()
    }
}
