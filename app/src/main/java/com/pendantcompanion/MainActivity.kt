package com.pendantcompanion

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pendantcompanion.ui.PendantViewModel
import com.pendantcompanion.ui.PendantViewModel.State.Connected
import com.pendantcompanion.ui.PendantViewModel.State.Connecting
import com.pendantcompanion.ui.PendantViewModel.State.Error
import com.pendantcompanion.ui.PendantViewModel.State.Idle
import com.pendantcompanion.ui.PendantViewModel.State.ScanResults
import com.pendantcompanion.ui.PendantViewModel.State.Scanning
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

fun exportFlashPages(context: android.content.Context): Int {
    val dir = File(context.filesDir, "flash_pages")
    val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
    if (files.isEmpty()) return 0
    val outDir = File(context.filesDir, "exports").apply { mkdirs() }
    val out = File(outDir, "pendant_flash_pages.zip")
    ZipOutputStream(FileOutputStream(out)).use { zos ->
        files.forEach { f ->
            zos.putNextEntry(ZipEntry(f.name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
    }
    val uri: Uri = FileProvider.getUriForFile(context, "com.pendantcompanion.fileprovider", out)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Export flash pages"))
    return files.size
}


fun buildBackupZip(context: android.content.Context): Int {
    val pages = File(context.filesDir, "flash_pages").listFiles()
        ?.filter { it.name.startsWith("page_") }?.sortedBy { it.name } ?: emptyList()
    val transcripts = File(context.filesDir, "transcripts")
        .listFiles { f -> f.name.startsWith("session_") && f.name.endsWith(".txt") }
        ?.sortedBy { it.name } ?: emptyList()
    if (pages.isEmpty() && transcripts.isEmpty()) return 0
    val outDir = File(context.filesDir, "exports").apply { mkdirs() }
    val out = File(outDir, "pendant_export_all.zip")
    ZipOutputStream(FileOutputStream(out)).use { zos ->
        pages.forEach { f ->
            zos.putNextEntry(ZipEntry("pages/" + f.name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        transcripts.forEach { f ->
            zos.putNextEntry(ZipEntry("transcripts/" + f.name))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        zos.putNextEntry(ZipEntry("manifest.txt"))
        val manifest = buildString {
            appendLine("Pendant Companion export")
            appendLine("exported: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            appendLine("pages: ${pages.size} raw flash page files")
            appendLine("transcripts: ${transcripts.size} text files")
            appendLine("note: read-only sync unless acknowledge mode is on; acknowledged pages were erased from the pendant after being saved here")
        }
        zos.write(manifest.toByteArray())
        zos.closeEntry()
    }
    return pages.size + transcripts.size
}

fun exportEverythingZip(context: android.content.Context): Int {
    val n = buildBackupZip(context)
    if (n == 0) return 0
    val out = File(File(context.filesDir, "exports"), "pendant_export_all.zip")
    val uri: Uri = FileProvider.getUriForFile(context, "com.pendantcompanion.fileprovider", out)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Export everything"))
    return n
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = PendantColors) { Surface(Modifier.fillMaxSize()) { PendantApp() } } }
    }
}


/** App palette matched to the launcher icon: deep navy with an orange accent. */
private val PendantColors = darkColorScheme(
    primary = Color(0xFFF09A4B),
    onPrimary = Color(0xFF161A2C),
    primaryContainer = Color(0xFF3A4066),
    onPrimaryContainer = Color(0xFFF5E9DC),
    secondary = Color(0xFF9DB0E8),
    onSecondary = Color(0xFF161A2C),
    secondaryContainer = Color(0xFF252A44),
    onSecondaryContainer = Color(0xFFDDE3F5),
    tertiary = Color(0xFF9DB0E8),
    onTertiary = Color(0xFF161A2C),
    background = Color(0xFF161A2C),
    onBackground = Color(0xFFE9ECF6),
    surface = Color(0xFF1D2236),
    onSurface = Color(0xFFE9ECF6),
    surfaceVariant = Color(0xFF252A44),
    onSurfaceVariant = Color(0xFFC3CBE4),
    outline = Color(0xFF565C7A),
    error = Color(0xFFEF7D68),
    onError = Color(0xFF161A2C),
)

@Composable
fun PendantApp(vm: PendantViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val needed = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) vm.startScan() }

    val enableBt = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val btOn by vm.btOn.collectAsState()
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pendant Companion", style = MaterialTheme.typography.headlineMedium)
        if (!btOn) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BluetoothDisabled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Bluetooth is off. Turn it on to find the pendant.",
                         Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton({ enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = "Turn on Bluetooth")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (val s = state) {
                is Idle -> "No pendant connected"
                is Scanning -> "Scanning for your LS-PT-01... (wake the pendant if its light is off)"
                is ScanResults -> "${s.hits.size} pendant candidate(s) found"
                is Connecting -> "Connecting to ${s.deviceName}..."
                is Connected -> "Connected: ${s.deviceName}"
                is Error -> s.message
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state is PendantViewModel.State.Scanning ||
            state is PendantViewModel.State.Connecting) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))

        when (val s = state) {
            is Idle, is Error -> Button({ permissionLauncher.launch(needed) }) { Text("Find my pendant") }
            is Scanning -> Button({ vm.stopScan() }) { Text("Stop scanning") }
            is ScanResults -> {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(s.hits) { hit ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.name ?: "(unnamed)", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${hit.device.address}   ${hit.rssi} dBm   " +
                                        if (hit.advertisesPendantService) "pendant service" else "name match",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button({ vm.pairAndConnect(hit) }) { Text("Connect") }
                        }
                    }
                }
            }
            is Connecting -> CircularProgressIndicator()
            is Connected -> DeviceInfoCard(s, vm, onDisconnect = { vm.disconnect() })
        }
    }
}

