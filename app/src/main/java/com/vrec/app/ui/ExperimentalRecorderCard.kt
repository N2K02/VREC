package com.vrec.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.launch

/**
 * Experimental connection UI. Never marks call audio as functional on the basis
 * of successful ADB pairing; shell UID and capture health are separate checks.
 */
@Composable
fun ExperimentalRecorderCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkPermission = when {
        Build.VERSION.SDK_INT >= 37 -> "android.permission.ACCESS_LOCAL_NETWORK"
        Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }
    var networkAllowed by remember {
        mutableStateOf(networkPermission == null ||
            context.checkSelfPermission(networkPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val networkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { networkAllowed = it }
    val state by ExperimentalRecording.status.collectAsState()
    val recorderMessage by ExperimentalCaptureDaemon.message.collectAsState()
    val recorderReady by ExperimentalCaptureDaemon.ready.collectAsState()
    var selection by remember { mutableStateOf(ExperimentalRecording.method(context)) }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf("") }
    var tcpWarning by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var shizukuLabel by remember { mutableStateOf(ExperimentalRecording.shizukuStatus()) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun openDeveloperOptions() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            localError = "Developer options unavailable on this device. Standard recording still works."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recording method", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Choose how VRec records. Standard still works without debugging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                Triple(ExperimentalRecording.Method.STANDARD,
                    "Standard (recommended)", "VRec microphone recorder. No extra setup."),
                Triple(ExperimentalRecording.Method.BUILT_IN_ADB,
                    "Built-in ADB", "Pair directly on this phone without notification or Shizuku."),
                Triple(ExperimentalRecording.Method.SHIZUKU,
                    "Shizuku", "Use an already running, authorized Shizuku service."),
            ).forEach { (method, label, subtitle) ->
                Row(
                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = selection == method, onClick = {
                        selection = method
                        ExperimentalRecording.select(context, method)
                        scope.launch {
                            if (method == ExperimentalRecording.Method.STANDARD) {
                                if (recorderReady) ExperimentalCaptureDaemon.stopRecording(context)
                            } else if (recorderReady) {
                                ExperimentalCaptureDaemon.setMode(context, true)
                            }
                        }
                    })
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (selection != ExperimentalRecording.Method.STANDARD) {
                HorizontalDivider()
                Text("Enhanced recording service", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (recorderReady) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Text(if (recorderReady) "Recorder running" else "Recorder not ready",
                        style = MaterialTheme.typography.titleSmall)
                }
                Text(recorderMessage, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Once started, the shell recorder can keep recording without an active " +
                        "ADB session. Its availability after Wi-Fi loss, force-stop or reboot " +
                        "depends on your phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(enabled = !state.busy, onClick = {
                    scope.launch {
                        ExperimentalCaptureDaemon.ensureStarted(context).onFailure {
                            localError = it.message
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (recorderReady) "Check / restart recorder" else "Start enhanced recorder")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            ExperimentalCaptureDaemon.collect(context)
                                .onSuccess { localError = "Imported " + it + " call recordings." }
                                .onFailure { localError = it.message }
                        }
                    }) { Text("Import saved calls") }
                    OutlinedButton(enabled = recorderReady, onClick = {
                        scope.launch { ExperimentalCaptureDaemon.stopRecording(context) }
                    }) { Text("Pause recorder") }
                }
                Text("Two-sided audio: not verified until a real consented call is recorded and played.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (selection == ExperimentalRecording.Method.BUILT_IN_ADB) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.connected) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Column {
                        Text(
                            if (state.connected) "ADB shell connected" else "ADB not connected",
                            style = MaterialTheme.typography.titleSmall)
                        Text("Shell identity: " +
                            (state.shellUid?.toString() ?: "not verified"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showPairing = !showPairing }) {
                    Text(if (showPairing) "Hide pairing setup" else "Pair without notification")
                }
                if (showPairing) {
                    if (networkPermission != null && !networkAllowed) {
                        Text("Android requires local network access to find its ADB pairing port.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            networkPermissionLauncher.launch(networkPermission)
                        }) { Text("Allow local ADB discovery") }
                    }
                    Text("1. Open Android Developer options → Wireless debugging → Pair with pairing code.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("2. Use split screen to keep Android's code visible while entering it in VRec.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = ::openDeveloperOptions) {
                        Text("Open Developer options")
                    }
                    OutlinedButton(
                        enabled = !state.busy && networkAllowed && Build.VERSION.SDK_INT >= 30,
                        onClick = {
                            scope.launch {
                                val result = ExperimentalRecording.discover(context)
                                result.getOrNull()?.let { pairingPort = it.toString() }
                            }
                        },
                    ) { Text("Find pairing port") }
                    OutlinedTextField(
                        value = pairingPort, onValueChange = {
                            pairingPort = it.filter(Char::isDigit).take(5)
                        }, label = { Text("Pairing port") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = pairingCode, onValueChange = {
                            pairingCode = it.filter(Char::isDigit).take(6)
                        }, label = { Text("Six-digit pairing code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Button(
                        enabled = !state.busy && pairingCode.length == 6 &&
                            (pairingPort.toIntOrNull() ?: 0) in 1..65535,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    ExperimentalRecording.pair(context,
                                        pairingPort.toInt(), pairingCode).getOrThrow()
                                }.onFailure { localError = it.message }
                                pairingCode = ""
                            }
                        },
                    ) { Text("Pair this phone") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !state.busy, onClick = {
                        scope.launch {
                            runCatching {
                                ExperimentalRecording.connect(
                                    context, connectionPort.toIntOrNull(),
                                ).getOrThrow()
                            }.onFailure { localError = it.message }
                        }
                    }) { Text("Connect & verify") }
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Hide advanced" else "Offline / TCP")
                    }
                }
                if (showAdvanced) {
                    HorizontalDivider()
                    Text("TCP/5555 — optional, can stay reachable without Wi-Fi on supported phones.",
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Security: TCP debugging may also listen on your Wi-Fi network. It does not" +
                            " survive every reboot and does not guarantee access to call audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = connectionPort,
                        onValueChange = { connectionPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Existing ADB port (optional)") },
                        supportingText = { Text("Leave blank to discover port automatically.") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = state.connected && !state.busy && !state.tcpPortOpen,
                            onClick = { tcpWarning = true }) {
                            Text("Enable TCP")
                        }
                        OutlinedButton(enabled = state.connected && !state.busy,
                            onClick = {
                                scope.launch {
                                    ExperimentalRecording.disableTcp().onFailure {
                                        localError = it.message
                                    }
                                }
                            }) { Text("Disable TCP") }
                    }
                    Text(
                        if (state.tcpPortOpen) "TCP port 5555 is responding; keep debugging disabled when not needed."
                        else "TCP mode is not verified.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "Audio status: not tested. Shell access is not proof of recording both voices.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selection == ExperimentalRecording.Method.SHIZUKU) {
                HorizontalDivider()
                Text("Shizuku service: " + shizukuLabel,
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        shizukuLabel = ExperimentalRecording.shizukuStatus()
                    }) { Text("Check service") }
                    Button(onClick = {
                        runCatching { ExperimentalRecording.requestShizuku() }
                            .onFailure { localError = it.message }
                    }) { Text("Authorize") }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        ExperimentalRecording.testShizuku()
                            .onSuccess { localError = "Shizuku shell verified (UID " + it + ")." }
                            .onFailure { localError = it.message }
                        shizukuLabel = ExperimentalRecording.shizukuStatus()
                    }
                }) { Text("Test Shizuku shell") }
                Text("Shizuku must be started separately after reboot. It is not needed for Built-in ADB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            localError?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (tcpWarning) {
        AlertDialog(
            onDismissRequest = { tcpWarning = false },
            title = { Text("Enable TCP debugging?") },
            text = { Text(
                "ADB may listen on network interfaces, not just this phone. " +
                    "Anyone with trusted debugging credentials on your network could connect. " +
                    "Use only on a trusted network, and disable it when finished. Continue?"
            ) },
            confirmButton = {
                Button(onClick = {
                    tcpWarning = false
                    scope.launch {
                        ExperimentalRecording.enableTcp(context)
                            .onFailure { localError = it.message }
                    }
                }) { Text("Enable TCP/5555") }
            },
            dismissButton = {
                TextButton(onClick = { tcpWarning = false }) { Text("Cancel") }
            },
        )
    }
}
