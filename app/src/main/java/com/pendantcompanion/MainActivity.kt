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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { PendantApp() } } }
    }
}

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
                Text("Device information (read-only)", style = MaterialTheme.typography.titleMedium)
                Text("Serial: ${i.serial.ifEmpty { "not reported" }}")
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
        val pickModel = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.importModel(it) } }
        WhisperCard(vm, onPickModel = { pickModel.launch(arrayOf("*/*")) })
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
    val sync by vm.sync.collectAsState()
    val trans by vm.trans.collectAsState()
    val decodeNote by vm.decodeNote.collectAsState()
    LaunchedEffect(Unit) { vm.refreshSessions() }
    LaunchedEffect((sync as? PendantViewModel.SyncProgress)?.finished) {
        if ((sync as? PendantViewModel.SyncProgress)?.finished == true) vm.refreshSessions()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recordings from the pendant on this phone", style = MaterialTheme.typography.titleMedium)
            if (sessions.isEmpty()) {
                Text("Nothing saved yet. Tap Download flash first.",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${sessions.size} recording sessions",
                     style = MaterialTheme.typography.bodySmall)
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
                            val hasTranscript = remember(sess.session) { vm.transcriptFile(sess.session).exists() }
                            val paused = player is PendantViewModel.PlayerState.Paused &&
                                (player as PendantViewModel.PlayerState.Paused).session == sess.session
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
                            IconButton({ vm.shareTranscript(sess.session) }, enabled = hasTranscript) {
                                Icon(Icons.Filled.Description, contentDescription = "Share transcript")
                            }
                            IconButton({ vm.transcribeSession(sess.session) },
                                       enabled = vm.model.value.path != null &&
                                           trans !is PendantViewModel.TransState.Running) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = "Transcribe")
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
            Text("On-device transcription (Whisper)", style = MaterialTheme.typography.titleMedium)
            val mp = model.path
            if (mp == null) {
                Text("No model loaded. Pick a ggml .bin Whisper model file (no internet needed).",
                     style = MaterialTheme.typography.bodySmall)
                Button(onClick = onPickModel) { Text("Load model file") }
            } else {
                Text("Model: ${mp.substringAfterLast('/')} (${model.sizeBytes / 1_000_000} MB)" +
                     if (model.ready) " — ready" else "",
                     style = MaterialTheme.typography.bodySmall)
                Button(onClick = onPickModel) { Text("Replace model") }
            }
            when (val t = trans) {
                is PendantViewModel.TransState.Running -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Session ${t.session}: ${t.note}", style = MaterialTheme.typography.bodySmall)
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
            Text("Search your recordings", style = MaterialTheme.typography.titleMedium)
            Text(
                "Finds words in transcripts. Transcribe a session (the TR icon) first. Everything runs on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
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
                AlertDialog(
                    onDismissRequest = { viewing = null },
                    confirmButton = { TextButton({ viewing = null }) { Text("Close") } },
                    title = { Text("Session $sess transcript") },
                    text = {
                        Column(
                            Modifier.heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
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
            Text("Pendant LED", style = MaterialTheme.typography.titleMedium)
            Text(
                led.reported?.let { "Pendant reports LED brightness: $it" } ?: "Pendant has not reported an LED brightness yet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(3, "Dim LED") }) { Text("Almost off") }
                Spacer(Modifier.width(10.dp))
                Button(enabled = !led.busy, onClick = { vm.setLedBrightness(255, "Restore LED") }) { Text("Full") }
            }
            led.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