@Composable
fun DeviceInfoCard(s: Connected, vm: PendantViewModel, onDisconnect: () -> Unit) {
    val i = s.info
    val sync by vm.sync.collectAsState()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Device information (read-only)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Serial: ${i.serial.ifEmpty { "" }}")
                Text("Firmware: ${i.firmware.ifEmpty { "not reported" }}")
                Text("Battery: ${i.batteryPercent}%")
                Text("Flash pages stored: ${i.oldestFlashPage} .. ${i.newestFlashPage}")
                Text("Device id: ${i.deviceId}")
                Text("Audio encryption: " + if (i.audioEncryptionEnabled) "enabled on device" else "not configured (fresh device)")
            }
        }
        Spacer(Modifier.height(16.dp))
        val syncRunning = (sync as? PendantViewModel.SyncProgress)?.finished == false
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ vm.checkStorageNow() }, enabled = !syncRunning) { Text("Check storage") }
            Button({ vm.syncReadOnly() }, enabled = !syncRunning) {
                Text(if (syncRunning) "Syncing..." else "Download flash")
            }
        }
        Spacer(Modifier.height(8.dp))
        (sync as? PendantViewModel.SyncProgress)?.let { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (p.finished) "Sync finished" else "Syncing flash (read-only)...",
                         style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { if (p.finished) 1f else 0.05f },
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text("Pages saved: ${p.pagesSaved}   Frames seen: ${p.framesSeen}   Bytes: ${p.bytesSaved / 1024} KB")
                    if (p.lastPageIndex >= 0) Text("Last page index: ${p.lastPageIndex}")
                    Text(p.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SearchCard(vm)
        Spacer(Modifier.height(8.dp))
        LedCard(vm)
        Spacer(Modifier.height(8.dp))
        AckCard(vm)
        Spacer(Modifier.height(8.dp))
        StorageCard(vm)
        Spacer(Modifier.height(8.dp))
        val pickModel = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.importModel(it) } }
        WhisperCard(vm, onPickModel = { pickModel.launch(arrayOf("*/*")) })
        Spacer(Modifier.height(8.dp))
        val pickSpeaker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.importSpeakerModel(it) } }
        SpeakerCard(vm, onPickModel = { pickSpeaker.launch(arrayOf("*/*")) })
        Spacer(Modifier.height(8.dp))
        SessionsCard(vm)
        Spacer(Modifier.height(8.dp))
        val ctx = LocalContext.current
        var exportedCount by remember { mutableStateOf(-1) }
        Button({ exportedCount = exportFlashPages(ctx) }) { Text("Export pages") }
        if (exportedCount >= 0) {
            Text(
                if (exportedCount == 0) "Nothing to export yet: no pages saved on this phone."
                else "Zipped $exportedCount page files. Pick Gmail in the share sheet to send them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        var exportedAll by remember { mutableStateOf(-1) }
        Button({ exportedAll = exportEverythingZip(ctx) }) { Text("Export everything (pages + transcripts)") }
        if (exportedAll >= 0) {
            Text(
                if (exportedAll == 0) "Nothing to export yet: no pages or transcripts saved on this phone."
                else "Zipped $exportedAll files (pages, transcripts, manifest). Pick Gmail in the share sheet to send them.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(12.dp))
        Button(onDisconnect) { Text("Disconnect") }
        Text("Read-only: pages are copied to this phone and the pendant keeps its copies (no acknowledge is sent).",
            style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
fun SessionsCard(vm: PendantViewModel) {
    val sessions by vm.sessions.collectAsState()
    val player by vm.player.collectAsState()
    val speakerState by vm.speaker.collectAsState()
    val sync by vm.sync.collectAsState()
    val trans by vm.trans.collectAsState()
    val decodeNote by vm.decodeNote.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSessions() }
    LaunchedEffect((sync as? PendantViewModel.SyncProgress)?.finished) {
        if ((sync as? PendantViewModel.SyncProgress)?.finished == true) vm.refreshSessions()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recordings from the pendant on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (sessions.isEmpty()) {
                Text("Nothing saved yet. Tap Download flash first.",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${sessions.size} recording sessions",
                     style = MaterialTheme.typography.bodySmall)
                var pendingDelete by remember { mutableStateOf<PendantViewModel.SessionInfo?>(null) }
                val pickTranscripts = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> if (uris.isNotEmpty()) vm.importTranscripts(uris) }
                Button(onClick = { pickTranscripts.launch(arrayOf("*/*")) }) {
                    Text("Import transcript files")
                }
                var confirmingClear by remember { mutableStateOf(false) }
                Button(onClick = { confirmingClear = true }) {
                    Text("Clear all transcripts")
                }
                if (confirmingClear) {
                    AlertDialog(
                        onDismissRequest = { confirmingClear = false },
                        title = { Text("Clear all transcripts?") },
                        text = { Text("This deletes every transcript in the app. The recordings themselves are untouched, and the batch will re-transcribe those sessions on its next run.") },
                        confirmButton = {
                            TextButton(onClick = { confirmingClear = false; vm.clearAllTranscripts() }) { Text("Clear") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmingClear = false }) { Text("Cancel") }
                        },
                    )
                }

                pendingDelete?.let { del ->
                    AlertDialog(
                        onDismissRequest = { pendingDelete = null },
                        title = { Text("Delete session ${del.session}?") },
                        text = { Text("This removes the session, its audio and its transcript from the phone. With acknowledge-after-sync on, the pendant has already erased its copy, so this cannot be undone.") },
                        confirmButton = { TextButton(onClick = { pendingDelete = null; vm.deleteSession(del.session) }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(sessions) { sess ->
                        val playing = player is PendantViewModel.PlayerState.Playing &&
                            (player as PendantViewModel.PlayerState.Playing).session == sess.session
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Session ${sess.session}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${sess.pages} pages · ${sess.frames} frames · " +
                                        "~${sess.durationSec / 60}m ${sess.durationSec % 60}s",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    if (sess.startMs > 0)
                                        java.text.SimpleDateFormat(
                                            "d MMM yyyy, h:mm a", java.util.Locale.getDefault(),
                                        ).format(java.util.Date(sess.startMs))
                                    else "date not recorded (older firmware)",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val hasTranscript = vm.transcriptFile(sess.session).exists()
                            val paused = player is PendantViewModel.PlayerState.Paused &&
                                (player as PendantViewModel.PlayerState.Paused).session == sess.session
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                IconButton({ vm.playSession(sess.session) },
                                           enabled = player !is PendantViewModel.PlayerState.Decoding) {
                                    Icon(
                                        when {
                                            playing -> Icons.Filled.Pause
                                            paused -> Icons.Filled.PlayArrow
                                            else -> Icons.Filled.PlayArrow
                                        },
                                        contentDescription = when {
                                            playing -> "Pause"
                                            paused -> "Resume"
                                            else -> "Play"
                                        })
                                }
                                if (paused) {
                                    IconButton({ vm.stopPlayback() }) {
                                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                                    }
                                }
                                IconButton({ vm.exportSession(sess.session) },
                                           enabled = player !is PendantViewModel.PlayerState.Decoding) {
                                    Icon(Icons.Filled.Save, contentDescription = "Export WAV")
                                }
                                IconButton({ vm.exportSessionAudioAndTranscript(sess.session) },
                                           enabled = player !is PendantViewModel.PlayerState.Decoding) {
                                    Icon(Icons.Filled.Share, contentDescription = "Export audio + transcript")
                                }
                                IconButton({ vm.shareTranscript(sess.session) }, enabled = hasTranscript) {
                                    Icon(Icons.Filled.Description, contentDescription = "Share transcript")
                                }
                                IconButton({ vm.transcribeSession(sess.session) },
                                           enabled = vm.model.value.path != null &&
                                               trans !is PendantViewModel.TransState.Running) {
                                    Icon(Icons.Filled.GraphicEq, contentDescription = "Transcribe")
                                }
                                IconButton({ vm.enrollSession(sess.session) },
                                           enabled = speakerState.loaded) {
                                    Icon(Icons.Filled.PersonAdd, contentDescription = "Enroll voice")
                                }
                                IconButton({ pendingDelete = sess }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete session")
                                }
                            }
                        }
                    }
                }
            }
            when (val ps = player) {
                is PendantViewModel.PlayerState.Decoding -> {
                    LinearProgressIndicator(
                        progress = { if (ps.total <= 0) 0f else ps.done.toFloat() / ps.total },
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Text("Decoding session ${ps.session}: ${ps.done}/${ps.total} packets",
                         style = MaterialTheme.typography.bodySmall)
                }
                is PendantViewModel.PlayerState.Playing ->
                    Text("Playing session ${ps.session}", style = MaterialTheme.typography.bodySmall)
                is PendantViewModel.PlayerState.PlayerError ->
                    Text(ps.message, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
            decodeNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable
fun WhisperCard(vm: PendantViewModel, onPickModel: () -> Unit) {
    val model by vm.model.collectAsState()
    val trans by vm.trans.collectAsState()
    LaunchedEffect(Unit) { vm.refreshModelInfo() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("On-device transcription (Whisper)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val mp = model.path
            if (mp == null) {
                Text("No model loaded. Pick a ggml .bin Whisper model file (no internet needed). tiny.en works; base.en or small.en .bin models also load the same way for noticeably better transcripts.",
                     style = MaterialTheme.typography.bodySmall)
                Button(onClick = onPickModel) { Text("Load model file") }
            } else {
                Text("Model: ${mp.substringAfterLast('/')} (${model.sizeBytes / 1_000_000} MB)" +
                     if (model.ready) " — ready" else "",
                     style = MaterialTheme.typography.bodySmall)
                Button(onClick = onPickModel) { Text("Replace model") }
                Text("Want better accuracy? Import a larger ggml .bin model (base.en or small.en); it replaces the current one the same way.",
                     style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                val b by vm.batch.collectAsState()
                Button(onClick = { vm.transcribeAllSessions() }, enabled = b == null) {
                    Text("Transcribe all sessions")
                }
                b?.let { bt ->
                    if (bt.total > 0 && bt.fraction > 0f) {
                        LinearProgressIndicator(
                            progress = { bt.fraction.coerceIn(0f, 1f) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        when {
                            bt.note == "finished" ->
                                "Finished: ${bt.done} of ${bt.total} transcripts ready, ${bt.failed} failed. Use Export everything (pages + transcripts) to zip them all."
                            bt.total == 0 ->
                                "Batch: ${bt.note}"
                            bt.note.isBlank() ->
                                "Batch: ${bt.done} of ${bt.total} done; session ${bt.session} up next"
                            else ->
                                "Batch: ${bt.done} of ${bt.total} done; session ${bt.session} ${bt.note}" +
                                    (if (bt.fraction > 0f) " (${(bt.fraction * 100).toInt()}% overall)" else "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { vm.stopBatch() }) { Text("Stop batch") }
                }
            }
            when (val t = trans) {
                is PendantViewModel.TransState.Running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Session ${t.session}: ${t.note}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { vm.stopTranscribe() }) { Text("Stop") }
                }
                is PendantViewModel.TransState.Done ->
                    Text("Session ${t.session} transcribed (${t.text.length} chars). Use TXT to view or share.",
                         style = MaterialTheme.typography.bodySmall)
                is PendantViewModel.TransState.TransError ->
                    Text(t.message, color = MaterialTheme.colorScheme.error,
                         style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
        }
    }
}

@Composable
fun SearchCard(vm: PendantViewModel) {
    val search by vm.search.collectAsState()
    var query by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Int?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Search your recordings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Finds words in transcripts. Transcribe a session (the TR icon) first. Everything runs on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            val onlyYou by vm.searchOnlyYou.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyYou, onCheckedChange = { vm.setSearchOnlyYou(it) })
                Text("Only my voice (score sessions first)", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search transcripts") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.runSearch(query) }),
                )
                IconButton({ vm.runSearch(query) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
            val anyTranscripts = remember(search.searched) { vm.hasAnyTranscripts() }
            if (!anyTranscripts) {
                Text(
                    "No transcripts yet. Tap the TR icon on a recording to transcribe it first; search reads transcripts, not audio.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (search.searched) {
                if (search.hits.isEmpty()) {
                    Text("No transcripts contain \"${search.query}\".",
                         style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${search.hits.size} session(s) match",
                         style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        items(search.hits) { hit ->
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { viewing = hit.session }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    "Session ${hit.session}" +
                                        if (hit.stamp.isNotEmpty()) " · ${hit.stamp}" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    hit.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            viewing?.let { sess ->
                val text = remember(sess) { vm.transcriptText(sess) } ?: ""
                val ctx = LocalContext.current
                var ttsReady by remember(sess) { mutableStateOf(false) }
                var speaking by remember(sess) { mutableStateOf(false) }
                val tts = remember(sess) {
                    TextToSpeech(ctx) { st -> ttsReady = st == TextToSpeech.SUCCESS }
                }
                DisposableEffect(sess) {
                    onDispose { try { tts.stop(); tts.shutdown() } catch (_: Exception) {} }
                }
                val tprefs = remember(sess) { ctx.getSharedPreferences("pendant_prefs", Context.MODE_PRIVATE) }
                var speed by remember(sess) { mutableStateOf(tprefs.getFloat("tts_speed", 1.0f)) }
                AlertDialog(
                    onDismissRequest = { viewing = null },
                    confirmButton = { TextButton({ viewing = null }) { Text("Close") } },
                    title = { Text("Session $sess transcript") },
                    text = {
                        Column(
                            Modifier.heightIn(max = 480.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (text.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        enabled = ttsReady,
                                        onClick = {
                                            if (speaking) { tts.stop(); speaking = false }
                                            else {
                                                tts.setSpeechRate(speed)
                                                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                                    override fun onStart(id: String?) { speaking = true }
                                                    override fun onDone(id: String?) { speaking = false }
                                                    @Deprecated("Deprecated in Java")
                                                    override fun onError(id: String?) { speaking = false }
                                                })
                                                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sess$sess")
                                                speaking = true
                                            }
                                        },
                                    ) { Text(if (speaking) "Stop" else "Read aloud") }
                                    Spacer(Modifier.width(10.dp))
                                    Text(String.format(Locale.US, "Speed %.1fx", speed),
                                         style = MaterialTheme.typography.bodySmall)
                                }
                                Slider(
                                    value = speed,
                                    onValueChange = {
                                        speed = it
                                        tts.setSpeechRate(it)
                                        tprefs.edit().putFloat("tts_speed", it).apply()
                                    },
                                    valueRange = 0.5f..1.6f,
                                )
                            }
                            if (!ttsReady && text.isNotEmpty()) {
                                Text("Speech engine loading...", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                if (text.isEmpty()) "No transcript saved for this session." else text,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                )
            }
        }
    }
}
@Composable
fun LedCard(vm: PendantViewModel) {
    val led by vm.led.collectAsState()
    LaunchedEffect(Unit) { vm.refreshLedBrightness() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pendant LED", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                led.reported?.let { "Pendant reports LED brightness: $it" } ?: "Pendant has not reported an LED brightness yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(0, "LED off") }) { Text("Off") }
                Spacer(Modifier.width(6.dp))
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(25, "LED quarter") }) { Text("25%") }
                Spacer(Modifier.width(6.dp))
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(50, "LED half") }) { Text("Half") }
            }
            Row {
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(75, "LED three-quarter") }) { Text("75%") }
                Spacer(Modifier.width(6.dp))
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(100, "LED full") }) { Text("Full") }
            }
            led.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun AckCard(vm: PendantViewModel) {
    val ack by vm.ackMode.collectAsState()
    val auto by vm.autoSync.collectAsState()
    val autoTx by vm.autoTranscribe.collectAsState()
    val driveFolder by vm.driveFolder.collectAsState()
    val pickTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.setDriveFolder(uri) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sync & automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Acknowledge pages after sync", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = ack, onCheckedChange = { vm.setAckMode(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-sync on connect", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = auto, onCheckedChange = { vm.setAutoSync(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-transcribe after sync", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = autoTx, onCheckedChange = { vm.setAutoTranscribe(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transcripts to Drive", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { pickTree.launch(null) }) {
                    Text(if (driveFolder == null) "Pick folder" else "Change folder")
                }
            }
            Text(
                "Every transcript is copied into that Drive folder as 2026-10-25_session-12.txt, and copies older " +
                    "than 14 days are deleted automatically, so Drive never accumulates them. Pick a folder inside " +
                    "your Drive once; the app itself never uses the internet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "On (default): as soon as the pendant connects, a read-only sync starts by itself; if new pages were saved, the backup zip is refreshed afterwards. " +
                    "Off: you tap Download flash yourself.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Off (default): the pendant keeps every recording and each sync re-streams its whole flash. " +
                    "On: each page is acknowledged only after it is saved on this phone, and the pendant erases it, " +
                    "so later syncs transfer only new pages. This phone then holds the only copy. " +
                    "A backup zip is rebuilt after every acknowledged sync; keep a copy of it outside the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun StorageCard(vm: PendantViewModel) {
    val st by vm.storageView.collectAsState()
    val note by vm.storageNote.collectAsState()
    LaunchedEffect(Unit) { vm.refreshStorage() }
    var confirming by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pendant storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val s = st
            if (s == null) {
                Text("Connect to read the pendant's storage.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${s.freeCapturePages} of ${s.totalCapturePages} capture pages free", style = MaterialTheme.typography.bodySmall)
                if (s.totalCapturePages > 0) {
                    val used = (s.totalCapturePages - s.freeCapturePages).coerceAtLeast(0) / s.totalCapturePages.toFloat()
                    LinearProgressIndicator(progress = { used }, Modifier.fillMaxWidth())
                    val hoursFree = s.freeCapturePages * 35.0 / s.totalCapturePages
                    Text("about " + "%.1f".format(hoursFree) + " hours of recording space left", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                enabled = s != null,
                onClick = {
                    if (!confirming) confirming = true
                    else { confirming = false; vm.eraseSyncedPages() }
                },
            ) {
                Text(if (confirming) "Tap again to erase pages already saved on this phone" else "Free space (erase pages already saved here)")
            }
            Text(
                "Only pages this phone already holds are erased; unsynced recordings stay on the pendant. Acknowledge mode does this automatically after each sync.",
                style = MaterialTheme.typography.bodySmall,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            var confirmingClearAll by remember { mutableStateOf(false) }
            Button(onClick = { confirmingClearAll = true }) {
                Text("Clear everything")
            }
            if (confirmingClearAll) {
                AlertDialog(
                    onDismissRequest = { confirmingClearAll = false },
                    title = { Text("Clear everything?") },
                    text = { Text("This will: sync the pendant one last time so nothing is lost; erase every pendant page already saved on this phone; delete all recordings, exports, transcripts and backup zips on this phone (the Whisper model is kept); and delete every transcript copy in your Drive folder. Recordings that failed to sync stay on the pendant. The Limitless cloud is handled by your account-deletion request.") },
                    confirmButton = {
                        TextButton(onClick = { confirmingClearAll = false; vm.clearEverything() }) { Text("Clear everything") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingClearAll = false }) { Text("Cancel") }
                    },
                )
            }
            var confirmingFactoryReset by remember { mutableStateOf(false) }
            Button(onClick = { confirmingFactoryReset = true }) {
                Text("Factory reset pendant")
            }
            if (confirmingFactoryReset) {
                AlertDialog(
                    onDismissRequest = { confirmingFactoryReset = false },
                    title = { Text("Factory reset the pendant?") },
                    text = { Text("This wipes the pendant's ENTIRE flash, including anything not yet synced to this phone. It cannot be undone, and the pendant will likely need re-pairing afterwards because it may reset its keys. If you want to keep anything, sync first.") },
                    confirmButton = {
                        TextButton(onClick = { confirmingFactoryReset = false; vm.factoryResetPendant() }) { Text("Factory reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingFactoryReset = false }) { Text("Cancel") }
                    },
                )
            }
        }
    }
}

@Composable
fun SpeakerCard(vm: PendantViewModel, onPickModel: () -> Unit) {
    val speaker by vm.speaker.collectAsState()
    val scores by vm.speakerScores.collectAsState()
    val note by vm.speakerNote.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Voiceprint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Tells your voice from background chatter and TV. Everything runs on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onPickModel) {
                Text(if (speaker.loaded) "Voice model loaded (tap to replace)" else "Load voice model (.onnx)")
            }
            Text(
                "Wear the pendant and talk normally for a minute, sync, then tap the person+ icon on that session in Recordings below. One session is enough; more is better.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (speaker.enrolled.isNotEmpty()) {
                Text("Enrolled from session(s): " + speaker.enrolled.joinToString(", "),
                     style = MaterialTheme.typography.bodySmall)
                TextButton({ vm.clearEnrollment() }) { Text("Clear enrollment") }
            }
            Text("Match threshold: " + String.format(java.util.Locale.US, "%.2f", speaker.threshold),
                 style = MaterialTheme.typography.bodySmall)
            Slider(value = speaker.threshold, onValueChange = { vm.setSpeakerThreshold(it) },
                   valueRange = 0.5f..0.95f, enabled = speaker.loaded)
            Button({ vm.scoreSessions() },
                   enabled = speaker.loaded && speaker.enrolled.isNotEmpty()) {
                Text("Score all sessions")
            }
            if (scores.isNotEmpty()) {
                Text("Your voice, per session:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                scores.values.sortedByDescending { it.youPct }.forEach { sv ->
                    Text("Session ${sv.session}: ${sv.youPct}% your voice (${sv.voiced} voiced clips)",
                         style = MaterialTheme.typography.bodySmall)
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
