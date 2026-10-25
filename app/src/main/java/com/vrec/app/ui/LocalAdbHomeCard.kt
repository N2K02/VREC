package com.vrec.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.LocalAdbMiddleware
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.launch

/** One-tap USB-debugging setup, with honest authorization diagnostics. */
@Composable
fun LocalAdbHomeCard(onOpenDetailedSetup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbState by ExperimentalRecording.status.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val helperReady by ExperimentalCaptureDaemon.ready.collectAsState()
    val busy by LocalAdbMiddleware.busy.collectAsState()
    val setupMessage by LocalAdbMiddleware.message.collectAsState()
    val tick = rememberOnResumeTick()
    val usbDebugging = androidx.compose.runtime.remember(tick, adbState) {
        ExperimentalRecording.usbDebuggingFlag(context)
    }
    val healthy = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val verified = adbState.connected && adbState.shellUid == 2000

    fun openDeveloperOptions() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            onOpenDetailedSetup()
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("ADB quick setup", style = MaterialTheme.typography.titleMedium)
            }
            Text(when {
                healthy -> "Enhanced recording helper ready"
                verified -> "Authorized Android shell UID 2000"
                else -> "Enhanced helper not connected"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(setupMessage, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { scope.launch { LocalAdbMiddleware.setup(context) } },
                enabled = !busy && !adbState.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy || adbState.busy) "Checking ADB…" else "Set up automatically")
            }
            OutlinedButton(onClick = onOpenDetailedSetup, modifier = Modifier.fillMaxWidth()) {
                Text("USB ADB setup instructions")
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB debugging", style = MaterialTheme.typography.titleMedium)
            Text(when {
                usbDebugging == true -> "USB debugging is on"
                usbDebugging == false -> "USB debugging is off"
                else -> "USB debugging status unavailable"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (usbDebugging == true && !adbState.tcpPortOpen)
                    "USB debugging is on, but a local authorized ADB endpoint is missing. An external trusted USB host must enable TCP/5555 once. VRec's local shell cannot authorize itself."
                else "VRec checks localhost TCP/5555 after a trusted USB host has enabled it. USB debugging alone is not shell authorization.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = ::openDeveloperOptions) { Text("Developer options") }
        }
    }
}
