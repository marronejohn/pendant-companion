package com.pendantcompanion.ui

import com.pendantcompanion.ReflectionAi
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
import com.pendantcompanion.TranscribeService
import com.pendantcompanion.SpeakerVerifier
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.pendantcompanion.buildBackupZip

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
        // Keep the daily-insights alarm armed whenever the app opens.
        try { ReflectionAi.armNext(getApplication()) } catch (_: Exception) {}
    }

    /** One pendant recording session found among the saved flash pages. */
    data class SessionInfo(
        val session: Int, val pages: Int, val frames: Long, val durationSec: Long,
        /** Wall-clock start of the session (epoch ms), 0 when the pendant's page stamp is boot-clock and cannot be dated. */
        val startMs: Long = 0,
    )

    // Ack mode (opt-in, default off): when on, every synced page is
    // acknowledged (delete older_than_or_equal_to_index) only after it is
    // durably written on this phone, and the pendant erases it. The phone
    // becomes the only copy; a backup zip is rebuilt after each acked sync.
    private val prefs = app.getSharedPreferences("pendant_prefs", Context.MODE_PRIVATE)
    private val _ackMode = MutableStateFlow(prefs.getBoolean("ack_mode", false))
    val ackMode: StateFlow<Boolean> = _ackMode
    private val ackMutex = Mutex()

    fun setAckMode(on: Boolean) {
        _ackMode.value = on
        prefs.edit().putBoolean("ack_mode", on).apply()
    }

    private val _autoTranscribe = MutableStateFlow(prefs.getBoolean("auto_transcribe", true))
    val autoTranscribe: StateFlow<Boolean> = _autoTranscribe
    fun setAutoTranscribe(on: Boolean) {
        _autoTranscribe.value = on
        prefs.edit().putBoolean("auto_transcribe", on).apply()
    }


    // ----- Guided setup (public edition) -----

    private val _setupDone = MutableStateFlow(prefs.getBoolean("setup_done", false))
    val setupDone: StateFlow<Boolean> = _setupDone
    fun finishSetup() {
        prefs.edit().putBoolean("setup_done", true).apply()
        _setupDone.value = true
    }

    /** (label, bytes so far, total bytes) while a Whisper model downloads. */
    private val _modelDownload = MutableStateFlow<Triple<String, Long, Long>?>(null)
    val modelDownload: StateFlow<Triple<String, Long, Long>?> = _modelDownload

    /** Downloads a Whisper ggml model into the app's default model path. */
    fun downloadWhisperModel(url: String, label: String) {
        transJob = scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "whisper").apply { mkdirs() }
                val dst = java.io.File(dir, "model.bin")
                val tmp = java.io.File(dir, "model.bin.part")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30_000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                conn.inputStream.use { ins ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            _modelDownload.value = Triple(label, got, if (total > 0) total else 0L)
                        }
                    }
                }
                conn.disconnect()
                if (dst.length() < 10_000_000) {
                    dst.delete()
                    throw IllegalStateException("the download came up short (${dst.length()} bytes); try again or pick a file manually")
                }
                // renameTo fails when the destination already exists, which is
                // exactly the returning-user case: drop the old model first.
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) throw IllegalStateException("could not move the downloaded model into place")
                if (prefs.getString("whisper_model_path", null) == null) {
                    prefs.edit().putString("whisper_model_path", dst.absolutePath).commit()
                }
                // Validate the download the same way the file-picker path does,
                // so the guided flow sees ready=true and moves on.
                val h = WhisperBridge.nativeInit(dst.absolutePath)
                val ok = h != 0L
                if (ok) WhisperBridge.nativeFree(h)
                _model.value = ModelInfo(dst.absolutePath, dst.length(), ok)
                _modelDownload.value = null
                if (!ok) throw IllegalStateException("Whisper did not load the downloaded model")
            } catch (e: Exception) {
                _modelDownload.value = null
                _trans.value = TransState.TransError(-1,
                    "Download failed: ${e.message ?: e.javaClass.simpleName}. You can also pick the model file manually.")
            }
        }
    }

    /** Marks a Whisper model already on the phone as ready (validation load),
     *  so the guided setup skips the download step for returning users. */
    fun ensureModelValidated() {
        if (_model.value.ready) return
        if (transJob?.isActive == true) return
        val f = modelFile()
        if (!f.exists() || f.length() < 10_000_000) return
        transJob = scope.launch(Dispatchers.IO) {
            try {
                val h = WhisperBridge.nativeInit(f.absolutePath)
                val ok = h != 0L
                if (ok) WhisperBridge.nativeFree(h)
                _model.value = ModelInfo(f.absolutePath, f.length(), ok)
            } catch (_: Exception) {}
        }
    }

    /** Voiceprint from a live in-app recording (guided setup). Keys are negative. */
    fun enrollFromMic(samples: FloatArray, slot: Int) {
        if (!_speaker.value.loaded) { _speakerNote.value = "Load the voice model file first."; return }
        if (speakerJob?.isActive == true) { _speakerNote.value = "Another voice job is running; try again in a moment."; return }
        val key = -slot
        speakerStop = false
        _speaker.value = _speaker.value.copy(enrolled = (speakerVecs.keys + key).sorted())
        speakerJob = scope.launch(Dispatchers.IO) {
            try {
                _speakerNote.value = "Building your voiceprint from the recording\u2026"
                val v = embedWindows(samples, maxWindows = 40)
                    ?: throw IllegalStateException("Not enough clear speech in that recording. Try again, talking a little more.")
                speakerVecs[key] = v
                writeFloats(java.io.File(speakerDir(), "vec_" + key + ".bin"), v)
                recomputeCentroid()
                val keys = speakerVecs.keys.sorted()
                prefs.edit().putString("speaker_enrolled", keys.joinToString(",")).apply()
                _speaker.value = _speaker.value.copy(enrolled = keys)
                _speakerNote.value = "Voiceprint updated from your recording."
                scoreSessions()
            } catch (e: Exception) {
                speakerVecs.remove(key)
                java.io.File(speakerDir(), "vec_" + key + ".bin").delete()
                recomputeCentroid()
                _speaker.value = _speaker.value.copy(enrolled = speakerVecs.keys.sorted())
                _speakerNote.value = e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Auto chain after an automatic sync: transcribe only what is new, then
     *  write today's AI card if it is due and still missing. */
    fun autoPipelineAfterSync() {
        scope.launch(Dispatchers.Default) {
            try {
                if (!_autoTranscribe.value) return@launch
                if (_sessions.value.none { it.frames > 0 }) return@launch
                refreshSessions()
                if (_sessions.value.none { s -> s.frames > 0 && !transcriptFile(s.session).exists() }) {
                    ReflectionAi.maybeCatchUp(getApplication())
                    return@launch
                }
                transcribeAllSessions(onlyNew = true)
                val deadline = System.currentTimeMillis() + 6L * 60 * 60 * 1000
                while (System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(5_000)
                    val st = BatchRunner.state.value
                    val ts = _trans.value
                    if (st == null || st.note == "finished" || ts is TransState.TransError) break
                }
                ReflectionAi.maybeCatchUp(getApplication())
            } catch (_: Exception) {}
        }
    }

    private val _driveFolder = MutableStateFlow(prefs.getString("drive_tree_uri", null))
    val driveFolder: StateFlow<String?> = _driveFolder
    private val _driveFolderInfo = MutableStateFlow(prefs.getString("drive_folder_info", "") ?: "")
    val driveFolderInfo: StateFlow<String> = _driveFolderInfo
    private val _mirrorNote = MutableStateFlow("")
    val mirrorNote: StateFlow<String> = _mirrorNote

    /** One-time grant: the user picks a Drive folder in the system picker and
     *  the app keeps persistable read/write to it. Transcripts are mirrored
     *  there through the system document provider, so the app itself stays
     *  offline (no INTERNET permission). */
    fun setDriveFolder(uri: android.net.Uri) {
        var granted = true
        var err = ""
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                     android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { granted = false; err = e.message ?: e.javaClass.simpleName }
        prefs.edit().putString("drive_tree_uri", uri.toString()).apply()
        _driveFolder.value = uri.toString()
        // Name the picked place and its provider, so a pick inside phone
        // storage instead of Google Drive is visible instead of silent.
        val where = try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val cr = getApplication<Application>().contentResolver
            val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            var name = ""
            cr.query(docUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                     null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: "" }
            val provider = when (uri.authority) {
                "com.android.externalstorage.documents" -> "phone storage, NOT Google Drive"
                "com.android.providers.downloads.documents" -> "Downloads, NOT Google Drive"
                "com.google.android.apps.docs.storage",
                "com.google.android.apps.docs.storage.legacy" -> "Google Drive"
                else -> uri.authority ?: "unknown provider"
            }
            if (name.isNotBlank()) "$name ($provider)" else provider
        } catch (e: Exception) { "unreadable folder: ${e.message ?: e.javaClass.simpleName}" }
        val line = if (granted) "Linked: $where" else "Link problem: $err"
        prefs.edit().putString("drive_folder_info", line).apply()
        _driveFolderInfo.value = line
    }

    // Auto-sync on connect (default on): after the pendant connects and
    // inspection succeeds, a read-only sync starts automatically, and the
    // backup zip is refreshed afterwards when new pages were saved.
    private val _autoSync = MutableStateFlow(prefs.getBoolean("auto_sync_on_connect", true))
    val autoSync: StateFlow<Boolean> = _autoSync

    fun setAutoSync(on: Boolean) {
        _autoSync.value = on
        prefs.edit().putBoolean("auto_sync_on_connect", on).apply()
    }

    // ---- Speaker verification (voiceprint) ----
    data class SpeakerInfo(
        val path: String? = null,
        val loaded: Boolean = false,
        val enrolled: List<Int> = emptyList(),
        val threshold: Float = 0.75f,
    )
    data class SessionVoice(val session: Int, val youPct: Int, val voiced: Int, val windows: Int)

    private val _speaker = MutableStateFlow(SpeakerInfo(threshold = prefs.getFloat("speaker_threshold", 0.75f)))
    val speaker: StateFlow<SpeakerInfo> = _speaker
    private val _speakerScores = MutableStateFlow<Map<Int, SessionVoice>>(emptyMap())
    private val _cleaning = MutableStateFlow(false)
    val cleaning: StateFlow<Boolean> = _cleaning
    val speakerScores: StateFlow<Map<Int, SessionVoice>> = _speakerScores
    private val _speakerNote = MutableStateFlow<String?>(null)
    val speakerNote: StateFlow<String?> = _speakerNote
    private val _searchOnlyYou = MutableStateFlow(prefs.getBoolean("search_only_you", false))
    val searchOnlyYou: StateFlow<Boolean> = _searchOnlyYou
    @Volatile private var speakerStop = false
    private var speakerJob: Job? = null
    private var speakerVecs = mutableMapOf<Int, FloatArray>()
    private var speakerCentroid: FloatArray? = null
    private var lastQuery = ""

    init {
        refreshSpeakerInfo()
        scope.launch(Dispatchers.IO) {
            try {
                val f = sessionsIndexFile()
                if (f.exists() && _sessions.value.isEmpty()) {
                    val arr = org.json.JSONArray(f.readText())
                    val list = (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        SessionInfo(o.getInt("session"), o.getInt("pages"), o.getLong("frames"),
                            o.getLong("durationSec"), o.optLong("startMs"))
                    }.sortedBy { it.session }
                    if (list.isNotEmpty()) _sessions.value = list
                    reloadScoresFromTags()
                }
            } catch (_: Exception) {}
        }
    }

    fun setSearchOnlyYou(on: Boolean) {
        _searchOnlyYou.value = on
        prefs.edit().putBoolean("search_only_you", on).apply()
        if (lastQuery.isNotEmpty()) runSearch(lastQuery)
    }

    private fun speakerDir(): java.io.File =
        java.io.File(getApplication<Application>().filesDir, "speaker").apply { mkdirs() }

    private fun tagsFile(s: Int): java.io.File =
        java.io.File(java.io.File(getApplication<Application>().filesDir, "transcripts"), "session_$s.tags.json")

    fun refreshSpeakerInfo() {
        val saved = prefs.getString("speaker_model_path", null)
        var loaded = false
        if (saved != null && java.io.File(saved).length() > 50_000_000L) {
            loaded = SpeakerVerifier.load(saved)
            if (!loaded) _speakerNote.value = "The saved voice model did not load; pick the .onnx file again."
        }
        speakerVecs = mutableMapOf()
        prefs.getString("speaker_enrolled", null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.forEach { id ->
            readFloats(java.io.File(speakerDir(), "vec_$id.bin"))?.let { speakerVecs[id] = it }
        }
        speakerCentroid = readFloats(java.io.File(speakerDir(), "enroll.bin"))
        _speaker.value = SpeakerInfo(saved, loaded, speakerVecs.keys.sorted(), _speaker.value.threshold)
        if (loaded && speakerVecs.isNotEmpty() && speakerCentroid == null) recomputeCentroid()
    }

    private fun readFloats(f: java.io.File): FloatArray? = try {
        if (f.exists()) {
            val a = FloatArray(192)
            java.nio.ByteBuffer.wrap(f.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(a)
            a
        } else null
    } catch (e: Exception) { null }

    private fun writeFloats(f: java.io.File, v: FloatArray) {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        f.writeBytes(bb.array())
    }

    /** Copies a user-picked ECAPA .onnx into app storage and loads it. */
    fun importSpeakerModel(uri: android.net.Uri) {
        if (speakerJob?.isActive == true) return
        speakerJob = scope.launch(Dispatchers.IO) {
            try {
                val dst = java.io.File(speakerDir(), "ecapa.onnx")
                val src = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open the selected file")
                src.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
                if (dst.length() < 50_000_000L) {
                    dst.delete()
                    throw IllegalStateException("That file is too small to be the voice model (${dst.length()} bytes)")
                }
                if (!SpeakerVerifier.load(dst.absolutePath)) {
                    dst.delete()
                    throw IllegalStateException("ONNX Runtime could not load that file; is it ecapa-any-length.onnx?")
                }
                prefs.edit().putString("speaker_model_path", dst.absolutePath).apply()
                _speaker.value = _speaker.value.copy(path = dst.absolutePath, loaded = true)
                _speakerNote.value = "Voice model loaded."
            } catch (e: Exception) {
                _speakerNote.value = "Voice model import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Mean voiceprint over 3 s clips (1.5 s hop) with enough energy, or null. */
    private fun embedWindows(samples: FloatArray, maxWindows: Int = 120): FloatArray? {
        val w = 48000; val hop = 24000
        val idx = mutableListOf<Int>()
        var st = 0
        while (st + w <= samples.size) {
            var sum = 0.0
            for (k in st until st + w step 4) { val g = samples[k]; sum += g * g }
            if (sum / (w / 4) > 0.005 * 0.005) idx.add(st)
            st += hop
        }
        if (idx.size > maxWindows) {
            val stride = idx.size / maxWindows
            val keep = mutableListOf<Int>()
            var i = 0
            while (i < idx.size) { keep.add(idx[i]); i += stride }
            idx.clear(); idx.addAll(keep)
        }
        if (idx.size < 3) return null
        val acc = FloatArray(192)
        var n = 0
        for (start in idx) {
            val v = SpeakerVerifier.embed(samples.copyOfRange(start, start + w)) ?: continue
            for (i in 0 until 192) acc[i] += v[i]
            n++
        }
        if (n < 3) return null
        var norm = 0f
        for (g in acc) norm += g * g
        norm = kotlin.math.sqrt(norm) + 1e-9f
        for (i in 0 until 192) acc[i] /= norm
        return acc
    }

    private fun recomputeCentroid() {
        if (speakerVecs.isEmpty()) { speakerCentroid = null; return }
        val acc = FloatArray(192)
        for (v in speakerVecs.values) for (i in 0 until 192) acc[i] += v[i]
        var norm = 0f
        for (g in acc) norm += g * g
        norm = kotlin.math.sqrt(norm) + 1e-9f
        for (i in 0 until 192) acc[i] /= norm
        speakerCentroid = acc
        writeFloats(java.io.File(speakerDir(), "enroll.bin"), acc)
        prefs.edit().putInt("speaker_enroll_version", prefs.getInt("speaker_enroll_version", 1) + 1).apply()
    }

    fun clearEnrollment() {
        speakerJob?.cancel()
        speakerStop = true
        speakerVecs.clear()
        speakerCentroid = null
        speakerDir().listFiles()?.forEach { if (it.name.startsWith("vec_") || it.name == "enroll.bin") it.delete() }
        prefs.edit().putString("speaker_enrolled", null).apply()
        _speakerScores.value = emptyMap()
        _speaker.value = _speaker.value.copy(enrolled = emptyList())
        _speakerNote.value = "Enrollment cleared."
    }

    fun setSpeakerThreshold(v: Float) {
        val t = v.coerceIn(0.5f, 0.95f)
        _speaker.value = _speaker.value.copy(threshold = t)
        prefs.edit().putFloat("speaker_threshold", t).apply()
    }

    /** Decode the session WAV once if it is not on the phone yet. */
    private fun ensureSessionWav(s: Int): java.io.File? {
        val out = wavFileFor(s)
        if (out.exists() && out.length() > 44L) return out
        val packets = collectPackets(s)
        if (packets.isEmpty()) return null
        OpusDecoder.decodeToWav(packets, out)
        return out
    }

    fun enrollSession(s: Int) {
        if (!_speaker.value.loaded) { _speakerNote.value = "Load the voice model file first."; return }
        if (speakerJob?.isActive == true) return
        speakerStop = false
        // The icon flips at once; the voiceprint work runs in the background
        // and the enrollment is reverted if it cannot finish.
        _speaker.value = _speaker.value.copy(enrolled = (speakerVecs.keys + s).sorted())
        fun failEnroll(msg: String) {
            speakerVecs.remove(s)
            _speaker.value = _speaker.value.copy(enrolled = speakerVecs.keys.sorted())
            _speakerNote.value = msg
        }
        speakerJob = scope.launch(Dispatchers.IO) {
            try {
                _speakerNote.value = "Decoding session $s…"
                val wav = ensureSessionWav(s) ?: run {
                    failEnroll("No audio found for session $s."); return@launch }
                _speakerNote.value = "Reading the voiceprint from session $s…"
                val samples = wavToSamples(wav)
                val v = embedWindows(samples) ?: run {
                    failEnroll("Not enough speech in session $s to enroll; try one where you talk for a minute."); return@launch }
                var vn = 0f
                for (x in v) vn += x * x
                if (!vn.isFinite() || vn <= 0f) {
                    failEnroll("The voiceprint from session $s came out empty; try a session where you talk for a minute."); return@launch }
                speakerVecs[s] = v
                writeFloats(java.io.File(speakerDir(), "vec_$s.bin"), v)
                recomputeCentroid()
                prefs.edit().putString("speaker_enrolled", speakerVecs.keys.sorted().joinToString(",")).apply()
                _speaker.value = _speaker.value.copy(enrolled = speakerVecs.keys.sorted())
                _speakerNote.value = "Enrolled session $s. One is enough; more sessions make the voiceprint surer."
                rescoreAfterEnrollChange()
            } catch (e: Exception) {
                failEnroll("Enroll failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Removes this session's voice enrollment (the person-minus icon). Audio,
     *  transcript and playback are untouched. If it was the only enrolled session,
     *  scoring is reset until something is enrolled again. */
    fun unenrollSession(s: Int) {
        if (!speakerVecs.containsKey(s)) {
            _speakerNote.value = "Your voice wasn't enrolled from session $s."
            return
        }
        java.io.File(speakerDir(), "vec_$s.bin").delete()
        speakerVecs.remove(s)
        recomputeCentroid()
        val enrolled = speakerVecs.keys.sorted()
        prefs.edit()
            .putString("speaker_enrolled", if (enrolled.isEmpty()) null else enrolled.joinToString(","))
            .apply()
        _speaker.value = _speaker.value.copy(enrolled = enrolled)
        _speakerScores.value = _speakerScores.value - s
        _speakerNote.value = if (enrolled.isEmpty())
            "Removed your voice from session $s. No sessions enrolled now; tap person+ on one to enroll again."
        else
            "Removed your voice from session $s."
        if (enrolled.isNotEmpty()) rescoreAfterEnrollChange()
    }

    fun scoreSessions(force: Boolean = false) {
        if (!_speaker.value.loaded) { _speakerNote.value = "Load the voice model file first."; return }
        if (speakerVecs.isEmpty()) { _speakerNote.value = "Enroll a session of your voice first."; return }
        if (speakerJob?.isActive == true) return
        val c = speakerCentroid ?: return
        val thr = _speaker.value.threshold
        speakerStop = false
        speakerJob = scope.launch(Dispatchers.IO) { scoreAll(force, c, thr) }
    }

    /** Scores every session against the current voiceprint without the busy
     *  guard, so it can run the moment an enrollment change finishes (the
     *  enroll job is still the active speakerJob at that point). */
    fun rescoreAfterEnrollChange() {
        val c = speakerCentroid ?: return
        val thr = _speaker.value.threshold
        speakerStop = false
        scope.launch(Dispatchers.IO) { scoreAll(false, c, thr) }
    }

    private fun scoreAll(force: Boolean, c: FloatArray, thr: Float) {
        run {
            try {
                val list = _sessions.value
                if (list.isEmpty()) { _speakerNote.value = "No sessions on the phone yet; sync first."; return }
                val res = mutableMapOf<Int, SessionVoice>()
                var i = 0
                var skipped = 0
                var skippedScored = 0
                val enrollVersion = prefs.getInt("speaker_enroll_version", 1)
                for (si in list) {
                    if (speakerStop) return
                    // Short or empty recordings never carry enough of the wearer's
                    // voice to score meaningfully; leave them out entirely.
                    if (shortSession(si, _minSessionSec.value) || si.pages <= 1) { skipped++; continue }
                    // Already scored against this voiceprint: keep the saved
                    // score instead of redoing the model work.
                    if (!force) {
                        val tf = tagsFile(si.session)
                        if (tf.exists()) {
                            val storedEv = try {
                                org.json.JSONObject(tf.readText()).optInt("ev", 0)
                            } catch (_: Exception) { 0 }
                            if (storedEv == enrollVersion) { skippedScored++; continue }
                        }
                    }
                    i++
                    _speakerNote.value = "Scoring session ${si.session} ($i of ${list.size})…"
                    try {
                        val wav = ensureSessionWav(si.session) ?: continue
                        val samples = wavToSamples(wav)
                        val w = 48000; val hop = 24000
                        val starts = mutableListOf<Int>(); val scores = mutableListOf<Float>()
                        var st = 0
                        while (st + w <= samples.size) {
                            var sum = 0.0
                            for (k in st until st + w step 4) { val g = samples[k]; sum += g * g }
                            if (sum / (w / 4) > 0.005 * 0.005) {
                                val v = SpeakerVerifier.embed(samples.copyOfRange(st, st + w))
                                if (v != null) { starts.add(st / 16000); scores.add(SpeakerVerifier.cosine(v, c)) }
                            }
                            st += hop
                        }
                        if (scores.isEmpty()) continue
                        val you = scores.count { it >= thr }
                        res[si.session] = SessionVoice(si.session, you * 100 / scores.size, scores.size, scores.size)
                        val sb = StringBuilder()
                        sb.append("{\"ev\":").append(enrollVersion).append(",\"win\":3.0,\"hop\":1.5,\"thr\":").append(thr).append(",\"w\":[")
                        starts.indices.forEach { j ->
                            if (j > 0) sb.append(",")
                            sb.append("[").append(starts[j]).append(",").append(scores[j]).append("]")
                        }
                        sb.append("]}")
                        tagsFile(si.session).writeText(sb.toString())
                    } catch (e: Exception) { /* skip this session */ }
                }
                _speakerScores.value = res
                _speakerNote.value = "Scored ${res.size} sessions against your voiceprint" +
                        (if (skipped > 0 || skippedScored > 0) " (skipped $skipped short or empty, $skippedScored already scored)." else ".")
                _insightsVersion.value += 1
            } catch (e: Exception) {
                _speakerNote.value = "Scoring failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Rebuilds the speaker scores from the saved per-session tags files, so
     *  scored sessions survive an app restart instead of only living in RAM. */
    private fun reloadScoresFromTags() {
        val allow = _sessions.value
                .filter { !shortSession(it, _minSessionSec.value) && it.pages > 1 }
                .map { it.session }.toSet()
        val res = mutableMapOf<Int, SessionVoice>()
        var stale = 0
        val curEv = prefs.getInt("speaker_enroll_version", 1)
        val dir = java.io.File(getApplication<Application>().filesDir, "transcripts")
        dir.listFiles()?.forEach { f ->
            val m = Regex("session_(\\d+)\\.tags\\.json").matchEntire(f.name) ?: return@forEach
            try {
                val o = org.json.JSONObject(f.readText())
                // A tags file scored against an earlier voiceprint no longer
                // describes the current one; hide it instead of showing
                // numbers that look current but are not.
                if (o.optInt("ev", 0) != curEv) { stale++; return@forEach }
                val thr = prefs.getFloat("speaker_threshold", 0.75f).toDouble()
                val arr = o.getJSONArray("w")
                if (arr.length() == 0) return@forEach
                var you = 0
                for (j in 0 until arr.length()) {
                    if (arr.getJSONArray(j).getDouble(1) >= thr) you++
                }
                val s = m.groupValues[1].toInt()
                if (s !in allow) return@forEach
                res[s] = SessionVoice(s, you * 100 / arr.length(), arr.length(), arr.length())
            } catch (_: Exception) {}
        }
        _speakerScores.value = res
        _insightsVersion.value += 1
        if (res.isEmpty() && stale > 0)
            _speakerNote.value = "$stale sessions were scored against an earlier voiceprint; tap Score new sessions to update them."
    }

    private val _insightsBusy = MutableStateFlow(false)
    val insightsBusy: StateFlow<Boolean> = _insightsBusy
    private val _insightsVersion = MutableStateFlow(0)
    val insightsVersion: StateFlow<Int> = _insightsVersion

    /** "Insights now": refresh the session list, reload every saved score from
     *  disk, and if nothing has ever been scored, run a full scoring pass so the
     *  card fills without a separate trip to the voice card. */
    fun insightsNow() {
        if (_insightsBusy.value) return
        _insightsBusy.value = true
        scope.launch(Dispatchers.IO) {
            try {
                refreshSessions()
                reloadScoresFromTags()
                loadReflections(force = true)
                val hadScores = _speakerScores.value.isNotEmpty()
                if (!hadScores && _speaker.value.loaded && speakerVecs.isNotEmpty()) {
                    _insightsBusy.value = false
                    scoreSessions()
                    return@launch
                }
                _decodeNote.value = if (hadScores) "Insights refreshed from ${_speakerScores.value.size} scored sessions."
                                    else "Nothing scored yet: load the voice model, enroll a session, then try Insights now again."
            } catch (e: Exception) {
                _decodeNote.value = "Insights refresh failed: ${e.message}"
            } finally {
                _insightsBusy.value = false
            }
        }
    }

    // ---- Reflection read-back: the nightly PDI review writes reflection and
    // ---- review JSON files into the same Drive folder the app mirrors
    // ---- transcripts to; this reads the newest ones back onto the card.
    data class Reflection(
        val kind: String, val label: String, val date: String, val summary: String,
        val wins: List<String>, val improve: List<String>,
        val commitments: List<String>, val ifThen: String?,
    )

    private val _reflection = MutableStateFlow<Reflection?>(null)
    val reflection: StateFlow<Reflection?> = _reflection
    private val _reflectionReviews = MutableStateFlow<List<Reflection>>(emptyList())
    val reflectionReviews: StateFlow<List<Reflection>> = _reflectionReviews
    private val _reflectionNote = MutableStateFlow("")
    val reflectionNote: StateFlow<String> = _reflectionNote
    private var reflectionLastLoadMs = 0L

    private fun parseReflection(kind: String, label: String, text: String): Reflection? = try {
        val o = org.json.JSONObject(text)
        fun arr(k: String): List<String> {
            val a = o.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotEmpty() } }
        }
        val ifS = o.optString("if", "").trim().ifBlank { null }
        val thS = o.optString("then", "").trim().ifBlank { null }
        Reflection(kind, label,
            o.optString("date", "").ifBlank { o.optString("period", "") },
            o.optString("summary", "").trim(),
            arr("wins"), arr("improve"), arr("commitments"),
            if (ifS != null && thS != null) "If $ifS, then $thS." else null)
    } catch (_: Exception) { null }

    fun loadReflections(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - reflectionLastLoadMs < 60_000) return
        reflectionLastLoadMs = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val uriStr = prefs.getString("drive_tree_uri", null) ?: run {
                    _reflectionNote.value = "Pick the Drive folder in Settings first; reflections arrive there."
                    return@launch
                }
                val treeUri = android.net.Uri.parse(uriStr)
                val cr = getApplication<Application>().contentResolver
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                data class Hit(val name: String, val id: String)
                val hits = mutableListOf<Hit>()
                cr.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    val iId = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val iName = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val n = c.getString(iName) ?: continue
                        if (n.startsWith("reflection_") || n.startsWith("review_")) hits.add(Hit(n, c.getString(iId)))
                    }
                }
                if (hits.isEmpty()) {
                    _reflection.value = null
                    _reflectionReviews.value = emptyList()
                    _reflectionNote.value = "No reflections in the Drive folder yet."
                    return@launch
                }
                val sorted = hits.sortedByDescending { it.name }
                fun read(h: Hit): String? = try {
                    cr.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, h.id))
                        ?.use { ins -> ins.readBytes().decodeToString() }
                } catch (_: Exception) { null }
                val daily = sorted.firstOrNull { it.name.startsWith("reflection_") }
                _reflection.value = daily?.let { h ->
                    read(h)?.let { t -> parseReflection("daily", h.name.removePrefix("reflection_").removeSuffix(".json"), t) }
                }
                val reviews = mutableListOf<Reflection>()
                for (k in listOf("year", "quarter", "half", "month", "week")) {
                    sorted.firstOrNull { it.name.startsWith("review_" + k + "_") }?.let { h ->
                        read(h)?.let { t -> parseReflection(k, h.name.removePrefix("review_").removeSuffix(".json"), t) }?.let { reviews.add(it) }
                    }
                }
                _reflectionReviews.value = reviews
                _reflectionNote.value = if (_reflection.value != null) "" else "No daily reflection file found yet."
            } catch (e: Exception) {
                _reflectionNote.value = "Could not read reflections: ${e.message}"
            }
        }
    }

    /** True when the transcript line at sec seconds scores as your voice. Sessions never scored stay in. */
    fun lineIsYou(session: Int, sec: Double): Boolean {
        val f = tagsFile(session)
        if (!f.exists()) return true
        return try {
            val o = org.json.JSONObject(f.readText())
            val arr = o.getJSONArray("w")
            val thr = o.getDouble("thr")
            val win = o.getDouble("win")
            var best = -1.0
            for (j in 0 until arr.length()) {
                val pa = arr.getJSONArray(j)
                val st = pa.getDouble(0)
                if (sec >= st && sec < st + win) best = maxOf(best, pa.getDouble(1))
            }
            best < 0 || best >= thr
        } catch (e: Exception) { true }
    }

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    val rawSessions: StateFlow<List<SessionInfo>> = _sessions

    // Minimum recording length: sessions shorter than this (when their length is
    // known) are hidden from the recordings list, skipped by the transcription
    // batch and left out of the insights card. The audio itself stays on the phone.
    private val _minSessionSec = MutableStateFlow(prefs.getInt("min_session_sec", 78))
    val minSessionSec: StateFlow<Int> = _minSessionSec
    fun setMinSessionSec(sec: Int) {
        val v = sec.coerceIn(0, 3600)
        _minSessionSec.value = v
        prefs.edit().putInt("min_session_sec", v).apply()
    }
    /** True when the session's length is known and under the minimum. */
    private fun shortSession(s: SessionInfo, min: Int): Boolean =
        s.durationSec in 1 until min

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
                            if (_autoSync.value && syncJob?.isActive != true) {
                                try { syncReadOnly(fromAutoSync = true) } catch (_: Exception) {}
                            }
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
    fun syncReadOnly(fromAutoSync: Boolean = false) {
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
                val ack = ackMode.value
                // Progress is throttled to one emission every 500 ms instead
                // of one per page, which used to drown the main thread during
                // a multi-thousand-page sync.
                var lastEmit = 0L
                fun prog(p: Int, fr: Long, by: Long, idx: Int, msg: String) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEmit >= 500) {
                        lastEmit = now
                        syncProgress.value = SyncProgress(p, fr, by, idx, false, msg)
                    }
                }
                syncProgress.value = SyncProgress(0, 0, 0, -1, false, "starting download mode")
                s.storageListener = { buf ->
                    val page = buf.flashPage.toByteArray()
                    if (page.isNotEmpty()) {
                        val fp = FlashPageParser.fromStorageBuffer(
                            buf.ingestTypeValue, buf.session, buf.run, buf.seq,
                            buf.index, page)
                        val f = java.io.File(dir, "page_%08d_session%d.bin".format(fp.index, fp.session))
                        var ackThisPage = false
                        if (f.exists()) {
                            // already on this phone from an earlier sync: keep the
                            // original file, only count the transfer
                            skipped++
                            ackThisPage = true
                            prog(pages, frames, bytes, lastIdx,
                                "page ${fp.index} already on this phone, skipping (${skipped} so far)")
                        } else {
                            if (ack) {
                                // Acknowledge mode erases the pendant copy once a
                                // page is durably saved, so it keeps the fsync.
                                java.io.FileOutputStream(f).use { fos ->
                                    fos.write(fp.rawPageBytes)
                                    fos.fd.sync()
                                }
                                ackThisPage = f.length() == fp.rawPageBytes.size.toLong()
                            } else {
                                // Read-only fast path: buffered write + atomic
                                // rename, no per-page fsync. A crash can lose a
                                // page but never half-write one, and the pendant
                                // keeps its own copy regardless.
                                val tmp = java.io.File(dir, f.name + ".part")
                                java.io.FileOutputStream(tmp).use { it.write(fp.rawPageBytes) }
                                if (!tmp.renameTo(f)) {
                                    tmp.delete()
                                    java.io.FileOutputStream(f).use { it.write(fp.rawPageBytes) }
                                }
                                ackThisPage = true
                            }
                            pages++
                            frames += fp.opusFrames.size
                            bytes += fp.rawPageBytes.size
                            lastIdx = fp.index
                            prog(pages, frames, bytes, lastIdx,
                                "page ${fp.index} (${fp.rawPageBytes.size} B, ${fp.opusFrames.size} frames)")
                        }
                        if (ack && ackThisPage) scope.launch(Dispatchers.IO) {
                            ackMutex.withLock {
                                try { s.deleteFlashPageUpTo(fp.index) } catch (_: Exception) {}
                            }
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
                else if (ack)
                    "acknowledged ${pages + skipped} pages; pendant erases them, this phone now holds the only copy"
                else if (skipped > 0)
                    "saved $pages new pages, skipped $skipped already on this phone, pendant copy untouched (no ack sent)"
                else
                    "saved $pages pages, pendant copy untouched (no ack sent)"
                if (ack && (pages > 0 || skipped > 0)) {
                    try {
                        val n = kotlinx.coroutines.withContext(Dispatchers.IO) { buildBackupZip(ctx) }
                        note += "; backup zip rebuilt ($n files)"
                    } catch (e: Exception) {
                        note += "; WARNING: backup zip failed (${e.message}) — acknowledged pages exist only on this phone until an export succeeds"
                    }
                }
                if (fromAutoSync && pages > 0) {
                    try {
                        val n = kotlinx.coroutines.withContext(Dispatchers.IO) { buildBackupZip(ctx) }
                        note += "; auto-backup zip rebuilt ($n files)"
                    } catch (e: Exception) {
                        note += "; auto-backup failed (${e.message})"
                    }
                }
                syncProgress.value = SyncProgress(pages, frames, bytes, lastIdx, true, note)
                if (autoTranscribe.value && !clearAllInProgress && (pages > 0 || skipped > 0)) {
                    autoPipelineAfterSync()
                }
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
                    val note2 = if (ackMode.value)
                        "after sync: pendant flash now ${st2.oldestPage}..${st2.newestPage} (ack mode: erased pages means acknowledged)"
                    else
                        "after sync: pendant flash now ${st2.oldestPage}..${st2.newestPage} (unchanged = success)"
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

    /** Saved recordings list, so it survives app restarts instead of emptying until the next sync. */
    private fun sessionsIndexFile(): java.io.File =
        java.io.File(getApplication<Application>().filesDir, "sessions_index.json")

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
                reloadScoresFromTags()
                try {
                    val arr = org.json.JSONArray()
                    list.forEach { s ->
                        arr.put(org.json.JSONObject()
                            .put("session", s.session).put("pages", s.pages)
                            .put("frames", s.frames).put("durationSec", s.durationSec)
                            .put("startMs", s.startMs))
                    }
                    sessionsIndexFile().writeText(arr.toString())
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _player.value = PlayerState.PlayerError("Could not list recordings: ${e.message}")
            }
        }
    }

    /** Delete one recording: its saved flash pages plus every derived file. */
    fun deleteSession(s: Int) {
        val cur = _player.value
        if ((cur is PlayerState.Playing && cur.session == s) ||
            (cur is PlayerState.Paused && cur.session == s)) {
            scope.launch(Dispatchers.Main) { stopPlayback() }
        }
        scope.launch(Dispatchers.IO) {
            try {
                flashPagesDir().listFiles()?.forEach { f ->
                    if (f.name.endsWith("_session$s.bin")) f.delete()
                }
                wavFileFor(s).delete()
                transcriptFile(s).delete()
                tagsFile(s).delete()
                java.io.File(speakerDir(), "vec_$s.bin").delete()
                speakerVecs.remove(s)
                recomputeCentroid()
                val enrolled = speakerVecs.keys.sorted()
                prefs.edit()
                    .putString("speaker_enrolled", if (enrolled.isEmpty()) null else enrolled.joinToString(","))
                    .apply()
                _speaker.value = _speaker.value.copy(enrolled = enrolled)
                _speakerScores.value = _speakerScores.value - s
                refreshSessions()
                _speakerNote.value = "Deleted session $s."
            } catch (e: Exception) {
                _speakerNote.value = "Could not delete session $s: ${e.message}"
            }
        }
    }

    /** Delete every session shorter than the minimum length (only ones whose
     *  length is known; unknown-length sessions are never touched). */
    fun deleteShortSessions(min: Int) {
        if (_cleaning.value || BatchRunner.job?.isActive == true) return
        val targets = _sessions.value.filter { shortSession(it, min) }.map { it.session }
        if (targets.isEmpty()) return
        _cleaning.value = true
        scope.launch(Dispatchers.IO) {
            var removed = 0
            try {
                val cur = _player.value
                val curSess = when (cur) {
                    is PlayerState.Playing -> cur.session
                    is PlayerState.Paused -> cur.session
                    else -> -1
                }
                for (s in targets) {
                    if (curSess == s) {
                        scope.launch(Dispatchers.Main) { stopPlayback() }
                    }
                    removeSessionFiles(s)
                    _speakerScores.value = _speakerScores.value - s
                    removed++
                }
                recomputeCentroid()
                val enrolled = speakerVecs.keys.sorted()
                prefs.edit()
                    .putString("speaker_enrolled", if (enrolled.isEmpty()) null else enrolled.joinToString(","))
                    .apply()
                _speaker.value = _speaker.value.copy(enrolled = enrolled)
                refreshSessions()
                _speakerNote.value = "Deleted $removed short recording${if (removed == 1) "" else "s"}."
            } catch (e: Exception) {
                _speakerNote.value = "Could not delete short recordings: ${e.message}"
            } finally {
                _cleaning.value = false
            }
        }
    }

    /** Scan every recording and delete sessions with no speech-like audio at all. */
    fun cleanEmptySessions() {
        if (_cleaning.value || _sessions.value.isEmpty()) return
        _cleaning.value = true
        scope.launch(Dispatchers.IO) {
            var removed = 0
            var kept = 0
            val removedIds = mutableListOf<Int>()
            try {
                val cur = _player.value
                val curSess = when (cur) {
                    is PlayerState.Playing -> cur.session
                    is PlayerState.Paused -> cur.session
                    else -> -1
                }
                val list = _sessions.value
                _decodeNote.value = "Scanning ${list.size} recordings for empty sessions..."
                val ctx = kotlinx.coroutines.currentCoroutineContext()
                for (s in list) {
                    if (!ctx.isActive) break
                    if (s.session == curSess) { kept++; continue }
                    try {
                        val wav = wavFileFor(s.session)
                        if (!wav.exists() || wav.length() <= 44L) {
                            val packets = collectPackets(s.session)
                            if (packets.isEmpty()) {
                                removeSessionFiles(s.session); removed++; removedIds.add(s.session)
                                continue
                            }
                            OpusDecoder.decodeToWav(packets, wav, keepGoing = { ctx.isActive })
                        }
                        val voiced = voicedWindows(wav)
                        val secs = wavSeconds(wav)
                        // Empty means no real transcript AND either no audible
                        // audio at all or a recording too short to hold speech
                        // (under 5 seconds): one-page stubs decode to a second
                        // or two of room noise that trips the RMS floor.
                        if (!hasRealTranscript(s.session) && (voiced == 0 || secs < 5.0)) {
                            removeSessionFiles(s.session)
                            removed++; removedIds.add(s.session)
                        } else kept++
                    } catch (_: Exception) { kept++ }
                }
                recomputeCentroid()
                val enrolled = speakerVecs.keys.sorted()
                prefs.edit()
                    .putString("speaker_enrolled", if (enrolled.isEmpty()) null else enrolled.joinToString(","))
                    .apply()
                _speaker.value = _speaker.value.copy(enrolled = enrolled)
                removedIds.forEach { id -> _speakerScores.value = _speakerScores.value - id }
                refreshSessions()
                _decodeNote.value = when {
                    removed == 0 -> "No empty recordings found: all $kept sessions carry speech."
                    else -> "Removed $removed empty recording${if (removed == 1) "" else "s"}; kept $kept with speech."
                }
            } catch (e: Exception) {
                _decodeNote.value = "Empty-recording scan failed: ${e.message}"
            } finally {
                _cleaning.value = false
            }
        }
    }

    private fun removeSessionFiles(s: Int) {
        flashPagesDir().listFiles()?.forEach { f ->
            if (f.name.endsWith("_session$s.bin")) f.delete()
        }
        wavFileFor(s).delete()
        transcriptFile(s).delete()
        tagsFile(s).delete()
        java.io.File(speakerDir(), "vec_$s.bin").delete()
        speakerVecs.remove(s)
    }

    /** Length of a decoded WAV in seconds (16-bit mono at OpusDecoder.SAMPLE_RATE). */
    private fun wavSeconds(wav: java.io.File): Double {
        if (wav.length() <= 44L) return 0.0
        return (wav.length() - 44L) / (OpusDecoder.SAMPLE_RATE * 2.0)
    }

    /** True when a session's transcript has real words, not just silence markers. */
    private fun hasRealTranscript(s: Int): Boolean {
        val txt = transcriptFile(s)
        if (!txt.exists()) return false
        return try {
            txt.readText()
                .replace("[BLANK_AUDIO]", "").replace("[Silence]", "")
                .replace("[inaudible]", "").replace("[music]", "").trim()
                .isNotEmpty()
        } catch (_: Exception) { true }
    }

    /** Counts 1-second loudness blocks above the speech floor (RMS > 0.02) in a 16-bit mono WAV. */
    private fun voicedWindows(wav: java.io.File): Int {
        if (!wav.exists() || wav.length() <= 44L) return 0
        var voiced = 0
        val buf = ByteArray(96000)
        try {
            java.io.BufferedInputStream(java.io.FileInputStream(wav), 1 shl 16).use { ins ->
                ins.skip(44L)
                while (true) {
                    var off = 0
                    while (off < buf.size) {
                        val n = ins.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off == 0) break
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < off) {
                        val v = (((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort()).toDouble() / 32768.0
                        sum += v * v
                        i += 2
                    }
                    val rms = kotlin.math.sqrt(sum / (off / 2))
                    if (rms > 0.02) voiced++
                    if (off < buf.size) break
                }
            }
        } catch (_: Exception) { return -1 }
        return voiced
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
                        _player.value = PlayerState.PlayerError(noAudioDiagnosis(session))
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
                        _player.value = PlayerState.PlayerError(noAudioDiagnosis(session))
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


    // ----- pendant storage fullness + safe free-space -----
    private val _storageView = MutableStateFlow<StorageView?>(null)
    val storageView: StateFlow<StorageView?> = _storageView
    private val _storageNote = MutableStateFlow<String?>(null)
    val storageNote: StateFlow<String?> = _storageNote

    /** Read the pendant's storage state for the storage card. */
    fun refreshStorage() {
        val s = session ?: return
        scope.launch(Dispatchers.IO) {
            try { _storageView.value = storageStatus(s) }
            catch (_: Exception) { /* keep the last reading on the card */ }
        }
    }

    /**
     * Erase every pendant page this phone already holds: delete up to the
     * highest page index saved locally. Unsynced recordings stay on the
     * pendant. Acknowledge mode does this continuously; this button is the
     * manual, checked version for read-only mode.
     */
    fun eraseSyncedPages() {
        val s = session ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "flash_pages")
                val re = Regex("page_(\\d+)_session\\d+\\.bin")
                val maxSaved = dir.listFiles { f -> f.name.startsWith("page_") }
                    ?.mapNotNull { re.find(it.name)?.groupValues?.get(1)?.toInt() }
                    ?.maxOrNull()
                    ?: throw IllegalStateException("no pages saved on this phone yet")
                s.deleteFlashPageUpTo(maxSaved)
                kotlinx.coroutines.delay(600)
                _storageView.value = storageStatus(s)
                _storageNote.value = "erased pendant pages up to $maxSaved (all already saved on this phone)"
            } catch (e: Exception) {
                _storageNote.value = "could not erase: ${e.message}"
            }
        }
    }

    @Volatile private var clearAllInProgress = false

    /** Clear everything: full read-only sync first so nothing is lost, then
     *  erase every pendant page already saved on this phone, delete all local
     *  recordings, exports, transcripts and backup zips (the Whisper model is
     *  kept), and delete every mirrored transcript copy in the Drive folder.
     *  Recordings that failed to sync stay on the pendant. The Limitless
     *  cloud itself is untouched (the app has no internet permission). */
    fun clearEverything() {
        val s = session
        if (s == null) {
            _trans.value = TransState.Done(-1, "Connect the pendant first, then tap Clear everything.")
            return
        }
        clearAllInProgress = true
        scope.launch(Dispatchers.IO) {
            val notes = mutableListOf<String>()
            try {
                if (syncJob?.isActive != true) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        syncProgress.value = SyncProgress(0, 0, 0, -1, false,
                            "clear all: syncing first so nothing is lost")
                        syncReadOnly()
                    }
                    while (syncJob?.isActive == true) kotlinx.coroutines.delay(500)
                    notes.add("final sync done")
                } else {
                    notes.add("used the sync already running")
                }
                try {
                    val re = Regex("page_(\\d+)_session\\d+\\.bin")
                    val maxSaved = flashPagesDir()
                        .listFiles { f -> f.name.startsWith("page_") }
                        ?.mapNotNull { re.find(it.name)?.groupValues?.get(1)?.toInt() }?.maxOrNull()
                    if (maxSaved != null) {
                        s.deleteFlashPageUpTo(maxSaved)
                        kotlinx.coroutines.delay(600)
                        notes.add("pendant erased up to page $maxSaved")
                    } else {
                        notes.add("nothing on the pendant to erase")
                    }
                } catch (e: Exception) {
                    notes.add("pendant erase failed: ${e.message}")
                }
                var files = 0
                for (name in listOf("flash_pages", "exports", "transcripts", "backups", "backup")) {
                    val d = java.io.File(getApplication<Application>().filesDir, name)
                    d.listFiles()?.forEach { if (it.deleteRecursively()) files++ }
                }
                getApplication<Application>().filesDir
                    .listFiles()?.forEach { f -> if (f.name.endsWith(".zip") && f.delete()) files++ }
                notes.add("$files local files deleted")
                val wiped = wipeDriveTranscripts()
                notes.add("$wiped Drive copies deleted")
            } catch (e: Exception) {
                notes.add("stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                clearAllInProgress = false
                refreshSessions()
                try { _storageView.value = storageStatus(s) } catch (_: Exception) {}
                _trans.value = TransState.Done(-1, "Clear all: " + notes.joinToString("; ") + ".")
            }
        }
    }

    /** Factory reset: sends FactoryResetPendant, wiping the pendant's ENTIRE
     *  flash including anything not yet synced. The pendant may reset its
     *  keys, so re-pairing may be needed afterwards. */
    fun factoryResetPendant() {
        val s = session ?: run {
            _storageNote.value = "connect the pendant first"
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                s.factoryResetPendant()
                kotlinx.coroutines.delay(1000)
                _storageNote.value = "factory reset command sent: the pendant wipes its flash and may restart. If the connection drops, re-pair from the app."
                try { _storageView.value = storageStatus(s) } catch (_: Exception) {}
            } catch (e: Exception) {
                _storageNote.value = "factory reset failed: ${e.message}"
            }
        }
    }

    /** Deletes every mirrored transcript copy in the picked Drive folder. */
    private fun wipeDriveTranscripts(): Int {
        var n = 0
        try {
            val uriStr = prefs.getString("drive_tree_uri", null) ?: return 0
            val treeUri = android.net.Uri.parse(uriStr)
            val cr = getApplication<Application>().contentResolver
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val victims = mutableListOf<android.net.Uri>()
            cr.query(childrenUri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val iId = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    if (Regex("^\\d{4}-\\d{2}-\\d{2}_session-\\d+\\.txt$").matchEntire(name) != null) {
                        victims.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(iId)))
                    }
                }
            }
            for (u in victims) {
                try { if (android.provider.DocumentsContract.deleteDocument(cr, u)) n++ } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return n
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
                val h = WhisperBridge.nativeInit(dst.absolutePath)
                val ok = h != 0L
                _model.value = ModelInfo(dst.absolutePath, dst.length(), ok)
                if (ok) WhisperBridge.nativeFree(h) // validation load only; real passes open their own context
                if (!ok) {
                    _trans.value = TransState.TransError(-1, "Whisper did not load that file; is it a ggml .bin model?")
                }
            } catch (e: Exception) {
                _trans.value = TransState.TransError(-1, "Model import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** One stitched Whisper segment: [t] is the real offset in seconds. */
    private data class TransSeg(val t: Int, val text: String)

    /**
     * Transcribe 16 kHz mono samples in bounded 30 s windows with a 1 s
     * overlap, reporting progress per window and honouring stopTranscribe().
     * One giant Whisper pass is what exhausted memory and froze the app on
     * longer sessions; each window's native buffers are small no matter how
     * long the session is. Each Whisper call is independent (no cross-window
     * context carry-over), which also breaks the repetition loops that long
     * stretches of radio or music used to fall into. Segment stamps are
     * rebased to the whole session, and the 1 s overlap's duplicated words
     * are dropped at each seam, so the joined transcript reads like the
     * old single-pass one.
     */
    private fun transcribeChunked(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        onNote: (String, Float) -> Unit,
    ): String {
        if (samples.isEmpty()) return ""
        if (handle == 0L) throw IllegalStateException("Whisper context not loaded")
        val chunk = 480_000    // 30 s at 16 kHz
        val overlap = 16_000   // 1 s
        val minTail = 80_000   // fold a remainder shorter than 5 s into the last window
        val step = chunk - overlap
        val approxTotal = if (samples.size <= chunk) 1 else (samples.size + step - 1) / step
        val segs = mutableListOf<TransSeg>()
        var start = 0
        var idx = 0
        while (start < samples.size) {
            if (transCancel.get()) throw IllegalStateException("stopped by user")
            idx++
            var end = minOf(start + chunk, samples.size)
            if (end < samples.size && samples.size - end < minTail) end = samples.size
            onNote("transcribing chunk $idx of ~$approxTotal", idx.toFloat() / approxTotal)
            val piece = java.util.Arrays.copyOfRange(samples, start, end)
            // A full Whisper pass costs the same on dead air as on speech (the
            // encoder dominates), so near-silent windows are skipped outright.
            var energy = 0.0
            for (v in piece) energy += v.toDouble() * v
            val rms = kotlin.math.sqrt(energy / piece.size)
            if (rms < 0.0015) {
                onNote("chunk $idx: silence, skipped", idx.toFloat() / approxTotal)
                if (end >= samples.size) break
                start += step
                continue
            }
            val t0 = System.currentTimeMillis()
            val raw = WhisperBridge.nativeTranscribe(handle, piece, threads) ?: ""
            val tookSec = (System.currentTimeMillis() - t0) / 1000
            onNote("chunk $idx of ~$approxTotal done in ${tookSec}s", idx.toFloat() / approxTotal)
            val offset = start / 16000.0
            val newSegs = parseSegments(raw, offset)
            if (segs.isNotEmpty() && newSegs.isNotEmpty()) {
                // The 1 s overlap can make the first window of the next chunk
                // repeat the words the previous chunk already ended with.
                val prevWords = segs.last().text.split(Regex("\\s+"))
                val first = newSegs[0]
                val words = first.text.split(Regex("\\s+"))
                var best = 0
                for (k in minOf(prevWords.size, words.size, 8) downTo 1) {
                    if (words.take(k) == prevWords.takeLast(k)) { best = k; break }
                }
                if (best > 0) {
                    val rest = words.drop(best).joinToString(" ").trim()
                    if (rest.isBlank()) newSegs.removeAt(0) else newSegs[0] = first.copy(text = rest)
                }
            }
            for (sg in newSegs) if (sg.text.isNotBlank()) segs.add(sg)
            if (end >= samples.size) break
            start += step
        }
        val sb = StringBuilder()
        for (sg in segs) {
            sb.append(String.format(java.util.Locale.US, "[%02d:%02d] ", sg.t / 60, sg.t % 60))
            sb.append(sg.text)
            sb.append('\n')
        }
        return sb.toString().trim()
    }

    /** "[mm:ss] text" lines relative to the window start, rebased to [offsetSec]. */
    private fun parseSegments(raw: String, offsetSec: Double): MutableList<TransSeg> {
        val out = mutableListOf<TransSeg>()
        val re = Regex("^\\[(\\d+):(\\d+)]\\s*(.*)$")
        for (line in raw.lines()) {
            val m = re.matchEntire(line.trim()) ?: continue
            val rel = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            val text = m.groupValues[3].trim()
            if (text.isNotEmpty()) out.add(TransSeg((offsetSec + rel).toInt(), text))
        }
        return out
    }

    fun transcribeSession(session: Int) {
        if (transJob?.isActive == true || BatchRunner.job?.isActive == true) return
        transJob = scope.launch(Dispatchers.Default) {
            var h = 0L
            try {
                val mf = modelFile()
                if (!mf.exists() || mf.length() < 10_000_000) {
                    _trans.value = TransState.TransError(session, "No Whisper model loaded. Tap Load model file first.")
                    return@launch
                }
                _trans.value = TransState.Running(session, "decoding audio")
                val wav = decodeSessionWav(session)
                _trans.value = TransState.Running(session, "loading Whisper model")
                h = WhisperBridge.nativeInit(mf.absolutePath)
                if (h == 0L) {
                    throw IllegalStateException(
                        "Whisper failed to load the model file (${mf.length() / 1_000_000} MB). " +
                            "Tap Load model file and pick it again; if it keeps failing, the copy on the phone may be damaged, so re-download it.")
                }
                _model.value = _model.value.copy(ready = true)
                _trans.value = TransState.Running(session, "transcribing")
                val samples = wavToSamples(wav)
                transCancel.set(false)
                val text = transcribeChunked(h, samples, Runtime.getRuntime().availableProcessors()) { note, _ ->
                    _trans.value = TransState.Running(session, note)
                }
                if (text.isBlank()) {
                    throw IllegalStateException("No speech detected in this session (only silence, music or radio)")
                }
                val tf = transcriptFile(session)
                tf.writeText(text)
                _trans.value = TransState.Done(session, text)
            } catch (e: Exception) {
                if (e.message?.contains("stopped by user") == true) {
                    _trans.value = TransState.TransError(session, "Transcription stopped.")
                } else {
                    _trans.value = TransState.TransError(session, "Transcription failed: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                if (h != 0L) WhisperBridge.nativeFree(h)
            }
        }
    }

    // ----- batch transcription: every session that holds audio, one tap -----
    data class BatchProgress(
        val done: Int, val total: Int, val session: Int, val failed: Int, val note: String,
        val fraction: Float = 0f,
    )

    val batch: StateFlow<BatchProgress?> = BatchRunner.state

    fun transcribeAllSessions(onlyNew: Boolean = false) {
        if (BatchRunner.job?.isActive == true) return
        val app = getApplication<Application>()
        // No previous batch running but a stale progress line left over? Clear it.
        if (BatchRunner.state.value != null) BatchRunner.state.value = null
        // The recordings list lives in memory until a sync fills it. Refuse to
        // start a batch (and its foreground service) against an empty list:
        // that used to park the notification at "0 of 0 (starting batch...)".
        if (_sessions.value.none { it.frames > 0 }) {
            if (onlyNew) return
            _trans.value = TransState.TransError(-1,
                "Nothing to transcribe yet: the recordings list is empty. Connect and sync the pendant first, then run the batch again.")
            return
        }
        BatchRunner.state.value = BatchProgress(0, 0, -1, 0, "starting batch...")
        BatchRunner.job = BatchRunner.scope.launch {
            try { TranscribeService.start(app) } catch (_: Exception) {}
            runBatch(onlyNew)
        }
    }

    private suspend fun runBatch(onlyNew: Boolean = false) {
        try {
            val mf = modelFile()
            if (!mf.exists() || mf.length() < 10_000_000) {
                _trans.value = TransState.TransError(-1, "No Whisper model loaded. Tap Load model file first.")
                try { TranscribeService.stop(getApplication()) } catch (_: Exception) {}
                return
            }
            val cores = Runtime.getRuntime().availableProcessors()
            // Several independent Whisper contexts transcribe different
            // sessions at once. Small models (tiny.en, ~32 MB) allow 3
            // workers; larger ones (base.en and up) use 2 to stay in memory.
            val workers = if (mf.length() > 50_000_000) 2 else 3
            val perWorker = maxOf(2, cores / workers)
            val minSec = _minSessionSec.value
            val targets = _sessions.value
                .filter { it.frames > 0 && !shortSession(it, minSec) &&
                    (!onlyNew || !transcriptFile(it.session).exists()) }
                .sortedBy { it.session }
            if (targets.isEmpty()) {
                if (onlyNew) {
                    BatchRunner.state.value = null
                    try { TranscribeService.stop(getApplication()) } catch (_: Exception) {}
                    return
                }
                _trans.value = TransState.TransError(-1,
                    "Nothing to transcribe yet: the recordings list is empty. Sync the pendant first.")
                BatchRunner.state.value = null
                try { TranscribeService.stop(getApplication()) } catch (_: Exception) {}
                return
            }
            val total = targets.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val failed = java.util.concurrent.atomic.AtomicInteger(0)
            val next = java.util.concurrent.atomic.AtomicInteger(0)
            // Sessions over ~20 min decode into a large float array; only one
            // of those is kept in memory at a time, smaller ones run freely.
            val bigLock = java.util.concurrent.Semaphore(1)
            val firstEmitted = java.util.concurrent.atomic.AtomicBoolean(false)
            BatchRunner.state.value = BatchProgress(0, total, -1, 0,
                "loading Whisper model x$workers in parallel")
            val loadStart = System.currentTimeMillis()
            val ticker = BatchRunner.scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(5_000)
                    val secs = (System.currentTimeMillis() - loadStart) / 1000
                    BatchRunner.state.value = BatchProgress(0, total, -1, 0,
                        "loading Whisper model x$workers (${secs}s elapsed)")
                }
            }
            transCancel.set(false)
            try {
                kotlinx.coroutines.coroutineScope {
                    repeat(workers) { w ->
                        launch(kotlinx.coroutines.Dispatchers.Default) {
                            val h = WhisperBridge.nativeInit(mf.absolutePath)
                            if (h == 0L) { failed.incrementAndGet(); return@launch }
                            _model.value = _model.value.copy(ready = true)
                            try {
                                while (true) {
                                    ensureActive()
                                    if (transCancel.get()) break
                                    val i = next.getAndIncrement()
                                    if (i >= total) break
                                    val s = targets[i]
                                    if (transcriptFile(s.session).exists()) { done.incrementAndGet(); continue }
                                    var lastNote = ""
                                    try {
                                        val big = s.durationSec > 1200
                                        if (big) bigLock.acquire()
                                        try {
                                            lastNote = "decoding audio"
                                            BatchRunner.state.value = BatchProgress(done.get(), total, s.session, failed.get(),
                                                "session ${s.session}: $lastNote")
                                            val wav = decodeSessionWav(s.session)
                                            lastNote = "transcribing"
                                            BatchRunner.state.value = BatchProgress(done.get(), total, s.session, failed.get(),
                                                "session ${s.session}: $lastNote")
                                            val samples = wavToSamples(wav)
                                            val text = transcribeChunked(h, samples, perWorker) { note, frac ->
                                                lastNote = note
                                                if (firstEmitted.compareAndSet(false, true)) ticker.cancel()
                                                BatchRunner.state.value = BatchProgress(done.get(), total, s.session, failed.get(),
                                                    "session ${s.session}: $note", (done.get() + frac) / total)
                                            }
                                            if (text.isBlank()) throw IllegalStateException("No speech detected in this session (only silence, music or radio)")
                                            saveTranscript(s.session, text)
                                            done.incrementAndGet()
                                        } finally { if (big) bigLock.release() }
                                    } catch (e: Throwable) {
                                        if (transCancel.get()) break
                                        failed.incrementAndGet()
                                        lastNote = "failed: ${e.message?.take(80) ?: e.javaClass.simpleName}"
                                    }
                                    BatchRunner.state.value = BatchProgress(done.get(), total, s.session, failed.get(),
                                        "session ${s.session}: $lastNote")
                                }
                            } finally {
                                WhisperBridge.nativeFree(h)
                            }
                        }
                    }
                }
            } finally { ticker.cancel() }
            BatchRunner.state.value = BatchProgress(done.get(), total, -1, failed.get(), "finished", 1f)
            kotlinx.coroutines.delay(30_000)
            BatchRunner.state.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            BatchRunner.state.value = null
            throw e
        } catch (e: Exception) {
            BatchRunner.state.value = BatchProgress(0, 0, -1, 0,
                "batch failed: ${e.message?.take(90) ?: e.javaClass.simpleName}")
            _trans.value = TransState.TransError(-1, "Batch transcription failed: ${e.message ?: e.javaClass.simpleName}")
        }
        TranscribeService.stop(getApplication())
    }

    fun stopBatch() {
        transCancel.set(true)
        BatchRunner.job?.cancel()
        BatchRunner.state.value = null
        try { TranscribeService.stop(getApplication()) } catch (_: Exception) {}
    }

    /** Stop an in-flight transcription (single or batch) between chunks. */
    private val transCancel = java.util.concurrent.atomic.AtomicBoolean(false)
    fun stopTranscribe() { transCancel.set(true) }

    /** One transcript match: session, the [mm:ss] stamp of the first hit, and matching lines. */
    data class SearchHit(val session: Int, val stamp: String, val snippet: String)
    data class SearchState(val query: String, val hits: List<SearchHit>, val searched: Boolean)

    private val _search = MutableStateFlow(SearchState("", emptyList(), false))
    val search: StateFlow<SearchState> = _search
    private var searchJob: Job? = null
    private var exportJob: Job? = null

    /**
     * Local-only keyword search over saved transcripts. No network, no index
     * file to maintain: transcripts are small, so each search re-scans them.
     */
    fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) { _search.value = SearchState("", emptyList(), false); return }
        lastQuery = query
        if (searchJob?.isActive == true) return
        searchJob = scope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(getApplication<Application>().filesDir, "transcripts")
                val hits = mutableListOf<SearchHit>()
                val onlyYou = _searchOnlyYou.value
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    val m = Regex("session_(\\d+)\\.txt").matchEntire(f.name) ?: return@forEach
                    val sess = m.groupValues[1].toInt()
                    val matches = mutableListOf<String>()
                    var stamp = ""
                    for (line in f.readLines()) {
                        if (line.contains(query, ignoreCase = true)) {
                            if (onlyYou) {
                                val sm = Regex("\\[(\\d+):(\\d+)\\]").find(line)
                                if (sm != null) {
                                    val sec = sm.groupValues[1].toInt() * 60 + sm.groupValues[2].toInt()
                                    if (!lineIsYou(sess, sec.toDouble())) continue
                                }
                            }
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

    /** Short opening snippet of a session's transcript for the recordings list. */
    fun transcriptPreview(session: Int, chars: Int = 220): String? =
        transcriptFile(session).takeIf { it.exists() }
            ?.readText()?.trim()
            ?.take(chars)?.let { if (it.length == chars) it.trimEnd() + "\u2026" else it }

    data class DetectedSession(val session: Int, val startMs: Long, val durationSec: Long,
        val youPct: Int, val words: Int, val fillers: Int, val excerpt: String?)
    data class VoicePeriodStat(val label: String, val minutes: Double, val sessions: Int,
        val words: Int, val fillers: Int, val fillerRate: Double)
    data class VoiceInsights(val detected: List<DetectedSession>, val days: List<VoicePeriodStat>,
        val months: List<VoicePeriodStat>, val hints: List<String>)

    /** Reads the latest speaker scores and transcripts and builds day/month stats for the insights card. */
    fun voiceInsights(): VoiceInsights {
        val scores = _speakerScores.value
        val fillerWords = listOf("um", "uh", "er", "ah", "hmm", "like", "you know",
            "sort of", "kind of", "basically", "actually", "literally")
        val det = mutableListOf<DetectedSession>()
        for (si in _sessions.value) {
            if (shortSession(si, _minSessionSec.value)) continue
            val sv = scores[si.session] ?: continue
            if (sv.youPct < 1) continue
            val txt = transcriptFile(si.session).takeIf { it.exists() }?.readText()?.trim()
            val words = txt?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0
            val lower = txt?.lowercase() ?: ""
            var fill = 0
            for (w in fillerWords) {
                val pat = if (w.contains(' ')) Regex("\\b" + Regex.escape(w) + "\\b") else Regex("\\b$w\\b")
                fill += pat.findAll(lower).count()
            }
            det.add(DetectedSession(si.session, si.startMs, si.durationSec, sv.youPct, words, fill,
                txt?.take(200)))
        }
        det.sortByDescending { it.startMs }
        val cal = java.util.Calendar.getInstance()
        fun dayKey(ms: Long): Long {
            cal.timeInMillis = ms
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        fun monthKey(ms: Long): Long {
            cal.timeInMillis = ms
            cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        fun fmt(ms: Long, pattern: String): String =
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ms))
        fun agg(keyOf: (Long) -> Long, labelOf: (Long) -> String): List<VoicePeriodStat> =
            det.filter { it.startMs > 0 }
                .groupBy { keyOf(it.startMs) }
                .entries.sortedByDescending { it.key }
                .map { (k, list) ->
                    val mins = list.sumOf { it.durationSec } / 60.0
                    val w = list.sumOf { it.words }
                    val f = list.sumOf { it.fillers }
                    VoicePeriodStat(labelOf(k), mins, list.size, w, f,
                        if (w > 0) f * 100.0 / w else 0.0)
                }
        val days = agg(::dayKey) { fmt(it, "d MMM yyyy") }
        val months = agg(::monthKey) { fmt(it, "MMM yyyy") }
        val hints = mutableListOf<String>()
        if (det.isNotEmpty()) {
            hints.add(
                "Across ${det.size} session${if (det.size == 1) "" else "s"} with your voice: " +
                    "${"%.0f".format(det.sumOf { it.durationSec } / 60.0)} min and ${det.sumOf { it.words }} words in total. " +
                    "Filler words counted: um, uh, er, ah, hmm, like, you know, sort of, kind of, basically, actually, literally.")
        }
        if (months.size >= 2) {
            val cur = months[0]; val prev = months[1]
            if (prev.fillerRate > 0.0 || cur.fillerRate > 0.0) {
                hints.add(
                    if (cur.fillerRate <= prev.fillerRate)
                        "Filler words this month: ${"%.1f".format(cur.fillerRate)} per 100 words, down from ${"%.1f".format(prev.fillerRate)} last month. Keep it up."
                    else
                        "Filler words this month: ${"%.1f".format(cur.fillerRate)} per 100 words, up from ${"%.1f".format(prev.fillerRate)} last month. Worth a watch."
                )
            }
        }
        return VoiceInsights(det, days, months, hints)
    }

    /** Import transcript text files picked with the SAF picker. File names
     *  must carry the session number, e.g. session-14.txt or transcript-14.txt. */
    fun importTranscripts(uris: List<android.net.Uri>) {
        scope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val cr = app.contentResolver
            val re = Regex("(?:session|transcript)[-_ ]?(\\d+)", RegexOption.IGNORE_CASE)
            var ok = 0
            var skipped = 0
            for (u in uris) {
                try {
                    val name = cr.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                                        null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: (u.lastPathSegment ?: "")
                    val m = re.find(name)
                    if (m == null) { skipped++; continue }
                    val session = m.groupValues[1].toInt()
                    val text = cr.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (text.isNotBlank()) {
                        transcriptFile(session).writeText(text)
                        ok++
                    } else skipped++
                } catch (_: Exception) { skipped++ }
            }
            _trans.value = TransState.Done(-1,
                "Imported $ok transcript files, $skipped skipped. File names must include the session number (session-14.txt).")
            if (ok > 0) synchronized(driveMirrorLock) { mirrorTranscriptsToDrive() }
            refreshSessions()
        }
    }

    /** Deletes every transcript file (session_N.txt). The recordings on the
     *  phone and on the pendant are untouched; the batch simply re-transcribes
     *  those sessions on its next run. */
    fun clearAllTranscripts() {
        scope.launch(Dispatchers.Default) {
            val dir = java.io.File(getApplication<Application>().filesDir, "transcripts")
            val files = dir.listFiles { f -> f.name.startsWith("session_") && f.name.endsWith(".txt") }.orEmpty()
            var n = 0
            for (f in files) if (f.delete()) n++
            _trans.value = TransState.Done(-1,
                "Cleared $n transcript files. The batch will re-transcribe those sessions on its next run.")
            refreshSessions()
        }
    }

    private val driveMirrorLock = Any()

    /** Saves a transcript locally and mirrors it into the picked Drive folder
     *  as YYYY-MM-DD_session-N.txt, pruning mirrored copies older than 14
     *  days so Drive never accumulates them. */
    fun saveTranscript(session: Int, text: String) {
        transcriptFile(session).writeText(text)
        synchronized(driveMirrorLock) { mirrorTranscriptsToDrive() }
    }

    // ---- Daily insights (bring-your-own-key AI) ----

    private val _aiEnabled = MutableStateFlow(prefs.getBoolean("ai_enabled", false))
    val aiEnabled: StateFlow<Boolean> = _aiEnabled
    private val _aiProvider = MutableStateFlow(ReflectionAi.provider(prefs))
    val aiProvider: StateFlow<String> = _aiProvider
    private val _aiKey = MutableStateFlow(ReflectionAi.key(prefs))
    val aiKey: StateFlow<String> = _aiKey
    private val _aiModel = MutableStateFlow(prefs.getString("ai_model", "") ?: "")
    val aiModel: StateFlow<String> = _aiModel
    private val _aiBaseUrl = MutableStateFlow(ReflectionAi.baseUrl(prefs))
    val aiBaseUrl: StateFlow<String> = _aiBaseUrl
    private val _aiHour = MutableStateFlow(ReflectionAi.hour(prefs))
    val aiHour: StateFlow<Int> = _aiHour
    private val _aiNote = MutableStateFlow(ReflectionAi.lastResult(prefs))
    val aiNote: StateFlow<String> = _aiNote

    fun setAiEnabled(on: Boolean) {
        _aiEnabled.value = on
        prefs.edit().putBoolean("ai_enabled", on).apply()
        ReflectionAi.armNext(getApplication())
    }

    fun setAiProvider(v: String) {
        _aiProvider.value = v
        prefs.edit().putString("ai_provider", v).apply()
        ReflectionAi.armNext(getApplication())
    }

    fun setAiKey(v: String) { _aiKey.value = v; prefs.edit().putString("ai_api_key", v.trim()).apply() }
    fun setAiModel(v: String) { _aiModel.value = v; prefs.edit().putString("ai_model", v.trim()).apply() }
    fun setAiBaseUrl(v: String) { _aiBaseUrl.value = v; prefs.edit().putString("ai_base_url", v.trim()).apply() }

    fun setAiHour(h: Int) {
        _aiHour.value = h
        prefs.edit().putInt("ai_hour", h).apply()
        ReflectionAi.armNext(getApplication())
    }

    fun refreshAiNote() { _aiNote.value = ReflectionAi.lastResult(prefs) }

    /** Write today's card right now; rewrites an existing one, for testing. */
    fun aiGenerateNow() {
        scope.launch(Dispatchers.IO) {
            _aiNote.value = "Calling the AI service..."
            _aiNote.value = ReflectionAi.generateDue(getApplication(), manual = true)
        }
    }

    private fun mirrorTranscriptsToDrive(): String {
        try {
            val uriStr = prefs.getString("drive_tree_uri", null) ?: return "No folder picked yet."
            val treeUri = android.net.Uri.parse(uriStr)
            val cr = getApplication<Application>().contentResolver
            val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            val treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val dir = java.io.File(getApplication<Application>().filesDir, "transcripts")
            val files = dir.listFiles { f -> f.name.startsWith("session_") && f.name.endsWith(".txt") }
                ?: return "No transcripts on this phone yet."
            if (files.isEmpty()) return "No transcripts on this phone to copy yet."
            var copied = 0; var already = 0; var failed = 0
            for (f in files) {
                val m = Regex("session_(\\d+)\\.txt").find(f.name) ?: continue
                val name = "${today}_session-${m.groupValues[1]}.txt"
                if (treeHasChild(cr, treeUri, docId, name)) { already++; continue }
                val created = try {
                    android.provider.DocumentsContract.createDocument(cr, treeDocUri, "text/plain", name)
                } catch (_: Exception) { null }
                if (created == null) { failed++; continue }
                try { cr.openOutputStream(created)?.use { it.write(f.readBytes()) } } catch (_: Exception) { failed++ }
                if (created != null) copied++
            }
            pruneOldDriveTranscripts(cr, treeUri, docId)
            return if (failed == 0) "Drive folder OK: $copied copied, $already already there (of ${files.size} transcripts)."
            else "Drive folder: $copied copied, $already already there, $failed FAILED (of ${files.size})."
        } catch (e: Exception) {
            return "Mirror failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Runs the mirror on demand and shows the result on the settings card,
     *  so a broken folder link is visible instead of silent. */
    fun mirrorNow() {
        scope.launch(Dispatchers.IO) {
            val line = synchronized(driveMirrorLock) { mirrorTranscriptsToDrive() }
            _mirrorNote.value = line
        }
    }

    private fun treeHasChild(
        cr: android.content.ContentResolver, treeUri: android.net.Uri, docId: String, name: String,
    ): Boolean {
        return try {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            cr.query(childrenUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                     null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) if (c.getString(col) == name) return true
            }
            false
        } catch (_: Exception) { false }
    }

    /** Deletes mirrored transcript files whose date prefix is older than 14 days. */
    private fun pruneOldDriveTranscripts(
        cr: android.content.ContentResolver, treeUri: android.net.Uri, docId: String,
    ): Int {
        var n = 0
        try {
            val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(System.currentTimeMillis() - 14L * 24 * 3600 * 1000))
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val victims = mutableListOf<android.net.Uri>()
            cr.query(childrenUri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                val iId = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(iName) ?: continue
                    val m = Regex("^(\\d{4}-\\d{2}-\\d{2})_session-\\d+\\.txt$").matchEntire(name) ?: continue
                    if (m.groupValues[1] < cutoff) {
                        victims.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(iId)))
                    }
                }
            }
            for (u in victims) {
                try { if (android.provider.DocumentsContract.deleteDocument(cr, u)) n++ } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return n
    }

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

    /** Share the session's decoded WAV together with its transcript text file. */
    fun exportSessionAudioAndTranscript(session: Int) {
        if (exportJob?.isActive == true) return
        exportJob = scope.launch(Dispatchers.IO) {
            try {
                val wav = decodeSessionWav(session)
                val files = ArrayList<java.io.File>()
                files.add(wav)
                transcriptFile(session).takeIf { it.exists() }?.let { files.add(it) }
                val uris = ArrayList<android.net.Uri>(files.size)
                for (f in files) {
                    uris.add(androidx.core.content.FileProvider.getUriForFile(
                        getApplication(), "com.pendantcompanion.fileprovider", f))
                }
                val share = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(
                        android.content.Intent.EXTRA_STREAM, uris)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                             android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = android.content.Intent.createChooser(
                    share, "Session $session audio and transcript")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(chooser)
            } catch (_: Exception) {
            }
        }
    }

    /** Plain-language diagnosis when a session has no decodable audio on the phone. */
    private fun noAudioDiagnosis(session: Int): String {
        val dir = flashPagesDir().listFiles() ?: emptyArray<java.io.File>()
        val mine = dir.filter { it.name.endsWith("_session$session.bin") }
        val frames = mine.sumOf { FlashPageParser.extractOpusFrames(it.readBytes()).size }
        val wav = wavFileFor(session)
        val where = when {
            mine.isEmpty() ->
                "no audio pages for this session reached the phone (storage holds ${dir.size} page files for other sessions)"
            frames == 0 ->
                "${mine.size} audio page${if (mine.size == 1) "" else "s"} stored for this session hold 0 usable frames, so their audio did not survive"
            else ->
                "${mine.size} pages hold $frames frames but none decoded"
        }
        val cache = if (!wav.exists()) "no cached audio copy"
            else "a ${wav.length()}-byte cached audio copy that is too small to use"
        return "No audio frames found for session $session: $where; $cache."
    }

    private suspend fun decodeSessionWav(session: Int): java.io.File {
        val out = wavFileFor(session)
        if (out.exists() && out.length() > 44L) return out
        val packets = collectPackets(session)
        if (packets.isEmpty()) throw IllegalStateException(noAudioDiagnosis(session))
        val jobCtx = kotlinx.coroutines.currentCoroutineContext()
        OpusDecoder.decodeToWav(packets, out, { jobCtx.isActive }, { _, _ -> })
        return out
    }

    /** 48 kHz 16-bit WAV -> 16 kHz float samples (simple 3:1 box filter). */
    private fun wavToSamples(wav: java.io.File): FloatArray {
        val total = wav.length()
        val start = 44L
        val n = ((total - start) / 6).toInt().coerceAtLeast(0)
        val out = FloatArray(n)
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(wav), 1 shl 20)).use { inp ->
            var skipped = 0L
            while (skipped < start) skipped += inp.skip(start - skipped)
            val buf = ByteArray(1 shl 16)
            var i = 0
            while (i < n) {
                val got = inp.read(buf, 0, minOf(buf.size, (n - i) * 6))
                if (got <= 0) break
                val frames = got / 6
                for (k in 0 until frames) {
                    val bi = k * 6
                    val s0 = ((buf[bi].toInt() and 0xff) or (buf[bi + 1].toInt() shl 8)).toShort().toInt()
                    val s1 = ((buf[bi + 2].toInt() and 0xff) or (buf[bi + 3].toInt() shl 8)).toShort().toInt()
                    val s2 = ((buf[bi + 4].toInt() and 0xff) or (buf[bi + 5].toInt() shl 8)).toShort().toInt()
                    out[i + k] = ((s0 + s1 + s2) / 3f) / 32768f
                }
                i += frames
            }
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
        syncJob?.cancel()
        scope.cancel()
        session?.close()
        client?.release()
    }
}

/**
 * App-scoped home for a batch transcription run: lives outside the
 * ViewModel so closing the activity does not cancel the batch, while the
 * foreground [com.pendantcompanion.TranscribeService] keeps the process
 * itself alive. Transcript files persist per session, so a batch that is
 * interrupted resumes where it left off on the next run.
 */
object BatchRunner {
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )
    val state = kotlinx.coroutines.flow.MutableStateFlow<PendantViewModel.BatchProgress?>(null)
    var job: kotlinx.coroutines.Job? = null
}
