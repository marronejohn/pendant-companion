package com.pendantcompanion.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.pendantcompanion.ble.PendantGattClient
import com.pendantcompanion.ble.PendantScanHit
import com.pendantcompanion.ble.PendantScanner
import com.pendantcompanion.protocol.FlashPageParser
import com.pendantcompanion.protocol.OpusDecoder
import com.pendantcompanion.protocol.WhisperBridge
import com.pendantcompanion.protocol.PendantSession
import limitless.server.Server
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

    /** Live Bluetooth on/off state, updated by a system broadcast receiver. */
    private val _btOn = MutableStateFlow(btAdapterNow() ?: false)
    val btOn: StateFlow<Boolean> = _btOn
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                _btOn.value = btAdapterNow() ?: false
            }
        }
    }

    private fun btAdapterNow(): Boolean? =
        (getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.let { it.isEnabled }

    init {
        ContextCompat.registerReceiver(
            getApplication(), btReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** One pendant recording session found among the saved flash pages. */
    data class SessionInfo(
        val session: Int, val pages: Int, val frames: Long, val durationSec: Long,
        /** Wall-clock start of the session (epoch ms), 0 when the pendant's page stamp is boot-clock and cannot be dated. */
        val startMs: Long = 0,
    )

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    sealed interface PlayerState {
        object Idle : PlayerState
        data class Decoding(val session: Int, val done: Int, val total: Int) : PlayerState
        data class Playing(val session: Int) : PlayerState
        data class Paused(val session: Int) : PlayerState
        data class PlayerError(val message: String) : PlayerState
    }

    private val _player = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val player: StateFlow<PlayerState> = _player

    /** Note from the last decode, e.g. how many frames were skipped as unusable. */
    private val _decodeNote = MutableStateFlow<String?>(null)
    val decodeNote: StateFlow<String?> = _decodeNote
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var decodeJob: Job? = null

    fun startScan() {
        if (_state.value is State.Scanning) return
        val bt = btAdapterNow()
        if (bt == false) {
            _state.value = State.Error("Bluetooth is off. Turn it on, then tap Find my pendant again.")
            return
        }
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
                            checkStorageNow()
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
            var skipped = 0
            var lastIdx = -1
            var note = ""
            try {
                val ctx = getApplication<Application>()
                val dir = java.io.File(ctx.filesDir, "flash_pages").apply { mkdirs() }
                // Incremental: page index -> highest already on this phone.
                val pageRe = Regex("page_(\\d+)_session\\d+\\.bin")
                val maxSaved = (dir.listFiles { f -> f.name.startsWith("page_") }
                    ?.mapNotNull { pageRe.find(it.name)?.groupValues?.get(1)?.toInt() }
                    ?.maxOrNull())
                // Fast path: if the pendant's newest page is one we already hold,
                // skip the whole download instead of re-streaming the flash.
                var skipDownload = false
                try {
                    val st = storageStatus(s)
                    if (maxSaved != null && st.newestPage <= maxSaved) {
                        skipDownload = true
                        syncProgress.value = SyncProgress(0, 0, 0, st.newestPage, true,
                            "already up to date: pendant's newest page is ${st.newestPage} and this phone has through $maxSaved. nothing to download")
                        return@launch
                    }
                } catch (_: Exception) { /* storage readout failed: do the full sync */ }
                syncProgress.value = SyncProgress(0, 0, 0, -1, false, "starting download mode")
                s.storageListener = { buf ->
                    val page = buf.flashPage.toByteArray()
                    if (page.isNotEmpty()) {
                        val fp = FlashPageParser.fromStorageBuffer(
                            buf.ingestTypeValue, buf.session, buf.run, buf.seq,
                            buf.index, page)
                        val f = java.io.File(dir, "page_%08d_session%d.bin".format(fp.index, fp.session))
                        if (f.exists()) {
                            // already on this phone from an earlier sync: keep the
                            // original file, only count the transfer
                            skipped++
                            syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, false,
                                "page ${fp.index} already saved — skipping (${skipped} so far)")
                        } else {
                            f.writeBytes(fp.rawPageBytes)
                            pages++
                            frames += fp.opusFrames.size
                            bytes += fp.rawPageBytes.size
                            lastIdx = fp.index
                            syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, false,
                                "page ${fp.index} (${fp.rawPageBytes.size} B, ${fp.opusFrames.size} frames)")
                        }
                    }
                }
                s.beginBatchDownload()
                syncProgress.value = SyncProgress(0, 0, 0, -1, false,
                    "download request sent — waiting for the pendant to send pages...")
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
                note = if (pages == 0 && skipped == 0)
                    "pendant sent no pages. Try: short-press the pendant to wake it, make sure no other app is connected to it, then reconnect here and tap Download flash again."
                else if (skipped > 0)
                    "saved $pages new pages, skipped $skipped already on this phone, pendant copy untouched (no ack sent)"
                else
                    "saved $pages pages, pendant copy untouched (no ack sent)"
                syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, true, note)
            } catch (e: Exception) {
                try { s.stopBatchDownload() } catch (_: Exception) {}
                syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, true,
                    "stopped: ${e.message ?: e.javaClass.simpleName}. $pages pages kept, pendant copy untouched")
            } finally {
                s.storageListener = null
                // re-read storage to prove nothing was freed (time-bounded so a
                // dead link can never leave this job active and block the next sync)
                try {
                val st2 = kotlinx.coroutines.withTimeoutOrNull(6_000) { storageStatus(s) }
                if (st2 != null) {
                    val note2 = "after sync: pendant flash now $st2.oldestPage..$st2.newestPage (unchanged = success)"
                    syncProgress.value = syncProgress.value?.copy(note = note2)
                }
                } catch (_: Exception) {}
            }
        }
    }

    /** One-shot storage readout, reported through the same status flow as sync. */
    fun checkStorageNow() {
        val s = session ?: run {
            syncProgress.value = SyncProgress(0, 0, 0, -1, true, "not connected yet")
            return
        }
        if (syncJob?.isActive == true) return
        syncJob = scope.launch(Dispatchers.Main) {
            try {
                val v = kotlinx.coroutines.withTimeoutOrNull(8_000) { storageStatus(s) }
                if (v == null) {
                    syncProgress.value = SyncProgress(0, 0, 0, -1, true,
                        "storage check timed out (no reply from pendant)")
                    return@launch
                }
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
                val firstPage = mutableMapOf<Int, Pair<Int, java.io.File>>() // session -> (lowest page index, its file)
                dir.listFiles()?.forEach { f ->
                    val m = Regex("page_(\\d+)_session(\\d+)\\.bin").matchEntire(f.name)
                        ?: return@forEach
                    val idx = m.groupValues[1].toInt()
                    val sess = m.groupValues[2].toInt()
                    val frames = FlashPageParser.extractOpusFrames(f.readBytes()).size.toLong()
                    val cur = bySess[sess] ?: longArrayOf(0, 0)
                    cur[0] += 1; cur[1] += frames
                    bySess[sess] = cur
                    val best = firstPage[sess]
                    if (best == null || idx < best.first) firstPage[sess] = idx to f
                }
                val list = bySess.map { (sess, pf) ->
                    // The pendant stamps every page with its own clock. Recent
                    // firmware writes epoch milliseconds (>= 10^12); older firmware
                    // wrote boot-clock milliseconds, which cannot be dated without a
                    // cross-reference, so those sessions stay undated here.
                    val ts = FlashPageParser.timestampMs(firstPage.getValue(sess).second.readBytes())
                    val startMs = if (ts >= 1_000_000_000_000L) ts else 0L
                    SessionInfo(sess, pf[0].toInt(), pf[1], pf[1] * 20 / 1000, startMs)
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
        if (current is PlayerState.Playing && current.session == session) {
            // pause, keep position
            try { mediaPlayer?.pause() } catch (_: Exception) {}
            _player.value = PlayerState.Paused(session)
            return
        }
        if (current is PlayerState.Paused && current.session == session) {
            try { mediaPlayer?.start() } catch (_: Exception) {}
            _player.value = PlayerState.Playing(session)
            return
        }
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
                    var skipped = 0
                    _player.value = PlayerState.Decoding(session, 0, packets.size)
                    val jobCtx = kotlinx.coroutines.currentCoroutineContext()
                    OpusDecoder.decodeToWav(
                        packets, out,
                        keepGoing = { jobCtx.isActive },
                        onProgress = { done, total -> _player.value = PlayerState.Decoding(session, done, total) },
                        onSkipped = { n, _ -> skipped += n },
                    )
                    _decodeNote.value = if (skipped > 0)
                        "Session $session: skipped $skipped unusable frames while decoding"
                    else
                        null
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
                    var skipped = 0
                    _player.value = PlayerState.Decoding(session, 0, packets.size)
                    val jobCtx = kotlinx.coroutines.currentCoroutineContext()
                    OpusDecoder.decodeToWav(
                        packets, out,
                        keepGoing = { jobCtx.isActive },
                        onProgress = { done, total -> _player.value = PlayerState.Decoding(session, done, total) },
                        onSkipped = { n, _ -> skipped += n },
                    )
                    _decodeNote.value = if (skipped > 0)
                        "Session $session: skipped $skipped unusable frames while decoding"
                    else
                        null
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
                withContextMain {
                    // the chooser is a fresh intent: it does NOT inherit the
                    // share intent's flags, so it needs NEW_TASK itself when
                    // started from application context
                    val chooser = android.content.Intent.createChooser(share, "Export session $session")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    getApplication<Application>().startActivity(chooser)
                }
                _player.value = PlayerState.Idle
            } catch (e: Exception) {
                _player.value = PlayerState.PlayerError("Export failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    sealed interface TransState {
        object Idle : TransState
        data class Running(val session: Int, val note: String) : TransState
        data class Done(val session: Int, val text: String) : TransState
        data class TransError(val session: Int, val message: String) : TransState
    }


    // ----- pendant LED brightness -----
    // SetLEDBrightness (ServerCommandMsg field 26) exists in the protocol but
    // this is its first use against real hardware, so every write is followed
    // by a GetDeviceStatus read-back and the card shows what actually stuck.

    data class LedState(
        val reported: Int? = null,
        val note: String? = null,
        val busy: Boolean = false,
    )

    private val _led = MutableStateFlow(LedState())
    val led: StateFlow<LedState> = _led

    /** Ask the pendant for its current status and surface the LED brightness. */
    fun refreshLedBrightness() {
        val s = session ?: return
        _led.value = _led.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            try {
                val resp = s.getDeviceStatus()
                val reported = if (resp.hasDeviceStatus()) resp.deviceStatus.ledBrightness else null
                withContextMain { _led.value = LedState(reported = reported, note = _led.value.note, busy = false) }
            } catch (e: Exception) {
                withContextMain { _led.value = _led.value.copy(busy = false, note = "Could not read LED brightness: ${e.message}") }
            }
        }
    }

    /** Send a brightness value, then read it back so the card shows the truth. */
    fun setLedBrightness(value: Int, label: String) {
        val s = session ?: return
        _led.value = _led.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            try {
                s.sendOneway {
                    it.setLedBrightness = Server.SetLEDBrightness.newBuilder()
                        .setBrightness(value).build()
                }
                kotlinx.coroutines.delay(400)
                val reported = try {
                    val resp = s.getDeviceStatus()
                    if (resp.hasDeviceStatus()) resp.deviceStatus.ledBrightness else null
                } catch (_: Exception) { null }
                withContextMain {
                    _led.value = LedState(
                        reported = reported,
                        note = "$label sent; pendant reports brightness ${reported ?: "?"}",
                        busy = false,
                    )
                }
            } catch (e: Exception) {
                withContextMain { _led.value = _led.value.copy(busy = false, note = "LED command failed: ${e.message}") }
            }
        }
    }

    private val _trans = MutableStateFlow<TransState>(TransState.Idle)
    val trans: StateFlow<TransState> = _trans
    private var transJob: Job? = null

    data class ModelInfo(val path: String?, val sizeBytes: Long, val ready: Boolean)
    private val _model = MutableStateFlow(ModelInfo(null, 0L, false))
    val model: StateFlow<ModelInfo> = _model

    private fun modelFile(): java.io.File {
        val saved = getApplication<Application>()
            .getSharedPreferences("pendant", android.content.Context.MODE_PRIVATE)
            .getString("whisper_model_path", null)
        return if (saved != null) java.io.File(saved)
               else java.io.File(java.io.File(getApplication<Application>().filesDir, "whisper"), "model.bin")
    }

    fun refreshModelInfo() {
        val f = modelFile()
        if (f.exists() && f.length() > 10_000_000) {
            _model.value = ModelInfo(f.absolutePath, f.length(), _model.value.ready)
        }
    }

    /** Copies a user-picked ggml .bin into app storage and loads it natively. */
    fun importModel(uri: android.net.Uri) {
        if (transJob?.isActive == true) return
        transJob = scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "whisper").apply { mkdirs() }
                val dst = java.io.File(dir, "model.bin")
                val src = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file")
                src.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                if (dst.length() < 10_000_000) {
                    dst.delete()
                    throw IllegalStateException("That file is too small to be a Whisper model (${dst.length()} bytes)")
                }
                getApplication<Application>()
                    .getSharedPreferences("pendant", android.content.Context.MODE_PRIVATE)
                    .edit().putString("whisper_model_path", dst.absolutePath).commit()
                val ok = WhisperBridge.nativeInit(dst.absolutePath)
                _model.value = ModelInfo(dst.absolutePath, dst.length(), ok)
                if (!ok) {
                    _trans.value = TransState.TransError(-1, "Whisper did not load that file; is it a ggml .bin model?")
                }
            } catch (e: Exception) {
                _trans.value = TransState.TransError(-1, "Model import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun transcribeSession(session: Int) {
        if (transJob?.isActive == true) return
        transJob = scope.launch(Dispatchers.Default) {
            try {
                val mf = modelFile()
                if (!mf.exists() || mf.length() < 10_000_000) {
                    _trans.value = TransState.TransError(session, "No Whisper model loaded. Tap Load model file first.")
                    return@launch
                }
                _trans.value = TransState.Running(session, "decoding audio")
                val wav = decodeSessionWav(session)
                _trans.value = TransState.Running(session, "loading Whisper model")
                if (!WhisperBridge.nativeInit(mf.absolutePath)) {
                    throw IllegalStateException("Whisper failed to load the model file")
                }
                _model.value = _model.value.copy(ready = true)
                _trans.value = TransState.Running(session, "transcribing; a few minutes for a long session")
                val samples = wavToSamples(wav)
                val text = WhisperBridge.nativeTranscribe(samples, 4)
                if (text == null || text.isBlank()) {
                    throw IllegalStateException("Whisper returned no text")
                }
                val tf = transcriptFile(session)
                tf.writeText(text)
                _trans.value = TransState.Done(session, text)
            } catch (e: Exception) {
                _trans.value = TransState.TransError(session, "Transcription failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** One transcript match: session, the [mm:ss] stamp of the first hit, and matching lines. */
    data class SearchHit(val session: Int, val stamp: String, val snippet: String)
    data class SearchState(val query: String, val hits: List<SearchHit>, val searched: Boolean)

    private val _search = MutableStateFlow(SearchState("", emptyList(), false))
    val search: StateFlow<SearchState> = _search
    private var searchJob: Job? = null

    /**
     * Local-only keyword search over saved transcripts. No network, no index
     * file to maintain: transcripts are small, so each search re-scans them.
     */
    fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) { _search.value = SearchState("", emptyList(), false); return }
        if (searchJob?.isActive == true) return
        searchJob = scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "transcripts")
                val hits = mutableListOf<SearchHit>()
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val m = Regex("session_(\\d+)\\.txt").matchEntire(f.name) ?: return@forEach
                    val sess = m.groupValues[1].toInt()
                    val matches = mutableListOf<String>()
                    var stamp = ""
                    for (line in f.readLines()) {
                        if (line.contains(query, ignoreCase = true)) {
                            if (matches.isEmpty()) {
                                stamp = Regex("\\[[^]]+\\]").find(line)?.value ?: ""
                            }
                            matches.add(line.trim().take(220))
                            if (matches.size >= 3) break
                        }
                    }
                    if (matches.isNotEmpty()) hits.add(SearchHit(sess, stamp, matches.joinToString("\n")))
                    if (hits.size >= 40) return@forEach
                }
                _search.value = SearchState(query, hits, true)
            } catch (e: Exception) {
                _search.value = SearchState(query, emptyList(), true)
            }
        }
    }

    /** True when at least one transcript file exists on this phone. */
    fun hasAnyTranscripts(): Boolean =
        java.io.File(getApplication<Application>().filesDir, "transcripts")
            .listFiles()?.any { it.name.startsWith("session_") && it.name.endsWith(".txt") } == true

    /** Full transcript text for the in-app viewer, or null when none is saved. */
    fun transcriptText(session: Int): String? =
        transcriptFile(session).takeIf { it.exists() }?.readText()

    fun transcriptFile(session: Int): java.io.File =
        java.io.File(
            java.io.File(getApplication<Application>().filesDir, "transcripts").apply { mkdirs() },
            "session_$session.txt",
        )

    fun shareTranscript(session: Int) {
        val f = transcriptFile(session)
        if (!f.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            getApplication(), "com.pendantcompanion.fileprovider", f)
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = android.content.Intent.createChooser(share, "Transcript session $session")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(chooser)
    }

    private suspend fun decodeSessionWav(session: Int): java.io.File {
        val out = wavFileFor(session)
        if (out.exists() && out.length() > 44L) return out
        val packets = collectPackets(session)
        if (packets.isEmpty()) throw IllegalStateException("No audio frames found for session $session")
        val jobCtx = kotlinx.coroutines.currentCoroutineContext()
        OpusDecoder.decodeToWav(packets, out, { jobCtx.isActive }, { _, _ -> })
        return out
    }

    /** 48 kHz 16-bit WAV -> 16 kHz float samples (simple 3:1 box filter). */
    private fun wavToSamples(wav: java.io.File): FloatArray {
        val bytes = wav.readBytes()
        val start = 44
        val n = (bytes.size - start) / 6
        val out = FloatArray(n)
        var bi = start
        for (i in 0 until n) {
            val s0 = ((bytes[bi].toInt() and 0xff) or (bytes[bi + 1].toInt() shl 8)).toShort().toInt()
            val s1 = ((bytes[bi + 2].toInt() and 0xff) or (bytes[bi + 3].toInt() shl 8)).toShort().toInt()
            val s2 = ((bytes[bi + 4].toInt() and 0xff) or (bytes[bi + 5].toInt() shl 8)).toShort().toInt()
            out[i] = ((s0 + s1 + s2) / 3f) / 32768f
            bi += 6
        }
        return out
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
        transJob?.cancel()
        WhisperBridge.nativeFree()
        syncJob?.cancel()
        scope.cancel()
        session?.close()
        client?.release()
    }
}
