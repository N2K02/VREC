package com.vrec.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Only USB-debugging bootstrap / fixed localhost ADB. No pairing or transport
 * selector. The server must already be started using trusted USB ADB access.
 */
@Composable
fun ExperimentalRecorderCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by ExperimentalRecording.status.collectAsState()
    var usbSetupDialog by remember { mutableStateOf(false) }
    var disableTcpConfirmation by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val resumeTick = rememberOnResumeTick()

    LaunchedEffect(resumeTick) {
        ExperimentalRecording.refreshConnectionStatus()
        AdbHelperAutoRecovery.check(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(8_000L)
            ExperimentalRecording.refreshConnectionStatus()
        }
    }

    fun openDeveloperOptions() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            localError = "Developer options unavailable. Standard recording remains available."
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ADB connection", style = MaterialTheme.typography.titleMedium)
            Icon(if (state.connected && state.shellUid == 2000) Icons.Rounded.CheckCircle
                else Icons.Rounded.Info, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Text(when {
                state.connected && state.shellUid == 2000 -> "Verified Android shell UID 2000 via localhost TCP/5555"
                state.busy -> "Checking USB-bootstrapped ADB…"
                state.tcpPortOpen -> "Local TCP/5555 detected · authorization not yet verified"
                else -> "Not connected · USB setup required"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "VRec runs an embedded ADB client. USB debugging itself does not provide a local ADB shell. " +
                    "An authorized USB ADB host must enable TCP/5555 once before this phone can connect to itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.connected) {
                OutlinedButton(onClick = { scope.launch { ExperimentalRecording.disconnect(context) } },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect ADB")
                }
            } else {
                Button(onClick = {
                    scope.launch {
                        ExperimentalRecording.connect(context).onFailure { localError = it.message }
                    }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "Connecting…" else "Connect USB-bootstrapped ADB")
                }
            }
            OutlinedButton(onClick = { usbSetupDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("One-time USB setup")
            }
            OutlinedButton(onClick = ::openDeveloperOptions, modifier = Modifier.fillMaxWidth()) {
                Text("Open Developer options")
            }
            if (state.tcpPortOpen && state.connected) {
                OutlinedButton(onClick = { disableTcpConfirmation = true },
                    modifier = Modifier.fillMaxWidth()) { Text("Disable TCP/5555") }
            }
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (ExperimentalRecording.recoveryAuthorization(context).allowed)
                    "Automatic recovery uses only previously verified TCP/5555 while VRec is running."
                else "Connect once to enable helper recovery. Disconnect disables automatic retries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (usbSetupDialog) {
        AlertDialog(onDismissRequest = { usbSetupDialog = false },
            title = { Text("One-time USB ADB setup") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1. In Developer options enable USB debugging. This is required once before connection.")
                    Text("2. Connect to a trusted USB ADB host and approve Android's RSA authorization dialog.")
                    Text("3. On that host run adb tcpip 5555.")
                    Text("4. Return to VRec and tap Connect USB-bootstrapped ADB. Approve this ADB key if Android asks.")
                    Text("5. VRec verifies Android shell UID 2000 before starting the enhanced helper.")
                    Text("Without an external USB ADB host or pre-existing authorized endpoint, setup cannot be completed on a stock USB-only Huawei.")
                    Text("TCP/5555 may be reachable from other devices on your network. Disable it after use.")
                }
            },
            confirmButton = { TextButton(onClick = {
                usbSetupDialog = false
                openDeveloperOptions()
            }) { Text("Developer options") } },
            dismissButton = { TextButton(onClick = { usbSetupDialog = false }) { Text("Close") } })
    }
    if (disableTcpConfirmation) {
        AlertDialog(onDismissRequest = { disableTcpConfirmation = false },
            title = { Text("Disable local TCP debugging?") },
            text = { Text("ADB will return to USB mode and VRec will lose the local connection. Continue?") },
            confirmButton = { Button(onClick = {
                disableTcpConfirmation = false
                scope.launch { ExperimentalRecording.disableTcp(context).onFailure { localError = it.message } }
            }) { Text("Disable TCP/5555") } },
            dismissButton = { TextButton(onClick = { disableTcpConfirmation = false }) { Text("Cancel") } })
    }
}
