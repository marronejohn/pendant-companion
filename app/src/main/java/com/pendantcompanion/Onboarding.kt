package com.pendantcompanion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pendantcompanion.ui.PendantViewModel
import com.pendantcompanion.ui.PendantViewModel.State
import com.pendantcompanion.ui.PendantViewModel.TransState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class Step { WELCOME, CONNECT, SYNC, MODEL, TRANSCRIBE, VOICE, INSIGHTS, FINISH }

/** The v0.14 first run: one screen, one button. Everything the old wizard did
 *  still happens, but silently afterwards; nothing here blocks on it. */
private enum class FrPhase { START, SCANNING, NOT_FOUND }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FirstRunScreen(vm: PendantViewModel) {
    val state by vm.state.collectAsState()
    val btOn by vm.btOn.collectAsState()

    var phase by remember { mutableStateOf(FrPhase.START) }
    var showSupport by remember { mutableStateOf(false) }
    var showTrail by remember { mutableStateOf(false) }
    var pairingAttempted by remember { mutableStateOf(false) }
    var lastEventAt by remember { mutableStateOf(0L) }
    val trail = remember { mutableStateListOf<String>() }
    fun wlog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        trail.add(0, "$ts $msg")
        if (trail.size > 6) trail.removeAt(trail.lastIndex)
        lastEventAt = System.currentTimeMillis()
    }
    LaunchedEffect(Unit) { wlog("first run open, v0.14.0") }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok ->
        val btGranted = ok.entries.any {
            (it.key == Manifest.permission.BLUETOOTH_SCAN ||
                it.key == Manifest.permission.BLUETOOTH_CONNECT ||
                it.key == Manifest.permission.ACCESS_FINE_LOCATION) && it.value
        }
        if (btGranted) {
            wlog("permissions ok")
            vm.startScan()
            phase = FrPhase.SCANNING
        } else {
            wlog("permission denied")
        }
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    LaunchedEffect(btOn, phase) {
        if (phase == FrPhase.SCANNING && !btOn) {
            wlog("asking to turn Bluetooth on")
            try { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
        }
    }

    // One button then hands off: auto-pair the best scan hit, and once the
    // pendant is connected the first run is over.
    LaunchedEffect(state) {
        when (val st = state) {
            is State.ScanResults -> {
                if (!pairingAttempted && st.hits.isNotEmpty() &&
                    (phase == FrPhase.SCANNING || phase == FrPhase.NOT_FOUND)) {
                    pairingAttempted = true
                    val best = st.hits.maxByOrNull { it.rssi } ?: st.hits.first()
                    wlog("pendant seen (${best.name ?: best.device.address}), pairing")
                    vm.pairAndConnect(best)
                }
            }
            is State.Connected -> {
                wlog("pendant connected")
                vm.finishSetup()
            }
            else -> {}
        }
    }

    // No progress for 20 s: say so plainly, and let the screen diagnose itself.
    LaunchedEffect(phase) {
        while (isActive) {
            delay(1000)
            if (phase == FrPhase.SCANNING &&
                System.currentTimeMillis() - lastEventAt >= 20_000) {
                wlog("no pendant found in 20 s")
                phase = FrPhase.NOT_FOUND
                showTrail = true
            }
        }
    }

    val ink = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(110.dp)) {
            val w = size.width; val h = size.height
            drawCircle(ink, radius = h * 0.10f, center = Offset(w / 2f, h * 0.16f),
                style = Stroke(width = h * 0.045f))
            drawCircle(ink, radius = h * 0.32f, center = Offset(w / 2f, h * 0.62f))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Pendant Companion",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { showSupport = true }) },
        )
        Spacer(Modifier.height(10.dp))
        when (phase) {
            FrPhase.START -> {
                Text("Wear your pendant and keep it close. One tap and I'll take it from there.",
                     textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Button(onClick = {
                    wlog("find tapped")
                    val wanted = buildList {
                        if (Build.VERSION.SDK_INT >= 31) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        } else add(Manifest.permission.ACCESS_FINE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }.toTypedArray()
                    pairingAttempted = false
                    perms.launch(wanted)
                }) { Text("Find my pendant") }
            }
            FrPhase.SCANNING -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Looking for your pendant...")
                if (!btOn) Text("Turn Bluetooth on when the phone asks.",
                                style = MaterialTheme.typography.bodySmall)
            }
            FrPhase.NOT_FOUND -> {
                Text("Couldn't find your pendant yet.")
                Spacer(Modifier.height(6.dp))
                Text("Wake it by pressing its button, and keep it within a metre.",
                     style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Button(onClick = {
                    wlog("try again")
                    pairingAttempted = false
                    vm.startScan()
                    phase = FrPhase.SCANNING
                }) { Text("Try again") }
            }
        }
        if (showSupport || showTrail) {
            Spacer(Modifier.height(20.dp))
            Text("Support info", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
            trail.forEach { t ->
                Text(t, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val clip = LocalClipboardManager.current
            TextButton(onClick = { clip.setText(AnnotatedString("v0.14.0\n" + trail.joinToString("\n"))) }) {
                Text("Copy")
            }
        }
    }
}

/** The public-edition first-run flow: find and connect the pendant, copy the
 *  recordings, transcribe them, teach the app the user's voice, set up the
 *  daily insights. The main app appears only once this is finished or skipped. */
@Composable
fun SetupWizard(vm: PendantViewModel) {
    var step by remember { mutableStateOf(Step.WELCOME) }

    val state by vm.state.collectAsState()
    val btOn by vm.btOn.collectAsState()
    val sync by vm.sync.collectAsState()
    val batch by vm.batch.collectAsState()
    val trans by vm.trans.collectAsState()
    val model by vm.model.collectAsState()
    val dl by vm.modelDownload.collectAsState()
    val speaker by vm.speaker.collectAsState()
    val speakerNote by vm.speakerNote.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val driveFolder by vm.driveFolder.collectAsState()

    val aiEnabled by vm.aiEnabled.collectAsState()
    val aiProvider by vm.aiProvider.collectAsState()
    val aiKey by vm.aiKey.collectAsState()
    val aiBaseUrl by vm.aiBaseUrl.collectAsState()
    val aiHour by vm.aiHour.collectAsState()
    val aiNote by vm.aiNote.collectAsState()

    var syncEta by remember { mutableStateOf<String?>(null) }
    var syncStartAt by remember { mutableStateOf(0L) }
    var batchStartAt by remember { mutableStateOf(0L) }
    var transKicked by remember { mutableStateOf(false) }
    var micOn by remember { mutableStateOf(false) }
    var micSecs by remember { mutableStateOf(0) }
    var micLevel by remember { mutableStateOf(0f) }
    val recorder = remember { MicRecorder() }

    // Status trail: the last few internal events, shown in small print at the
    // bottom of every step, so a stuck screen says exactly where it stopped.
    val trail = remember { mutableStateListOf<String>() }
    fun wlog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        trail.add(0, "$ts $msg")
        if (trail.size > 6) trail.removeAt(trail.lastIndex)
    }

    val needed = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok ->
        if (ok.values.any { it }) { wlog("permissions ok"); vm.startScan(); step = Step.CONNECT }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { micSecs = 0; micOn = recorder.start() }
    }
    val pickWhisper = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSpeakerModel(uri)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDriveFolder(uri)
    }

    // Connected -> the sync step (the connect path already starts the sync).
    LaunchedEffect(state) {
        val st = state
        if (st is State.Connected && step == Step.CONNECT) {
            wlog("pendant connected")
            if (!vm.autoSync.value) vm.syncReadOnly()
            syncStartAt = 0L
            syncEta = null
            step = Step.SYNC
        }
    }

    // Sync progress + estimate; when it finishes, move on.
    LaunchedEffect(sync) {
        val sp = sync ?: return@LaunchedEffect
        if (step != Step.SYNC) return@LaunchedEffect
        if (!sp.finished) {
            if (sp.pagesSaved > 0 && syncStartAt == 0L) syncStartAt = System.currentTimeMillis()
            val newest = (state as? State.Connected)?.info?.newestFlashPage ?: 0
            if (sp.pagesSaved >= 8 && newest > 0 && sp.lastPageIndex >= 0 && syncStartAt > 0) {
                val elapsedMin = ((System.currentTimeMillis() - syncStartAt) / 60000.0).coerceAtLeast(0.02)
                val rate = sp.pagesSaved / elapsedMin
                val remaining = (newest - sp.lastPageIndex).coerceAtLeast(0)
                syncEta = if (remaining > 0 && rate > 0.005)
                    "about ${kotlin.math.ceil(remaining / rate).toInt()} min left" else null
            }
        } else {
            wlog("sync finished: ${sp.pagesSaved} pages")
            step = if (model.ready) Step.TRANSCRIBE else Step.MODEL
        }
    }

    // A model already downloaded on a previous run is validated on entry,
    // so returning users skip the download step automatically.
    LaunchedEffect(step) {
        if (step == Step.MODEL) vm.ensureModelValidated()
    }

    LaunchedEffect(step) { wlog("step ${step.name}") }

    LaunchedEffect(model) {
        if (model.ready) wlog("model ready, validated ok")
    }

    LaunchedEffect(trans) {
        val te = trans
        if (te is TransState.TransError) wlog("error: ${te.message.take(70)}")
    }

    LaunchedEffect(batch) {
        val b = batch
        if (b != null && (b.note == "finished" || b.note.startsWith("batch failed") || b.note.startsWith("starting")))
            wlog("batch: ${b.note.take(50)} (${b.done}/${b.total})")
    }

    // The transcription stage: entering it kicks the batch if nothing is
    // running, and the step moves on when the batch finishes or when there is
    // nothing new to transcribe (the batch never starts then).
    LaunchedEffect(step, model, dl, batch) {
        if (step == Step.MODEL && model.ready && dl == null) {
            step = Step.TRANSCRIBE
        }
        if (step != Step.TRANSCRIBE) return@LaunchedEffect
        if (!transKicked && batch == null) {
            transKicked = true
            vm.transcribeAllSessions(onlyNew = true)
            if (batchStartAt == 0L) batchStartAt = System.currentTimeMillis()
        }
        var quiet = 0
        while (step == Step.TRANSCRIBE) {
            delay(1000)
            val b = vm.batch.value
            if (b != null) {
                quiet = 0
                if (b.note == "finished") { step = Step.VOICE; return@LaunchedEffect }
                if (b.note.startsWith("batch failed")) return@LaunchedEffect
            } else {
                quiet++
                // A running batch sets its state within a few seconds; if the
                // batch stays null the onlyNew filter found nothing to do.
                if (quiet >= 6) { step = Step.VOICE; return@LaunchedEffect }
            }
        }
    }

    // Mic recording loop with a live level meter and a 30 s cap.
    LaunchedEffect(micOn) {
        if (!micOn) return@LaunchedEffect
        while (micOn) {
            delay(250)
            micSecs = recorder.elapsedSec()
            micLevel = recorder.levelNow()
            if (micSecs >= 30) {
                micOn = false
                recorder.stop()?.let { vm.enrollFromMic(it, 1) }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (step) {
            Step.WELCOME -> {
                StepHead(1, "Hello", "Let's set your pendant up in about ten minutes.")
                Text("This app turns the Omi / Limitless pendant into an automatic diary. " +
                    "Five short steps: find and connect the pendant, copy its recordings onto this phone, " +
                    "turn them into text, teach the app your voice, and set up your daily insights.")
                Text("Everything runs on this phone. The only thing that ever leaves it is text you choose to send to your AI service for the daily card.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { perms.launch(needed) }) { Text("Get started") }
                if (!btOn) Text("Bluetooth is off. Turn it on, then tap Get started.", style = MaterialTheme.typography.bodySmall)
            }

            Step.CONNECT -> {
                StepHead(1, "Find your pendant", "Wake it up (press the side button), then it shows up below.")
                when (val st = state) {
                    is State.ScanResults -> {
                        if (st.hits.isEmpty()) Text("Nothing yet. Wake the pendant and keep it close.", style = MaterialTheme.typography.bodySmall)
                        st.hits.forEach { hit ->
                            val label = (hit.name ?: "unnamed device") +
                                "  (" + kotlin.math.abs(hit.rssi) + " m away signal)" +
                                if (hit.advertisesPendantService) "" else "  [no pendant service]"
                            Button(
                                onClick = { vm.pairAndConnect(hit) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(label) }
                        }
                        OutlinedButton(onClick = { vm.startScan() }) { Text("Scan again") }
                    }
                    is State.Scanning -> {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Scanning for the pendant...")
                    }
                    is State.Connecting -> Text("Pairing with ${st.deviceName}...")
                    is State.Error -> {
                        Text("Trouble: ${st.message}", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = { vm.startScan() }) { Text("Scan again") }
                    }
                    else -> {
                        OutlinedButton(onClick = { vm.startScan() }) { Text("Start scanning") }
                    }
                }
                OutlinedButton(onClick = { step = Step.MODEL }) { Text("Set up the pendant later") }
            }

            Step.SYNC -> {
                StepHead(2, "Copy the recordings", "The pendant's recordings move onto this phone. This runs automatically from now on.")
                val sp = sync
                if (sp == null) {
                    Text("Waiting for the sync to start...")
                } else {
                    Text("Copied so far: ${sp.pagesSaved} recordings pages, " +
                        String.format(java.util.Locale.US, "%.1f", sp.bytesSaved / 1_000_000.0) + " MB of audio")
                    Text(sp.note, style = MaterialTheme.typography.bodySmall)
                    syncEta?.let { Text(it, fontWeight = FontWeight.Bold) }
                }
                Text("The pendant's own copy is untouched; the phone keeps its own set.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { step = if (model.ready) Step.TRANSCRIBE else Step.MODEL }) {
                    Text("Skip ahead")
                }
            }

            Step.MODEL -> {
                StepHead(3, "Transcription engine", "A one-time download that turns the audio into text, entirely on this phone.")
                val d = dl
                if (d != null) {
                    val (label, got, total) = d
                    Text("Downloading $label: " +
                        String.format(java.util.Locale.US, "%.0f", got / 1_000_000.0) + " MB" +
                        (if (total > 0) " of " + String.format(java.util.Locale.US, "%.0f", total / 1_000_000.0) + " MB" else ""))
                    if (total > 0) LinearProgressIndicator(got.toFloat() / total, Modifier.fillMaxWidth())
                } else {
                    Text("The app needs a speech model. Download it once, or pick a file you already have.")
                    Button(onClick = { wlog("download tapped"); vm.downloadWhisperModel(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin",
                        "small.en (~182 MB, most accurate)",
                        "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin") }) {
                        Text("Download the best free model (~182 MB)") }
                    Text("small.en is the most accurate free model that still runs well on a phone; transcription takes a little longer.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { wlog("download tapped"); vm.downloadWhisperModel(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
                        "base.en (~60 MB, more accurate)",
                        "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin") }) { Text("Download the better model (~60 MB)") }
                    Button(onClick = { wlog("download tapped"); vm.downloadWhisperModel(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin",
                        "tiny.en (~43 MB, fastest)",
                        "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin") }) { Text("Download the fast model (~43 MB)") }
                    OutlinedButton(onClick = { pickWhisper.launch(arrayOf("*/*")) }) { Text("Pick a model file instead") }
                }
                val te = trans as? TransState.TransError
                te?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            Step.TRANSCRIBE -> {
                StepHead(3, "Transcribe", "Your recordings become text, right on this phone. Nothing is uploaded.")
                val b = batch
                if (b != null) {
                    if (b.note == "finished") {
                        Text("All done: ${b.done} of ${b.total} sessions transcribed" +
                            (if (b.failed > 0) ", ${b.failed} failed (you can retry later)" else "") + ".")
                    } else {
                        Text("Session ${b.session}: ${b.note}")
                        Text("${b.done} of ${b.total} sessions done" +
                            (if (b.failed > 0) ", ${b.failed} failed" else ""), style = MaterialTheme.typography.bodySmall)
                        if (b.fraction > 0.03f && batchStartAt > 0) {
                            val elapsed = (System.currentTimeMillis() - batchStartAt) / 1000.0
                            val etaMin = elapsed * (1 - b.fraction) / b.fraction / 60.0
                            if (etaMin > 0.5) Text("about ${kotlin.math.ceil(etaMin).toInt()} min left", fontWeight = FontWeight.Bold)
                        }
                    }
                } else Text("Preparing...")
                val te = trans as? TransState.TransError
                te?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (te != null) {
                    OutlinedButton(onClick = { transKicked = false; vm.transcribeAllSessions(onlyNew = true) }) { Text("Try again") }
                }
                OutlinedButton(onClick = { step = Step.VOICE }) { Text("Continue") }
            }

            Step.VOICE -> {
                StepHead(4, "Your voice", "Teach the app what you sound like, so every session shows how much of it is you.")
                if (!speaker.loaded) {
                    Text("First, the voice model: a one-time file (about 85 MB) that runs only on this phone.")
                    Button(onClick = { vm.downloadEcapaModel(
                        "https://github.com/marronejohn/pendant-companion/releases/download/v0.15.1-models/ecapa-any-length.onnx",
                        "voice model (~85 MB)") }) { Text("Download the voice model (~85 MB)") }
                    Button(onClick = { pickVoice.launch(arrayOf("*/*")) }) { Text("Pick the voice model (.onnx) instead") }
                    Text("The download is checked against the known-good copy before it loads (md5 32728b2f\u2026). You can also finish this step later from the main screen.", style = MaterialTheme.typography.bodySmall)
                } else {
                    if (!micOn) {
                        Text("Now record yourself: 30 seconds of normal talking, a few sentences about your day.")
                        Button(onClick = { micPerm.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Record 30 seconds of me talking") }
                        Text("You can redo it as many times as you like.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Recording: $micSecs s of 30")
                        LinearProgressIndicator(micSecs / 30f, Modifier.fillMaxWidth())
                        Text(if (micLevel > 0.01f) "Hearing you, keep talking" else "Very quiet: talk up a little", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            micOn = false
                            recorder.stop()?.let { vm.enrollFromMic(it, 1) }
                        }) { Text("Stop now") }
                    }
                    val cands = sessions.filter { it.frames > 0 && it.durationSec >= 45 }
                        .sortedByDescending { it.durationSec }.take(3)
                    if (cands.isNotEmpty()) {
                        Text("Or learn your voice from a pendant recording:", style = MaterialTheme.typography.bodySmall)
                        cands.forEach { c ->
                            OutlinedButton(onClick = { vm.enrollSession(c.session) }) {
                                Text("Learn from session ${c.session} (${c.durationSec / 60} min)")
                            }
                        }
                    }
                    if (speaker.enrolled.isNotEmpty()) {
                        Text(speakerNote ?: "Voiceprint ready.", style = MaterialTheme.typography.bodySmall)
                        Text("Learned from: " + speaker.enrolled.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { step = Step.INSIGHTS }) { Text("Continue") }
                        OutlinedButton(onClick = { vm.clearEnrollment() }) { Text("Start over") }
                    }
                }
                OutlinedButton(onClick = { step = Step.INSIGHTS }) { Text("Skip for now") }
            }

            Step.INSIGHTS -> {
                StepHead(5, "Daily insights", "Each evening at a time you pick, the app asks an AI service to write your day's reflection card.")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { vm.setAiProvider("gemini") }, enabled = aiProvider != "gemini") { Text("Gemini") }
                    Button(onClick = { vm.setAiProvider("openai"); vm.setAiBaseUrl("https://api.groq.com/openai/v1"); vm.setAiModel("openai/gpt-oss-120b") },
                           enabled = aiProvider != "openai" || aiBaseUrl != "https://api.groq.com/openai/v1") { Text("Groq") }
                    Button(onClick = { vm.setAiProvider("openai"); vm.setAiBaseUrl("https://openrouter.ai/api/v1"); vm.setAiModel("openai/gpt-oss-120b") },
                           enabled = aiProvider != "openai" || aiBaseUrl != "https://openrouter.ai/api/v1") { Text("OpenRouter") }
                }
                OutlinedTextField(
                    value = aiKey, onValueChange = { vm.setAiKey(it) },
                    label = { Text("API key (free; no card needed)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("Free keys: aistudio.google.com for Gemini, console.groq.com/keys for Groq, openrouter.ai/keys for OpenRouter.", style = MaterialTheme.typography.bodySmall)
                AiDisclosure(aiProvider, aiBaseUrl)
                Button(onClick = { pickFolder.launch(null) }) {
                    Text(if (driveFolder == null) "Pick where cards and transcripts go (a Drive folder)"
                         else "Cards folder set (tap to change)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Card time", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { vm.setAiHour((aiHour + 23) % 24) }) { Text("-") }
                    Text(aiHourText(aiHour), Modifier.padding(horizontal = 12.dp))
                    Button(onClick = { vm.setAiHour((aiHour + 1) % 24) }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Write cards automatically", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = aiEnabled, onCheckedChange = { vm.setAiEnabled(it) })
                }
                Button(onClick = { vm.aiGenerateNow() }) { Text("Write today's card now (test)") }
                if (aiNote.isNotBlank()) Text(aiNote, style = MaterialTheme.typography.bodySmall)
                Text("Only the day's transcript text goes to the AI service, with your own key. The audio and the voiceprint never leave the phone.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.finishSetup(); step = Step.FINISH }) { Text("Finish setup") }
                    OutlinedButton(onClick = { step = Step.FINISH }) { Text("Set up later") }
                }
            }

            Step.FINISH -> {
                StepHead(1, "All set", "Here is what happens by itself from now on.")
                Text("When the pendant connects, new recordings sync to this phone, get transcribed, and are scored against your voiceprint. " +
                    "At your card time, today's reflection card is written; bigger weekly and monthly cards are written overnight. " +
                    "Everything is in the cards folder you picked.")
                Button(onClick = { vm.finishSetup() }) { Text("Open the app") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trail.forEach { t ->
            Text(t, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StepHead(n: Int, title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Pendant Companion setup  ·  step $n of 5",
             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
    }
}

/** Records about 40 s of 16 kHz mono PCM and hands back the samples. */
private class MicRecorder {
    private var rec: AudioRecord? = null
    private var data: ShortArray? = null
    private var count = 0
    private var thread: Thread? = null
    @Volatile private var level = 0f
    @Volatile private var startedAt = 0L

    fun start(): Boolean {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        val buf = ShortArray(rate * 40)
        r.startRecording()
        rec = r; data = buf; count = 0; startedAt = System.currentTimeMillis()
        thread = Thread {
            val tmp = ShortArray(1600)
            while (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val n = r.read(tmp, 0, tmp.size)
                if (n > 0) {
                    synchronized(this@MicRecorder) {
                        val space = buf.size - count
                        val take = minOf(n, space)
                        System.arraycopy(tmp, 0, buf, count, take)
                        count += take
                    }
                    var sum = 0.0
                    for (i in 0 until n step 2) { val g = tmp[i] / 32768f; sum += g * g }
                    level = kotlin.math.sqrt(sum / (n / 2)).toFloat()
                }
            }
        }.also { it.start() }
        return true
    }

    fun elapsedSec(): Int = if (startedAt == 0L) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()
    fun levelNow(): Float = level

    fun stop(): FloatArray? {
        try { rec?.stop() } catch (_: Exception) {}
        thread?.join(1500)
        val out: FloatArray = synchronized(this@MicRecorder) {
            val c = count; val src = data
            if (src == null || c < 16_000 * 8) FloatArray(0)
            else FloatArray(c) { i -> src[i] / 32768f }
        }
        try { rec?.release() } catch (_: Exception) {}
        rec = null; data = null; count = 0; thread = null; startedAt = 0L
        return if (out.isEmpty()) null else out
    }
}
