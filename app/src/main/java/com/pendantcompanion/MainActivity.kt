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

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Pendant Companion", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (val s = state) {
                is Idle -> "No pendant connected"
                is Scanning -> "Scanning for your LS-PT-01..."
                is ScanResults -> "${s.hits.size} pendant candidate(s) found"
                is Connecting -> "Connecting to ${s.deviceName}..."
                is Connected -> "Connected: ${s.deviceName}"
                is Error -> s.message
            },
            style = MaterialTheme.typography.bodyMedium,
        )
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
            is Connected -> DeviceInfoCard(s, onDisconnect = { vm.disconnect() })
        }
    }
}

@Composable
fun DeviceInfoCard(s: Connected, onDisconnect: () -> Unit) {
    val i = s.info
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
        Button(onDisconnect) { Text("Disconnect") }
        Text("Nothing on the pendant was changed. Recording sync arrives in the next phase.",
            style = MaterialTheme.typography.bodySmall)
    }
}
