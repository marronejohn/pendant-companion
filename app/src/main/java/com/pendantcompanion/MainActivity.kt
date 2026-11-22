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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

/** Breaks a raw transcript wall of text into readable paragraphs: two sentences
 *  per paragraph when punctuation allows, otherwise ~24-word chunks. */
fun formatTranscriptText(raw: String): String {
    val t = raw.trim()
    if (t.length < 250) return t
    val sentences = Regex("(?<=[.!?])\\s+").split(t).map { it.trim() }.filter { it.isNotEmpty() }
    return if (sentences.size >= 3) sentences.chunked(2).joinToString("\n\n") { it.joinToString(" ") }
    else t.split(Regex("\\s+")).chunked(24).joinToString("\n\n") { it.joinToString(" ") }
}

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
    /** Writes every uncaught crash to crash_log.txt (shown on the sync card),
     *  then lets the system handler run so the crash behaves normally. */
    private fun installCrashLogger(ctx: android.content.Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(ctx.filesDir, "crash_log.txt").writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        "  thread=" + t.name + "\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)
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

    val setupDone by vm.setupDone.collectAsState()
    if (!setupDone) {
        SetupWizard(vm)
        return
    }

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

        Spacer(Modifier.height(24.dp))
        Text("Advanced", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { vm.rerunSetup() }) { Text("Run full setup again") }
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
                StepBadge(1, "Download the recordings")
                Text("Device information (read-only)", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Button({ vm.syncReadOnly(false, true) }, enabled = !syncRunning) {
                Text(if (syncRunning) "Syncing..." else "Boost processing")
            }
        }
        Text(
            "Boost processing hands each flash page to a boosted worker so the phone never slows the pendant's stream. The Bluetooth link itself is still the top speed.",
            style = MaterialTheme.typography.bodySmall,
        )
        val crashCtx = androidx.compose.ui.platform.LocalContext.current
        val lastCrash = remember(crashCtx) {
            try {
                val f = java.io.File(crashCtx.filesDir, "crash_log.txt")
                if (f.exists() && f.length() > 0) f.readText().take(700) else null
            } catch (_: Exception) { null }
        }
        lastCrash?.let {
            Spacer(Modifier.height(8.dp))
            Text("LAST CRASH (screenshot this for a bug report):\n" + it,
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
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
        SessionsCard(vm)
        NextArrow("Step 3 of 8: Turn recordings into text")
        val pickModel = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.importModel(it) } }
        WhisperCard(vm, onPickModel = { pickModel.launch(arrayOf("*/*")) })
        NextArrow("Step 4 of 8: Teach it your voice")
        val pickSpeaker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { vm.importSpeakerModel(it) } }
        SpeakerCard(vm, onPickModel = { pickSpeaker.launch(arrayOf("*/*")) })
        NextArrow("Step 5 of 8: Voice insights")
        VoiceInsightsCard(vm)
        NextArrow("Step 6 of 8: Daily AI card (your API key)")
        AiCard(vm)
        NextArrow("Step 7 of 8: Daily reflection")
        ReflectionCard(vm)
        NextArrow("Step 8 of 8: Other voices (optional)")
        VoicesCard()
        Spacer(Modifier.height(16.dp))
        Text("Optional extras", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        PeopleCard()
        Spacer(Modifier.height(8.dp))
        SearchCard(vm)
        Spacer(Modifier.height(8.dp))
        LedCard(vm)
        Spacer(Modifier.height(8.dp))
        AckCard(vm)
        Spacer(Modifier.height(8.dp))
        StorageCard(vm)
        Spacer(Modifier.height(8.dp))
        ExperimentCard(vm)
        Spacer(Modifier.height(8.dp))
        OpenLoopsCard()
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
    val cleaning by vm.cleaning.collectAsState()
    val minSec by vm.minSessionSec.collectAsState()
    val rawSessions by vm.rawSessions.collectAsState()
    val shown = sessions.filter { it.durationSec <= 0 || it.durationSec >= minSec }
    val hiddenCount = rawSessions.count { it.durationSec in 1 until minSec }
    val durLabel = if (minSec < 60) "${minSec}s" else "${minSec / 60}m ${minSec % 60}s"
    LaunchedEffect(Unit) { vm.refreshSessions() }
    LaunchedEffect((sync as? PendantViewModel.SyncProgress)?.finished) {
        if ((sync as? PendantViewModel.SyncProgress)?.finished == true) vm.refreshSessions()
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(2, "Clean the recording list")
            Text("Recordings from the pendant on this phone", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (shown.isEmpty()) {
                Text("Nothing saved yet. Tap Download flash first.",
                     style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${shown.size} recording sessions",
                     style = MaterialTheme.typography.bodySmall)
                if (hiddenCount > 0) {
                    Text(
                        "$hiddenCount short recording${if (hiddenCount == 1) "" else "s"} hidden (under $durLabel); their audio stays on this phone",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Shortest recording shown: $durLabel. The pendant itself always records; this only trims what the app lists and transcribes. Drag to change.",
                    style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = minSec.toFloat(),
                    onValueChange = { vm.setMinSessionSec(it.toInt()) },
                    valueRange = 0f..300f)
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
                var confirmingClean by remember { mutableStateOf(false) }
                Button(onClick = { confirmingClean = true },
                       enabled = trans !is PendantViewModel.TransState.Running && !cleaning &&
                                 player !is PendantViewModel.PlayerState.Decoding) {
                    Text(if (cleaning) "Deleting recordings..." else "Delete empty recordings")
                }
                if (confirmingClean) {
                    AlertDialog(
                        onDismissRequest = { confirmingClean = false },
                        title = { Text("Delete empty recordings?") },
                        text = { Text("Scans every recording (decoding where needed) and removes the ones with no speech-like audio at all, plus recordings under 5 seconds long (those never hold real speech). Anything longer with voice, or with real transcript text, is always kept. Removed sessions are gone for good.") },
                        confirmButton = { TextButton(onClick = { confirmingClean = false; vm.cleanEmptySessions() }) { Text("Scan and delete") } },
                        dismissButton = { TextButton(onClick = { confirmingClean = false }) { Text("Cancel") } },
                    )
                }
                var confirmingShorts by remember { mutableStateOf(false) }
                Button(onClick = { confirmingShorts = true },
                       enabled = hiddenCount > 0 && trans !is PendantViewModel.TransState.Running && !cleaning &&
                                 player !is PendantViewModel.PlayerState.Decoding,
                       colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete $hiddenCount short recordings")
                }
                if (confirmingShorts) {
                    AlertDialog(
                        onDismissRequest = { confirmingShorts = false },
                        title = { Text("Delete $hiddenCount short recordings?") },
                        text = { Text("This permanently removes every recording shorter than $durLabel (the ones hidden from the list), including their audio and transcripts. This cannot be undone.") },
                        confirmButton = { TextButton(onClick = { confirmingShorts = false; vm.deleteShortSessions(minSec) }) { Text("Delete") } },
                        dismissButton = { TextButton(onClick = { confirmingShorts = false }) { Text("Cancel") } },
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(shown) { sess ->
                        val playing = player is PendantViewModel.PlayerState.Playing &&
                            (player as PendantViewModel.PlayerState.Playing).session == sess.session
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column {
                                Text("Session ${sess.session}", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${sess.pages} pages · ~${sess.durationSec / 60}m ${sess.durationSec % 60}s · " +
                                        (if (sess.startMs > 0)
                                            java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
                                                .format(java.util.Date(sess.startMs))
                                        else "date not recorded"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            val hasTranscript = vm.transcriptFile(sess.session).exists()
                            val paused = player is PendantViewModel.PlayerState.Paused &&
                                (player as PendantViewModel.PlayerState.Paused).session == sess.session
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                                    .horizontalScroll(rememberScrollState())) {
                                IconButton(modifier = Modifier.size(36.dp), onClick = { vm.playSession(sess.session) },
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
                                    IconButton(modifier = Modifier.size(36.dp), onClick = { vm.stopPlayback() }) {
                                        Icon(Icons.Filled.Stop, contentDescription = "Stop")
                                    }
                                }
                                IconButton(modifier = Modifier.size(36.dp), onClick = { vm.exportSession(sess.session) },
                                           enabled = player !is PendantViewModel.PlayerState.Decoding) {
                                    Icon(Icons.Filled.Save, contentDescription = "Export WAV")
                                }
                                IconButton(modifier = Modifier.size(36.dp), onClick = { vm.exportSessionAudioAndTranscript(sess.session) },
                                           enabled = player !is PendantViewModel.PlayerState.Decoding) {
                                    Icon(Icons.Filled.Share, contentDescription = "Export audio + transcript")
                                }
                                IconButton(modifier = Modifier.size(36.dp), onClick = { vm.shareTranscript(sess.session) }, enabled = hasTranscript) {
                                    Icon(Icons.Filled.Description, contentDescription = "Share transcript")
                                }
                                IconButton(modifier = Modifier.size(36.dp), onClick = { vm.transcribeSession(sess.session) },
                                           enabled = vm.model.value.path != null &&
                                               trans !is PendantViewModel.TransState.Running) {
                                    Icon(Icons.Filled.GraphicEq, contentDescription = "Transcribe")
                                }
                                val enrolledHere = speakerState.enrolled.contains(sess.session)
                                IconButton(modifier = Modifier.size(36.dp),
                                           enabled = speakerState.loaded,
                                           onClick = { if (enrolledHere) vm.unenrollSession(sess.session) else vm.enrollSession(sess.session) }) {
                                    Icon(
                                        if (enrolledHere) Icons.Filled.PersonAdd else Icons.Filled.PersonRemove,
                                        contentDescription = if (enrolledHere)
                                            "Marked as you; tap to remove your voice from this session"
                                        else
                                            "Not marked as you; tap to mark this session as your voice")
                                }
                                IconButton(modifier = Modifier.size(36.dp), onClick = { pendingDelete = sess }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete session")
                                }
                            }
                            val preview = remember(sess.session) { vm.transcriptPreview(sess.session) }
                            if (!preview.isNullOrEmpty()) {
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
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
fun ReflectionCard(vm: PendantViewModel) {
    val r by vm.reflection.collectAsState()
    val reviews by vm.reflectionReviews.collectAsState()
    val note by vm.reflectionNote.collectAsState()
    val aiNote by vm.aiNote.collectAsState()
    LaunchedEffect(Unit) { vm.loadReflections() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(7, "Daily reflection")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val cur = r
            if (cur == null) {
                Text(
                    note.ifBlank { "No reflection written yet. Cards are written once a day at the time set in the Daily AI card above." },
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { vm.aiGenerateNow() }) { Text("Write today's reflection now") }
                if (aiNote.isNotBlank()) Text(aiNote, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(cur.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (cur.summary.isNotBlank()) Text(cur.summary, style = MaterialTheme.typography.bodyMedium)
                if (cur.wins.isNotEmpty()) {
                    Text("Did well", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.wins.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (cur.improve.isNotEmpty()) {
                    Text("Improve", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.improve.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (cur.commitments.isNotEmpty()) {
                    Text("Commitments", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.commitments.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                cur.ifThen?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
                if (cur.keep.isNotEmpty()) {
                    Text("Keep doing", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.keep.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (cur.actions.isNotEmpty()) {
                    Text("To do", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.actions.forEach { a ->
                        val who = when (a.owner) {
                            "you" -> "You"
                            "them" -> if (a.person.isNotBlank()) a.person else "They"
                            else -> "?"
                        }
                        val meta = listOf(a.due, a.area).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
                        Text("\u2022 " + who + ": " + a.what + (if (meta.isNotBlank()) " (" + meta + ")" else "") +
                            (if (a.cite.isNotBlank()) " " + a.cite else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (cur.people.isNotEmpty()) {
                    Text("People", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.people.take(6).forEach { p ->
                        val meta = if (p.relationship.isNotBlank()) " (" + p.relationship + ")" else ""
                        Text("\u2022 " + p.name + meta + (if (p.cite.isNotBlank()) " " + p.cite else ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (cur.communication.isNotEmpty()) {
                    Text("How you came across", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    cur.communication.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (cur.focus.isNotBlank()) {
                    Text("Focus", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(cur.focus, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (reviews.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Longer reviews", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                reviews.forEach { rv ->
                    Text(
                        rv.kind.replaceFirstChar { c -> c.uppercaseChar() } + " (" + rv.label + "): " + rv.summary.take(200),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun VoiceInsightsCard(vm: PendantViewModel) {
    val sessions by vm.sessions.collectAsState()
    val speakerState by vm.speaker.collectAsState()
    val scores by vm.speakerScores.collectAsState()
    val insightsBusy by vm.insightsBusy.collectAsState()
    val insightsVersion by vm.insightsVersion.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(5, "Voice insights")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (!speakerState.loaded || speakerState.enrolled.isEmpty()) {
                Text(
                    "This card turns on when two things are done: the voice model is loaded, and one session is marked as yours (the person icon). " +
                        "It then shows the sessions where it heard you, what you said in each, and how your talking is going by day and month.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { vm.insightsNow() }, enabled = !insightsBusy) {
                    Text(if (insightsBusy) "Generating insights..." else "Insights now")
                }
                val insightsNote by vm.insightsNote.collectAsState()
                if (insightsNote.isNotBlank()) {
                    Text(insightsNote, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val insights = remember(sessions, scores, insightsVersion) { vm.voiceInsights() }
                if (insights.detected.isEmpty()) {
                    Text(
                        "No sessions detected your voice yet. Tap Score new sessions in the voice card above, then come back here.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "You were detected in ${insights.detected.size} session${if (insights.detected.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    insights.detected.take(6).forEach { d ->
                        Column {
                            Text(
                                "Session ${d.session} · " +
                                    (if (d.startMs > 0)
                                        java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
                                            .format(java.util.Date(d.startMs))
                                    else "date not recorded") +
                                    " · ${d.youPct}% you · ~${d.durationSec / 60}m ${d.durationSec % 60}s · ${d.words} words",
                                style = MaterialTheme.typography.bodySmall)
                            d.excerpt?.let { ex ->
                                Text(
                                    ex,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (insights.detected.size > 6) {
                        Text("+ ${insights.detected.size - 6} earlier sessions",
                             style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("By day", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    insights.days.take(7).forEach { s ->
                        Text(
                            "${s.label}: ${"%.0f".format(s.minutes)} min · ${s.sessions} session${if (s.sessions == 1) "" else "s"} · ${s.words} words · ${"%.1f".format(s.fillerRate)} filler words per 100",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("By month", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    insights.months.take(6).forEach { s ->
                        Text(
                            "${s.label}: ${"%.1f".format(s.minutes / 60.0)} h · ${s.sessions} sessions · ${s.words} words · ${"%.1f".format(s.fillerRate)} filler words per 100",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    insights.hints.forEach { h ->
                        Text(h, style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Numbered badge shown beside each setup card's title, so the order is obvious. */
@Composable
fun StepBadge(n: Int, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary,
                 style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Small arrow line placed between two setup cards, naming what comes next. */
@Composable
fun NextArrow(label: String) {
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text("\u2193  $label", style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun WhisperCard(vm: PendantViewModel, onPickModel: () -> Unit) {
    val model by vm.model.collectAsState()
    val trans by vm.trans.collectAsState()
    LaunchedEffect(Unit) { vm.refreshModelInfo() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(3, "Turn recordings into text")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val mp = model.path
            if (mp == null) {
                Text("No transcription model loaded yet. Pick a Whisper model file (.bin). The small one (tiny.en) is fine to start; bigger ones (base.en, small.en) turn the audio into better text.",
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
                Button(onClick = { vm.transcribeAllSessions(false, true) }, enabled = b == null) {
                    Text("Boost processing")
                }
                Text(
                    "Boost transcribes every recording at once with every core at top priority: much faster, but the phone gets hot and the battery drains. Leave it plugged in.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                            val display = remember(sess) { formatTranscriptText(text) }
                            Text(
                                if (text.isEmpty()) "No transcript saved for this session." else display,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 24.sp,
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
            val driveInfo by vm.driveFolderInfo.collectAsState()
            val mirrorNote by vm.mirrorNote.collectAsState()
            Text(
                if (driveFolder == null) "No folder linked yet."
                else driveInfo.ifBlank { "Folder linked." },
                style = MaterialTheme.typography.bodySmall,
            )
            if (driveFolder != null) {
                Button(onClick = { vm.mirrorNow() }) { Text("Mirror now") }
                if (mirrorNote.isNotBlank()) {
                    Text(mirrorNote, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Each transcript is copied to your Drive folder with its date in the name. Copies older than 14 days are deleted, so the folder stays small. Pick the folder once; the app never uses the internet on its own.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Auto-sync on (default): recordings copy to this phone by themselves when the pendant connects. Off: you tap Download flash yourself.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Keep recordings on the pendant (default): nothing is ever erased. Erase after copying: once a recording is safely on this phone, the pendant deletes it, so the next sync moves only new recordings. This phone then holds the only copy, so keep a backup of the zip it makes.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun AiCard(vm: PendantViewModel) {
    val enabled by vm.aiEnabled.collectAsState()
    val provider by vm.aiProvider.collectAsState()
    val key by vm.aiKey.collectAsState()
    val model by vm.aiModel.collectAsState()
    val baseUrl by vm.aiBaseUrl.collectAsState()
    val hour by vm.aiHour.collectAsState()
    val note by vm.aiNote.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(6, "Daily AI card (API key)")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Write cards automatically", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { vm.setAiEnabled(it) })
            }
            Text(
                "Every day at the time you set, the app sends that day's transcript text to the AI service you picked, and the card it writes back is saved in the same folder as the transcripts. Longer reviews (week, month, year) run on their own set days. Your audio never leaves the phone, and your key stays in the app's private storage.",
                style = MaterialTheme.typography.bodySmall,
            )
            AiDisclosure(provider, baseUrl)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Service", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.setAiProvider("gemini") }, enabled = provider != "gemini") { Text("Gemini") }
                Button(onClick = { vm.setAiProvider("openai"); vm.setAiBaseUrl("https://api.groq.com/openai/v1"); vm.setAiModel("openai/gpt-oss-120b") },
                       enabled = provider != "openai" || baseUrl != "https://api.groq.com/openai/v1") { Text("Groq") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.setAiProvider("openai"); vm.setAiBaseUrl("https://openrouter.ai/api/v1"); vm.setAiModel("openai/gpt-oss-120b") },
                       enabled = provider != "openai" || baseUrl != "https://openrouter.ai/api/v1") { Text("OpenRouter") }
                Button(onClick = { vm.setAiProvider("openai") },
                       enabled = provider != "openai" || (baseUrl != "https://api.groq.com/openai/v1" && baseUrl != "https://openrouter.ai/api/v1")) { Text("Other") }
            }
            OutlinedTextField(
                value = key, onValueChange = { vm.setAiKey(it) },
                label = { Text("API key") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(onClick = { vm.testAiKey() }) { Text("Save & test key") }
            OutlinedTextField(
                value = model, onValueChange = { vm.setAiModel(it) },
                label = { Text("Model (default: " + if (provider == "gemini") "gemini-2.5-flash)" else "gpt-4o-mini)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (provider == "openai") {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { vm.setAiBaseUrl(it) },
                    label = { Text("API base URL, e.g. https://api.openai.com/v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Card time", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.setAiHour((hour + 23) % 24) }) { Text("\u2212") }
                Text(aiHourText(hour), Modifier.padding(horizontal = 12.dp))
                Button(onClick = { vm.setAiHour((hour + 1) % 24) }) { Text("+") }
            }
            Button(onClick = { vm.aiGenerateNow() }) { Text("Write today's card now") }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
            Text(
                "Setting up, once:\n" +
                    "1. Choose the transcripts folder (Step 1 card). Cards are saved there.\n" +
                    "2. Get a free key. Gemini: open aistudio.google.com in a browser, sign in, tap Get API key, create one, copy it. Groq and OpenRouter also give free keys, no card needed (console.groq.com/keys, openrouter.ai/keys).\n" +
                    "3. Paste the key above and tap Save & test key. You should see 'Key works'.\n" +
                    "4. Turn on Write cards automatically and set the card time (9 pm is a good default).\n" +
                    "5. Tap Write today's card now to see one straight away.\n" +
                    "Cards are written once a day at the time you set. Tap Write today's card now any time you want one sooner. The new card appears in the Daily reflection card right below this one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

fun aiHourText(h: Int): String = when {
    h == 0 -> "12 am"
    h < 12 -> "$h am"
    h == 12 -> "12 pm"
    else -> "${h - 12} pm"
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

private fun experimentCheckLabel(r: String): String = when (r) {
    "attempted" -> "Tried it"
    "not_attempted" -> "Didn't try it"
    "no_opportunity" -> "Didn't come up"
    else -> "Unclear"
}

@Composable
fun ExperimentCard(vm: PendantViewModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ReflectionAi.PREFS, Context.MODE_PRIVATE) }
    val refl by vm.reflection.collectAsState()
    var active by remember { mutableStateOf<Experiment?>(null) }
    var past by remember { mutableStateOf(emptyList<Experiment>()) }
    var reload by remember { mutableStateOf(0) }
    var dismissed by remember { mutableStateOf(prefs.getString("exp_draft_dismissed", "") ?: "") }
    var writing by remember { mutableStateOf(false) }
    var whenText by remember { mutableStateOf("") }
    var doText by remember { mutableStateOf("") }
    val today = remember { java.time.LocalDate.now().toString() }
    LaunchedEffect(reload) {
        val both = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ExperimentStore.active(ctx) to ExperimentStore.history(ctx)
        }
        active = both.first
        past = both.second
    }
    val e = active
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Experiment", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (e != null) {
                Text(e.behaviour, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("When: " + e.trigger, style = MaterialTheme.typography.bodySmall)
                Text("Then: " + e.action, style = MaterialTheme.typography.bodySmall)
                if (e.success.isNotBlank()) Text("Success looks like: " + e.success, style = MaterialTheme.typography.bodySmall)
                Text(ExperimentLogic.summaryLine(e), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                val due = ExperimentLogic.reviewDue(e, today)
                Text(
                    if (due) "Review time: has it worked?" else "Review in " + ExperimentLogic.daysLeft(e, today) + " days",
                    style = MaterialTheme.typography.labelSmall)
                e.checks.takeLast(5).reversed().forEach { c ->
                    Text(
                        "\u2022 " + c.date + ": " + experimentCheckLabel(c.result) + (if (c.note.isNotBlank()) " (" + c.note + ")" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (due) {
                        TextButton(onClick = { ExperimentStore.extend(ctx, today); reload += 1 }) { Text("Keep going 7 more days") }
                        TextButton(onClick = { ExperimentStore.finish(ctx, "worked"); reload += 1 }) { Text("It worked") }
                        TextButton(onClick = { ExperimentStore.finish(ctx, "not_working"); reload += 1 }) { Text("Not working") }
                    }
                    TextButton(onClick = { ExperimentStore.finish(ctx, "stopped"); reload += 1 }) { Text("Stop") }
                }
            } else {
                val d = refl?.experimentDraft
                if (d != null && d.behaviour != dismissed) {
                    Text("Suggested from your latest daily card", style = MaterialTheme.typography.labelMedium)
                    Text(d.behaviour, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("When: " + d.trigger, style = MaterialTheme.typography.bodySmall)
                    Text("Then: " + d.action, style = MaterialTheme.typography.bodySmall)
                    if (d.success.isNotBlank()) Text("Success looks like: " + d.success, style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = { ExperimentStore.start(ctx, d, today); reload += 1 }) { Text("Start this experiment") }
                        TextButton(onClick = {
                            dismissed = d.behaviour
                            prefs.edit().putString("exp_draft_dismissed", d.behaviour).apply()
                        }) { Text("Not now") }
                    }
                    Text(
                        "Nothing starts until you tap Start. It runs for 7 days, and each night's card checks whether you tried it, " +
                            "counting only days the situation really came up.",
                        style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(
                        "No experiment running. When a daily card spots a real opportunity it will suggest one here, or you can write your own.",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { whenText = ""; doText = ""; writing = true }) { Text("Write my own") }
            }
            if (past.isNotEmpty()) {
                Text("Past experiments", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                past.take(3).forEach {
                    Text(
                        "\u2022 " + it.behaviour + " \u2014 " + ExperimentLogic.outcomeLabel(it) + " (" + it.startDate + ")",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (writing) {
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Write your own experiment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = whenText, onValueChange = { whenText = it },
                        label = { Text("When this happens...") }, singleLine = true)
                    OutlinedTextField(value = doText, onValueChange = { doText = it },
                        label = { Text("I will...") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (doText.isNotBlank()) {
                        ExperimentStore.start(ctx, ExperimentDraft(doText, whenText, doText, ""), today)
                        writing = false
                        reload += 1
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LoopRows(items: List<OpenLoop>, onDone: (Int) -> Unit, onDrop: (Int) -> Unit) {
    items.take(8).forEach { l ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(l.what, style = MaterialTheme.typography.bodySmall)
                val meta = listOf(l.person, l.due, l.area, "since " + l.firstSeen).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { onDone(l.id) }) { Text("Done") }
            TextButton(onClick = { onDrop(l.id) }) { Text("Drop") }
        }
    }
    if (items.size > 8) Text("and " + (items.size - 8) + " more", style = MaterialTheme.typography.labelSmall)
}

@Composable
fun OpenLoopsCard() {
    val ctx = LocalContext.current
    var loops by remember { mutableStateOf(emptyList<OpenLoop>()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        loops = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LoopStore.open(ctx) }
    }
    val mine = loops.filter { it.owner == "you" }
    val theirs = loops.filter { it.owner == "them" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (loops.isEmpty()) "Open loops" else "Open loops (" + loops.size + ")",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (loops.isEmpty()) {
                Text(
                    "Nothing open. To-dos and promises from your daily cards collect here until you tick them off. " +
                        "A ticked or dropped item never comes back when a later card mentions it again.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (mine.isNotEmpty()) {
                Text("You owe", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                LoopRows(mine, { LoopStore.setStatus(ctx, it, "done"); reload += 1 }, { LoopStore.setStatus(ctx, it, "dropped"); reload += 1 })
            }
            if (theirs.isNotEmpty()) {
                Text("Waiting on others", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                LoopRows(theirs, { LoopStore.setStatus(ctx, it, "done"); reload += 1 }, { LoopStore.setStatus(ctx, it, "dropped"); reload += 1 })
            }
        }
    }
}

@Composable
fun PeopleCard() {
    val ctx = LocalContext.current
    var people by remember { mutableStateOf(emptyList<PersonRecord>()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        people = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PeopleRegister.list(ctx) }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (people.isEmpty()) "People" else "People (" + people.size + ")",
                 style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (people.isEmpty()) {
                Text(
                    "Names that come up in your daily cards collect here, with the sessions that mention them. " +
                        "Nothing leaves the phone, and nothing is sent to the AI service.",
                    style = MaterialTheme.typography.bodySmall)
            }
            people.take(10).forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        val meta = mutableListOf<String>()
                        if (p.relationship.isNotBlank()) meta.add(p.relationship)
                        meta.add("in " + p.sessions.size + (if (p.sessions.size == 1) " conversation" else " conversations"))
                        Text(meta.joinToString(" \u00B7 "),
                             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { PeopleRegister.forget(ctx, p.name); reload += 1 }) { Text("Forget") }
                }
            }
            if (people.size > 10) Text("and " + (people.size - 10) + " more", style = MaterialTheme.typography.labelSmall)
            if (people.isNotEmpty()) TextButton(onClick = { PeopleRegister.clear(ctx); reload += 1 }) { Text("Forget everyone") }
        }
    }
}

@Composable
fun VoicesCard() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(ReflectionAi.PREFS, Context.MODE_PRIVATE) }
    var voices by remember { mutableStateOf(emptyList<VoiceProfile>()) }
    var people by remember { mutableStateOf(emptyList<PersonRecord>()) }
    var stats by remember { mutableStateOf(emptyMap<Int, VoiceTimeStat>()) }
    var reload by remember { mutableStateOf(0) }
    var on by remember { mutableStateOf(OtherVoices.enabled(prefs)) }
    var strict by remember { mutableStateOf(OtherVoices.matchThr(prefs)) }
    var listHidden by remember { mutableStateOf(prefs.getBoolean("voices_list_hidden", false)) }
    LaunchedEffect(reload) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v = OtherVoices.load(ctx).sortedByDescending { it.lastSeenMs }
            val pe = PeopleRegister.list(ctx)
            val st = OtherVoices.timeStats(ctx)
            voices = v; people = pe; stats = st
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(8, if (voices.isEmpty()) "Other voices" else "Other voices (" + voices.size + ")")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Learn recurring voices", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = on, onCheckedChange = { on = it; OtherVoices.setEnabled(prefs, it) })
            }
            Text(
                "When sessions are scored, any voice that is clearly not yours is grouped into a repeat voice you can name. " +
                    "Names stay on this phone. Only named voices are told to the AI service; unnamed ones stay “others”.",
                style = MaterialTheme.typography.bodySmall)
            if (voices.isEmpty()) {
                Text(
                    "No other voices yet. Score new sessions in the Teach it your voice card; a voice appears here once several windows agree it is the same person.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (listHidden && voices.isNotEmpty()) {
                TextButton(onClick = { listHidden = false; prefs.edit().putBoolean("voices_list_hidden", false).apply() }) {
                    Text("Show " + voices.size + " other voice" + if (voices.size == 1) "" else "s")
                }
            }
            if (!listHidden) {
            voices.take(10).forEach { v ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        if (v.name.isNotBlank()) v.name else "Voice " + v.id,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val st = stats[v.id]
                    Text(
                        "Heard in " + v.sessions.size + (if (v.sessions.size == 1) " conversation" else " conversations") +
                            (if (st != null) " \u00B7 " + Math.round(st.secondsWeek / 60.0) + " min this week \u00B7 last " +
                                java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(st.lastHeardMs)) else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        if (v.name.isBlank()) {
                            var nm by remember(v.id) { mutableStateOf("") }
                            OutlinedTextField(
                                value = nm, onValueChange = { nm = it },
                                label = { Text("Name") }, singleLine = true,
                                modifier = Modifier.width(150.dp))
                            Button(onClick = { if (OtherVoices.name(ctx, v.id, nm)) reload += 1 }) { Text("Save name") }
                            people.filter { pv -> pv.name.isNotBlank() && pv.sessions.any { it in v.sessions } }
                                .take(2).forEach { pv ->
                                    OutlinedButton(onClick = { if (OtherVoices.name(ctx, v.id, pv.name)) reload += 1 }) {
                                        Text("Call this " + pv.name + "?")
                                    }
                                }
                        } else {
                            TextButton(onClick = { OtherVoices.forget(ctx, v.id); reload += 1 }) { Text("Forget voice") }
                        }
                    }
                }
            }
            if (voices.size > 10) Text("and " + (voices.size - 10) + " more", style = MaterialTheme.typography.labelSmall)
            val namedIds = voices.filter { it.name.isNotBlank() }.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            val quiet = VoiceTime.catchUp(stats, namedIds, now)
            if (quiet.isNotEmpty()) {
                Text("Worth a catch-up", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                quiet.take(5).forEach { id ->
                    val nm = voices.firstOrNull { it.id == id }?.name ?: ""
                    val days = (now - (stats[id]?.lastHeardMs ?: now)) / VoiceTime.DAY_MS
                    Text("\u2022 $nm: not heard for $days days", style = MaterialTheme.typography.bodySmall)
                }
            }
                TextButton(onClick = { listHidden = true; prefs.edit().putBoolean("voices_list_hidden", true).apply() }) {
                    Text("Hide these voices")
                }
            }
            Text(
                "Match strictness: " + String.format(Locale.US, "%.2f", strict) +
                    "  (higher = only very sure matches)",
                style = MaterialTheme.typography.bodySmall)
            Slider(value = strict, onValueChange = { strict = it; OtherVoices.setMatchThr(prefs, it) },
                   valueRange = 0.50f..0.95f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { OtherVoices.clear(ctx); reload += 1 }) { Text("Forget all voices") }
            }
        }
    }
}

/** Who receives what when insights run, in plain words. Shown wherever the AI service is set up. */
@Composable
fun AiDisclosure(provider: String, baseUrl: String) {
    val who = if (provider == "gemini") "Google Gemini (generativelanguage.googleapis.com)" else {
        val host = try { java.net.URI(baseUrl.trim()).host } catch (_: Exception) { null }
        if (host.isNullOrBlank()) "the service at the base URL you enter" else host
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What leaves this phone", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Sent to $who, using your own key: the transcripts of the period being summarised. That is today for the daily card, " +
                "and the whole week, month, quarter, half-year or year for the longer reviews. Lines are marked YOU, OTHER or UNSURE, " +
                "with any names you have given to recurring voices, and a short description of your current experiment if one is running. " +
                "Transcripts include other people's words.",
            style = MaterialTheme.typography.bodySmall)
        Text(
            "Never sent: audio, voiceprints, your People list, your open loops, or your Drive files. " +
                "The only other online use is downloading a speech model when you choose to, and your Drive folder if you mirror transcripts there.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SpeakerCard(vm: PendantViewModel, onPickModel: () -> Unit) {
    val speaker by vm.speaker.collectAsState()
    val scores by vm.speakerScores.collectAsState()
    val note by vm.speakerNote.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StepBadge(4, "Teach it your voice")
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Tells your voice from background chatter and TV. Everything runs on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onPickModel) {
                Text(if (speaker.loaded) "Voice model loaded (tap to replace)" else "Load voice model (.onnx)")
            }
            Text(
                "Wear the pendant and chat normally for a minute, sync, then tap the person+ icon next to that session in the Recordings card below. One session is enough; more makes the match better.",
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
                Text("Score new sessions")
            }
            TextButton({ vm.scoreSessions(true) },
                   enabled = speaker.loaded && speaker.enrolled.isNotEmpty()) {
                Text("Rescore all sessions")
            }
            Button({ vm.scoreSessions(true, true) },
                   enabled = speaker.loaded && speaker.enrolled.isNotEmpty()) {
                Text("Boost processing")
            }
            Text(
                "Boost rescoring runs several sessions at once on every core at top priority: much faster, but the phone gets hot.",
                style = MaterialTheme.typography.bodySmall,
            )
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
