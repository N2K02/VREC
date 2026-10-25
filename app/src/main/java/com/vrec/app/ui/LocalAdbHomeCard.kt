package com.vrec.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
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

/** Everyday setup on Home; advanced port and pairing controls stay under Settings. */
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
    // Read when returning from Developer options as well as on ADB state changes.
    val usbDebugging = androidx.compose.runtime.remember(tick, adbState) {
        ExperimentalRecording.usbDebuggingFlag(context)
    }
    val wirelessDebugging = androidx.compose.runtime.remember(tick, adbState) {
        ExperimentalRecording.wirelessDebuggingFlag(context)
    }
    val healthy = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val verified = adbState.connected && adbState.shellUid == 2000
    val usbOnly = usbDebugging == true && wirelessDebugging != true && !adbState.tcpPortOpen

    fun openDeveloperOptions() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            onOpenDetailedSetup()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("ADB quick setup", style = MaterialTheme.typography.titleMedium)
            }
            Text(when {
                healthy -> "Enhanced helper ready for recording"
                verified -> "ADB authorized · preparing recording helper"
                else -> "Enhanced helper not connected"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(setupMessage, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { scope.launch { LocalAdbMiddleware.setup(context) } },
                enabled = !busy && !adbState.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy || adbState.busy) "Checking ADB…" else "Set up automatically") }
            OutlinedButton(onClick = onOpenDetailedSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Advanced ADB setup")
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB debugging", style = MaterialTheme.typography.titleMedium)
            Text(when {
                usbDebugging == true -> "USB debugging is on"
                usbDebugging == false -> "USB debugging is off"
                else -> "USB debugging status unavailable"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (usbOnly) "USB alone cannot authorize VRec from this same phone. A one-time trusted USB ADB host is needed to enable TCP/5555. A local shell or container cannot replace it."
                else "If TCP/5555 was enabled by an authorized host, VRec can connect automatically. USB debugging alone is not an ADB connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = ::openDeveloperOptions) { Text("Developer options") }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wireless debugging", style = MaterialTheme.typography.titleMedium)
            Text(when {
                Build.VERSION.SDK_INT < 30 -> "Requires Android 11 or newer"
                wirelessDebugging == true -> "Wireless debugging is on"
                else -> "Wireless debugging is off or unavailable"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (wirelessDebugging == true)
                    "Pair once with the six-digit code in Android Developer options. VRec reconnects automatically after authorization."
                else "On Huawei phones without Wireless debugging, use the USB setup instructions. VRec cannot switch this Android feature on by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDetailedSetup) { Text("Pair or connect") }
        }
    }
}
