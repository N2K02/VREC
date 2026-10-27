package com.vrec.app.ui

import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.AdbOnboardingPolicy
import com.vrec.app.experimental.adb.AdbBadgePolicy
import com.vrec.app.experimental.adb.AdbPermissionBootstrap
import com.vrec.app.experimental.adb.AdbSelfRecovery
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.LocalAdbMiddleware
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.Prefs
import kotlinx.coroutines.launch

/**
 * Home owns the only setup/retry action. First-time directions are disclosure
 * content, not a second setup wizard or a collection of competing buttons.
 */
@Composable
fun LocalAdbHomeCard(recordingActive: Boolean = false, standardRecording: Boolean = false) {
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
    var lastSetupTapAt by remember { mutableStateOf(0L) }
    var setupTapInFlight by remember { mutableStateOf(false) }
    var recordingOn by remember { mutableStateOf(Prefs.recordingEnabled(context)) }
    var devOptionsOn by remember { mutableStateOf(true) }
    var onWifi by remember { mutableStateOf(true) }
    LaunchedEffect(tick) {
        devOptionsOn = AdbSelfRecovery.developerOptionsEnabled(context)
        onWifi = AdbSelfRecovery.onWifi(context)
        recordingOn = Prefs.recordingEnabled(context)
    }
    // Home renders shared state; background recovery owns connection monitoring.
    // A setup attempt is explicit, via the user action below. Navigation between
    // screens must never initiate ADB operations or wireless discovery.
    LaunchedEffect(needsUsbBootstrap, wireless.connectPort, wireless.pairingPort) {
        instructionsExpanded = needsUsbBootstrap &&
            wireless.connectPort == null && wireless.pairingPort == null
    }

    val healthy = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val verified = adbState.connected && adbState.shellUid == 2000
    val onboarding = AdbOnboardingPolicy.next(AdbOnboardingPolicy.Signals(
        helperHealthy = healthy,
        tcpListening = adbState.tcpPortOpen,
        shellUid = if (verified) adbState.shellUid else null,
        paired = ExperimentalRecording.wirelessWasPaired(context),
        pairingPortFound = wireless.pairingPort != null,
        wirelessPortFound = wireless.connectPort != null,
        tcpApproved = com.vrec.app.experimental.adb.AdbUserChoices.tcpMode(context),
        awaitingManualRetry = ExperimentalRecording.awaitingManualRetry,
    ))
    val badge = AdbBadgePolicy.resolve(
        AdbBadgePolicy.Signals(
            busy = busy || adbState.busy,
            verifiedUid2000 = verified,
            // Typed failure, not message text. A missing endpoint is a setup step, not a failure.
            failed = !needsUsbBootstrap && (lastSetupFailed ||
                (adbState.lastFailure != null &&
                    adbState.lastFailure != com.vrec.app.experimental.adb.AdbFailure.NETWORK_UNAVAILABLE)),
        )
    )

    fun runSetup() {
        val now = SystemClock.elapsedRealtime()
        if (setupTapInFlight || busy || adbState.busy || now - lastSetupTapAt < 1_500L) return
        lastSetupTapAt = now
        setupTapInFlight = true
        scope.launch {
            try {
                if (wireless.connectPort != null &&
                    ExperimentalRecording.wirelessWasPaired(context)) {
                    LocalWirelessAdb.retry(context)
                } else {
                    val restored = LocalAdbMiddleware.setup(context, userInitiated = true)
                    if (!restored && !ExperimentalRecording.tcpPortListening())
                        LocalWirelessAdb.start(context, userInitiated = true)
                }
            } finally {
                setupTapInFlight = false
            }
        }
    }

    AdbDashboard(
        healthy = healthy,
        verified = verified,
        working = busy || adbState.busy || helperState == AdbPrivilegedApiHelper.State.STARTING ||
            wireless.phase in setOf(LocalWirelessAdb.Phase.PAIRING, LocalWirelessAdb.Phase.CONNECTING,
                LocalWirelessAdb.Phase.SWITCHING, LocalWirelessAdb.Phase.VERIFYING_TCP),
        failed = helperState == AdbPrivilegedApiHelper.State.ERROR,
        canReconnect = verified || adbState.tcpPortOpen,
        recordingOn = recordingOn,
        restore = when {
            AdbSelfRecovery.canSelfRestore(context) -> "Automatic"
            ExperimentalRecording.wirelessWasPaired(context) -> "Wi-Fi"
            else -> "Computer"
        },
        onStart = { runSetup() },
        recordingActive = recordingActive,
    )

    if (healthy) {
        if (!accessibilityOn) {
            Text(
                "Optional: Vrec call recording helper needs Android Accessibility approval for accessibility-based detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

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
            if (!devOptionsOn) {
                Text(
                    "Developer options are off. In About phone, tap Build number 7 times, then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { AdbSelfRecovery.openDeviceInfo(context) },
                    modifier = Modifier.fillMaxWidth()) { Text("Open About phone") }
            } else if (wireless.supported && !onWifi && !verified) {
                Text(
                    "Connect to Wi-Fi to set up with Wireless debugging — no computer needed. " +
                        "Without Wi-Fi, use the USB host instructions below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    onboarding == AdbOnboardingPolicy.Step.READY -> "Recording helper ready — nothing to set up"
                    onboarding == AdbOnboardingPolicy.Step.SWITCH_TO_TCP -> "UID 2000 verified — switching to approved TCP/5555"
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
                    onClick = { runSetup() },
                    enabled = !setupTapInFlight && !busy && !adbState.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(when {
                        busy || adbState.busy -> "Waiting for authorization…"
                        wireless.pairingPort != null -> "Waiting for pairing code"
                        wireless.connectPort != null &&
                            ExperimentalRecording.wirelessWasPaired(context) -> "Restore wireless ADB"
                        needsUsbBootstrap && ExperimentalRecording.wirelessWasPaired(context) -> "Restore ADB (paired)"
                        needsUsbBootstrap -> "Redo ADB setup"
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
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("VRec USB ADB bootstrap",
                        "adb devices\nadb tcpip 5555"))
                }, modifier = Modifier.fillMaxWidth()) { Text("Copy USB ADB commands") }
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
                    "First complete the external USB bootstrap steps above; VRec continues automatically. " +
                        "Tick \"Always allow\" on Android's authorization prompts so reconnecting doesn't ask again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Phones without Wireless debugging need the computer again after every restart. " +
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
