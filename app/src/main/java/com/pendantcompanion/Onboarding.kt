package com.pendantcompanion

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pendantcompanion.ui.PendantViewModel
import com.pendantcompanion.ui.PendantViewModel.State
import com.pendantcompanion.ui.PendantViewModel.TransState
import kotlinx.coroutines.delay

private enum class Step { WELCOME, CONNECT, SYNC, MODEL, TRANSCRIBE, VOICE, INSIGHTS, FINISH }

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

    val needed = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok ->
        if (ok.values.any { it }) { vm.startScan(); step = Step.CONNECT }
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
            step = if (model.ready) Step.TRANSCRIBE else Step.MODEL
        }
    }

    // A model already downloaded on a previous run is validated on entry,
    // so returning users skip the download step automatically.
    LaunchedEffect(step) {
        if (step == Step.MODEL) vm.ensureModelValidated()
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
                    Text("The app needs a speech model. Download it once (about 45 to 60 MB), or pick a file you already have.")
                    Button(onClick = { vm.downloadWhisperModel(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q8_0.bin",
                        "tiny.en (~43 MB, fastest)") }) { Text("Download the fast model (~43 MB)") }
                    Button(onClick = { vm.downloadWhisperModel(
                        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin",
                        "base.en (~60 MB, more accurate)") }) { Text("Download the better model (~60 MB)") }
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
                OutlinedButton(onClick = { step = Step.VOICE }) { Text("Continue") }
            }

            Step.VOICE -> {
                StepHead(4, "Your voice", "Teach the app what you sound like, so every session shows how much of it is you.")
                if (!speaker.loaded) {
                    Text("First, the voice model: a one-time file (about 85 MB) that runs only on this phone.")
                    Button(onClick = { pickVoice.launch(arrayOf("*/*")) }) { Text("Pick the voice model (.onnx)") }
                    Text("The file is ecapa-any-length.onnx; the app's guide says where to get it. You can also finish this step later from the main screen.", style = MaterialTheme.typography.bodySmall)
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
