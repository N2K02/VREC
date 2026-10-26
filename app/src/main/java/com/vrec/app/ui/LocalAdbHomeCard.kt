package com.vrec.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.AdbBadgePolicy
import com.vrec.app.experimental.adb.AdbPermissionBootstrap
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.LocalAdbMiddleware
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.launch

/**
 * Home owns the only setup/retry action. First-time directions are disclosure
 * content, not a second setup wizard or a collection of competing buttons.
 */
@Composable
fun LocalAdbHomeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adbState by ExperimentalRecording.status.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val helperReady by ExperimentalCaptureDaemon.ready.collectAsState()
    val busy by LocalAdbMiddleware.busy.collectAsState()
    val setupMessage by LocalAdbMiddleware.message.collectAsState()
    val lastSetupFailed by LocalAdbMiddleware.lastSetupFailed.collectAsState()
    val needsUsbBootstrap by LocalAdbMiddleware.needsUsbBootstrap.collectAsState()
    val wireless by LocalWirelessAdb.status.collectAsState()
    val permissions by AdbPermissionBootstrap.status.collectAsState()
    val tick = rememberOnResumeTick()
    val accessibilityOn = remember(tick) { VrecAccessibilityService.isEnabled(context) }
    var instructionsExpanded by remember { mutableStateOf(false) }
    // Automatic first-open detection. No manual transport picker or repeated
    // background retries after a user-visible authorization failure.
    LaunchedEffect(Unit) {
        LocalWirelessAdb.start(context)
        LocalAdbMiddleware.setup(context, userInitiated = false)
    }
    LaunchedEffect(needsUsbBootstrap, wireless.connectPort, wireless.pairingPort) {
        instructionsExpanded = needsUsbBootstrap &&
            wireless.connectPort == null && wireless.pairingPort == null
    }

    val healthy = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val verified = adbState.connected && adbState.shellUid == 2000
    val badge = AdbBadgePolicy.resolve(
        AdbBadgePolicy.Signals(
            busy = busy || adbState.busy,
            verifiedUid2000 = verified,
            failed = !needsUsbBootstrap && (lastSetupFailed ||
                adbState.message.contains("failed", ignoreCase = true) ||
                adbState.message.contains("denied", ignoreCase = true) ||
                setupMessage.contains("could not connect", ignoreCase = true)),
        )
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text("ADB recording setup", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Auto-detect · USB or Wireless debugging",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AdbUidBadge(badge, needsUsbBootstrap)
            }
            Text(
                when {
                    healthy -> "Recording helper ready — nothing to set up"
                    verified -> "Android shell UID 2000 verified — preparing helper"
                    busy || adbState.busy -> "Checking ADB authorization…"
                    wireless.pairingPort != null -> "Wireless pairing found — enter Android's six-digit code"
                    wireless.connectPort != null && !wireless.paired ->
                        "Wireless debugging available — pair to authorize VRec"
                    needsUsbBootstrap -> "Waiting for USB host or Wireless debugging"
                    lastSetupFailed || ExperimentalRecording.awaitingManualRetry ->
                        "Authorization stopped. Tap Retry to try again."
                    else -> "Authorize ADB once to configure recording"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (healthy && !verified)
                    "The independent recording helper is running. A live ADB session is not required until recovery."
                else if (wireless.pairingPort != null || wireless.connectPort != null)
                    wireless.message
                else setupMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!healthy) {
                Button(
                    onClick = {
                        if (wireless.connectPort != null &&
                            ExperimentalRecording.wirelessWasPaired(context))
                            LocalWirelessAdb.retry(context)
                        else scope.launch {
                            LocalAdbMiddleware.setup(context, userInitiated = true)
                        }
                    },
                    enabled = !busy && !adbState.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        busy || adbState.busy -> "Waiting for authorization…"
                        wireless.pairingPort != null -> "Waiting for pairing code"
                        wireless.connectPort != null &&
                            ExperimentalRecording.wirelessWasPaired(context) -> "Restore wireless ADB"
                        needsUsbBootstrap -> "Check USB ADB connection"
                        lastSetupFailed || ExperimentalRecording.awaitingManualRetry -> "Retry ADB setup"
                        verified -> "Start recording helper"
                        else -> "Set up ADB recording"
                    })
                }
                Text(
                    if (needsUsbBootstrap && wireless.connectPort == null)
                        "No USB ADB endpoint detected. Use another USB host or enable Wireless debugging below."
                    else "Authorization times out after 15 seconds. No automatic retry after a failed attempt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (permissions.finished) {
                Text(
                    if (permissions.unavailable.isEmpty())
                        "Supported Android permissions configured automatically."
                    else "Configured ${permissions.granted.size} permissions. Android requires manual attention: " +
                        permissions.unavailable.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!accessibilityOn && (verified || healthy)) {
                Text(
                    "Optional: Vrec call recording helper needs Android Accessibility approval for accessibility-based detection. " +
                        "ADB does not enable this protected setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WirelessAdbSetupCard()
            TermuxTerminalCard()
            Row(
                modifier = Modifier.fillMaxWidth().clickable { instructionsExpanded = !instructionsExpanded }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("USB host instructions (when Wireless debugging is unavailable)", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                Text(if (instructionsExpanded) "▲" else "▼",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (instructionsExpanded) {
                Text("USB debugging", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Open Android Developer options and confirm USB debugging is enabled. " +
                        "VRec cannot reliably read this toggle because Android reports it disabled to third-party apps.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "1. Enable USB debugging in Android Developer options.\n" +
                        "2. Connect this phone to a trusted external USB ADB host and approve its debugging key.\n" +
                        "3. On that host run: adb devices, then adb tcpip 5555.\n" +
                        "4. Back in VRec, tap Set up ADB recording and approve VRec's ADB key if prompted.\n" +
                        "5. VRec verifies UID 2000, grants supported permissions and starts the recording helper in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Android cannot authorize its own first USB ADB connection. " +
                        "A reboot or debugging reset can disable TCP/5555 and require the external host again. " +
                        "TCP/5555 may also be accessible from your network while enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Open Developer options",
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }
                    }.padding(vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Badge represents a verified current shell session, not merely an open TCP socket. */
@Composable
private fun AdbUidBadge(state: AdbBadgePolicy.State, needsUsbBootstrap: Boolean) {
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
        AdbBadgePolicy.State.DISCONNECTED -> if (needsUsbBootstrap) "USB setup" else "Connect"
    }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = background) {
        Text(
            label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = if (state == AdbBadgePolicy.State.IN_PROGRESS) Color.Black else Color.White,
            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        )
    }
}
