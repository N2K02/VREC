package com.vrec.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
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
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    var adbMode by rememberSaveable { mutableStateOf("wireless") }
    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf("") }
    var tcpWarning by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Health-check on entry even if ADB or Wi-Fi is disconnected.
        // Reconnect to an existing shell service; never spawn a privileged
        // process silently or attempt an unauthorized ADB connection.
        AdbPrivilegedApiHelper.reconnect(context)
    }

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
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!com.vrec.app.Prefs.recordingEnabled(context)) {
                Text("Recording is off. Turn it on from Home to capture calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("ADB connection", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (state.connected) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            if (state.connected) "Connected · shell UID ${state.shellUid ?: "unverified"}"
                            else "Disconnected",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (state.connected) "ADB verified. The helper operates independently."
                            else "Choose one connection method below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!state.connected) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("wireless" to "Wireless", "tcp" to "TCP/5555")
                            .forEachIndexed { index, (value, label) ->
                                SegmentedButton(
                                    selected = adbMode == value,
                                    onClick = { adbMode = value; localError = null },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                    label = { Text(label) },
                                    enabled = !state.busy,
                                )
                            }
                    }
                    if (adbMode == "wireless") {
                        Text(
                            "Use Android Wireless debugging. Pair once; later connections only need Connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (networkPermission != null && !networkAllowed) {
                            Text(
                                "Allow local network access for Wireless debugging discovery.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                networkPermissionLauncher.launch(networkPermission)
                            }) { Text("Allow local ADB discovery") }
                        }
                        TextButton(onClick = { showPairing = !showPairing }) {
                            Text(if (showPairing) "Hide pairing steps" else "Pair device (first time)")
                        }
                        if (showPairing) {
                            Text(
                                "In Developer options, open Wireless debugging → Pair device with pairing code. " +
                                    "Use split screen to enter the displayed code here.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = ::openDeveloperOptions) {
                                Text("Open Developer options")
                            }
                            OutlinedButton(
                                enabled = !state.busy && networkAllowed && Build.VERSION.SDK_INT >= 30,
                                onClick = {
                                    scope.launch {
                                        ExperimentalRecording.discover(context)
                                            .onSuccess { pairingPort = it.toString() }
                                            .onFailure { localError = it.message }
                                    }
                                },
                            ) { Text("Find pairing port") }
                            OutlinedTextField(
                                value = pairingPort,
                                onValueChange = { pairingPort = it.filter(Char::isDigit).take(5) },
                                label = { Text("Pairing port") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = pairingCode,
                                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                                label = { Text("Six-digit pairing code") },
                                singleLine = true,
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
                            OutlinedTextField(
                                value = connectionPort,
                                onValueChange = { connectionPort = it.filter(Char::isDigit).take(5) },
                                label = { Text("Wireless ADB connection port (optional)") },
                                supportingText = { Text("Leave blank for automatic discovery.") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    } else {
                        Text(
                            "Connect to port 5555 after enabling TCP debugging via USB ADB " +
                                "or an existing authorized wireless session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.connected) {
                    OutlinedButton(
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                localError = null
                                ExperimentalRecording.disconnect()
                            }
                        },
                    ) { Text("Disconnect ADB") }
                    Text(
                        "Disconnect only closes the ADB connection. The enhanced helper may " +
                            "continue running; turn off Recording on Home to stop call capture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Button(
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                localError = null
                                val port = if (adbMode == "tcp") 5555
                                    else connectionPort.toIntOrNull()
                                ExperimentalRecording.connect(context, port,
                                    wirelessOnly = adbMode == "wireless")
                                    .onFailure { localError = it.message }
                            }
                        },
                    ) { Text(if (state.busy) "Connecting…" else "Connect") }
                }
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide TCP/5555 settings" else "Advanced TCP/5555 settings")
                }
                if (showAdvanced) {
                    HorizontalDivider()
                    Text("TCP/5555", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "To enable TCP without a computer, connect using Wireless debugging first, " +
                            "then enable TCP here. Alternatively run adb tcpip 5555 over USB. " +
                            "Switching modes restarts adbd and may disconnect Wireless debugging.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "TCP debugging may be reachable from other devices on your network. " +
                            "Disable it before leaving a trusted network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = state.connected && !state.busy && !state.tcpPortOpen,
                            modifier = Modifier.weight(1f),
                            onClick = { tcpWarning = true },
                        ) { Text("Enable TCP") }
                        OutlinedButton(
                            enabled = state.connected && !state.busy && state.tcpPortOpen,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    ExperimentalRecording.disableTcp().onFailure {
                                        localError = it.message
                                    }
                                }
                            },
                        ) { Text("Disable TCP") }
                    }
                    Text(
                        if (state.tcpPortOpen) "TCP/5555 is listening; disconnecting does not turn it off."
                        else "TCP/5555 is not detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        if (recorderReady) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text("Enhanced recording helper", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                helperState == AdbPrivilegedApiHelper.State.STARTING -> "Starting…"
                                recorderReady && helperState == AdbPrivilegedApiHelper.State.RUNNING -> "Ready"
                                helperState == AdbPrivilegedApiHelper.State.ERROR -> "Needs attention"
                                helperState == AdbPrivilegedApiHelper.State.NEEDS_ADB -> "Needs ADB to start"
                                else -> "Not running"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (helperState == AdbPrivilegedApiHelper.State.ERROR ||
                    helperState == AdbPrivilegedApiHelper.State.STARTING) {
                    Text(
                        recorderMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "The helper starts automatically after ADB verification. " +
                        "Use Home to control recording. Two-sided audio remains unverified " +
                        "until a real consented call is played back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                "Android will restart ADB. The current Wireless debugging connection will disconnect; " +
                    "VRec will try to reconnect at TCP/5555. TCP debugging can also be accessed from " +
                    "your network by trusted ADB clients. Use only on a trusted network. Continue?"
            ) },
            confirmButton = {
                Button(onClick = {
                    tcpWarning = false
                    scope.launch {
                        localError = null
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
