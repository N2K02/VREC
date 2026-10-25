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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.vrec.app.experimental.adb.AdbBadgePolicy
import com.vrec.app.experimental.adb.AdbPermissionBootstrap
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
    val lastSetupFailed by LocalAdbMiddleware.lastSetupFailed.collectAsState()
    val permissions by AdbPermissionBootstrap.status.collectAsState()
    val tick = rememberOnResumeTick()
    val usbDebugging = androidx.compose.runtime.remember(tick, adbState) {
        ExperimentalRecording.usbDebuggingFlag(context)
    }
    val healthy = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val verified = adbState.connected && adbState.shellUid == 2000
    val badge = AdbBadgePolicy.resolve(
        AdbBadgePolicy.Signals(
            busy = busy || adbState.busy,
            verifiedUid2000 = verified,
            failed = lastSetupFailed ||
                adbState.message.contains("failed", ignoreCase = true) ||
                adbState.message.contains("denied", ignoreCase = true) ||
                setupMessage.contains("required once", ignoreCase = true) ||
                setupMessage.contains("could not connect", ignoreCase = true),
        )
    )

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
                Column(Modifier.weight(1f)) {
                    Text("ADB control center", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("USB debugging · local shell helper",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AdbUidBadge(badge)
            }
            Text(when {
                healthy -> "Enhanced recording helper ready"
                verified -> "Authorized Android shell UID 2000"
                else -> "Enhanced helper not connected"
            }, style = MaterialTheme.typography.bodyMedium)
            Text(setupMessage, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (permissions.finished) {
                Text(
                    if (permissions.unavailable.isEmpty())
                        "Supported permissions confirmed (${permissions.granted.size})."
                    else "Permissions confirmed: ${permissions.granted.size}. " +
                        "Android still requires attention for: ${permissions.unavailable.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { scope.launch { LocalAdbMiddleware.setup(context) } },
                enabled = !busy && !adbState.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy || adbState.busy) "Connecting…" else "Connect / restore ADB")
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
            Text(
                "First time: enable USB debugging, authorize a trusted USB ADB host, " +
                    "run adb tcpip 5555 on that host, then return and connect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = ::openDeveloperOptions) { Text("Developer options") }
        }
    }
}

/** A single meaningful connection indicator with explicit shell UID verification. */
@Composable
private fun AdbUidBadge(state: AdbBadgePolicy.State) {
    val background = when (state) {
        AdbBadgePolicy.State.SUCCESS -> Color(0xFF2E7D32)
        AdbBadgePolicy.State.FAILED -> Color(0xFFC62828)
        AdbBadgePolicy.State.IN_PROGRESS -> Color(0xFFF9A825)
        AdbBadgePolicy.State.DISCONNECTED -> Color(0xFF757575)
    }
    val label = when (state) {
        AdbBadgePolicy.State.SUCCESS -> "UID 2000 ✓"
        AdbBadgePolicy.State.FAILED -> "Failed"
        AdbBadgePolicy.State.IN_PROGRESS -> "Connecting…"
        AdbBadgePolicy.State.DISCONNECTED -> "Connect"
    }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(50),
        color = background) {
        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (state == AdbBadgePolicy.State.IN_PROGRESS) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}
