package com.vrec.app.ui

import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.material3.OutlinedButton
import com.vrec.app.calls.VoipNotifListener
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.EnhancedCapturePreferences
import com.vrec.app.experimental.daemon.SimCaptureSource
import android.os.Build
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import kotlinx.coroutines.launch
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.LiveUpdater
import dev.n2k02.updater.hasPendingUpdate
import com.vrec.app.Prefs

@Composable
fun SettingsScreen(
    section: String?,
    onSectionChange: (String?) -> Unit,
    onOpenLog: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    val scope = rememberCoroutineScope()
    val resumeTick = rememberOnResumeTick()
    val voipNotificationGranted = remember(resumeTick) { VoipNotifListener.isEnabled(context) }
    val captureState by com.vrec.app.recording.RecordingService.state.collectAsState()
    val shellRecording by com.vrec.app.recording.AdbFallbackNotice.enhancedActive.collectAsState()
    val methodLocked = !com.vrec.app.recording.RecordingMethodPolicy.canChange(
        captureState is com.vrec.app.recording.RecordingService.State.Idle, shellRecording)
    val connection by ExperimentalRecording.status.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val helperReady by ExperimentalCaptureDaemon.ready.collectAsState()
    LaunchedEffect(resumeTick, section) {
        AdbPrivilegedApiHelper.checkHealth(context)
    }

    // Home and Settings share the same persisted recording method.
    // Local mirror of the stored values, so the UI updates immediately.
    var autoRecord by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_RECORD, true)) }
    var recordVoip by remember { mutableStateOf(prefs.getBoolean(Prefs.RECORD_VOIP, false)) }
    var simCaptureSource by remember { mutableStateOf(EnhancedCapturePreferences.simSource(context)) }
    val voipArmed by ExperimentalCaptureDaemon.voipArmed.collectAsState()
    val voipMessage by ExperimentalCaptureDaemon.voipMessage.collectAsState()
    val simSourceMessage by ExperimentalCaptureDaemon.simSourceMessage.collectAsState()
    var discardSilent by remember { mutableStateOf(prefs.getBoolean(Prefs.DISCARD_SILENT, false)) }
    var showOverlay by remember { mutableStateOf(Prefs.showOverlay(context)) }
    val overlayPermissionGranted = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context)
    }

    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (section == null) {
            Text(
                "Manage ADB recording",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
            )
            listOfNotNull(
                Triple("Recording", "Automatic recording and silent files", Icons.Rounded.Mic),
                Triple("Floating controls", "Small recording overlay", Icons.Rounded.Notifications),
                Triple("VoIP", "Internet call recording", Icons.Rounded.Call),
                Triple("Audio source", "Enhanced SIM capture source and diagnostics", Icons.Rounded.Tune),
            ).forEach { (name, detail, icon) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSectionChange(name) },
                )
            }
            // Supported runtime permissions are configured after UID 2000 verification.
            HorizontalDivider()
            // Diagnostics, logs and updates remain together at the bottom like mRadio.
            ListItem(
                headlineContent = { Text("ADB diagnostics") },
                supportingContent = { Text("Helper status and access exceptions") },
                leadingContent = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onSectionChange("Experimental") },
            )
            ListItem(
                headlineContent = { Text("Activity log") },
                supportingContent = { Text("Recording events and diagnostics") },
                leadingContent = { Icon(Icons.Rounded.Article, contentDescription = null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLog),
            )
            ListItem(
                headlineContent = { Text("About & updates") },
                supportingContent = {
                    Text(if (updateStatus.hasPendingUpdate()) "A new update is available" else "Version and update checks")
                },
                leadingContent = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                trailingContent = { dev.n2k02.updater.N2K02UpdateBadge(updateStatus) },
                modifier = Modifier.clickable(onClick = onOpenUpdates),
            )
        } else {
        if (section == "Recording") SettingCard("Recording") {
            ToggleRow("Record calls automatically", "Start recording as soon as a call connects.", autoRecord) {
                autoRecord = it; putBool(Prefs.AUTO_RECORD, it)
            }
            ToggleRow(
                "Discard silent recordings",
                "If a recording comes out silent (this phone blocked capture), delete it instead of keeping it.",
                discardSilent,
            ) { discardSilent = it; putBool(Prefs.DISCARD_SILENT, it) }
        }

         if (section == "Floating controls") SettingCard("Floating controls") {
            ToggleRow("Show compact overlay", "Enabled by default. Drag the three-dot handle on the right to move the controls. Stop saves audio; Close only hides the overlay.", showOverlay) {
                showOverlay = it; putBool(Prefs.SHOW_OVERLAY, it)
            }
            if (showOverlay && !overlayPermissionGranted) {
                Text("Allow Display over other apps so VRec can show floating controls during calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")))
                    }
                }) { Text("Allow floating controls") }
            }
        }

        if (section == "Experimental") {
                SettingCard("ADB connection") {
                    Text(
                        when {
                            helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING ->
                                "Recording helper is running."
                            connection.connected && connection.shellUid == 2000 ->
                                "Shell UID 2000 verified; helper is not ready yet."
                            else -> "Open Home to set up or retry ADB authorization."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("USB authorization and retry are managed on Home. " +
                        "This screen contains capture configuration and diagnostics only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AdbSecurityChoices()
                // UID 2000 bootstrap handles eligible grants automatically.
                val bootstrap by com.vrec.app.experimental.adb.AdbPermissionBootstrap.status.collectAsState()
                if (bootstrap.finished && bootstrap.unavailable.isNotEmpty()) {
                    SettingCard("Access still requiring attention") {
                        Text(bootstrap.unavailable.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }

        if (section == "Audio source") {
                SettingCard("Enhanced SIM capture source") {
                    Text(
                        "Select the shell's call-audio source. This does not use VRec's " +
                            "standard microphone source setting. Availability depends on Android and your phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SimCaptureSource.entries.forEach { option ->
                        SourceOption(option.description, option.name, simCaptureSource.name) { chosen ->
                            simCaptureSource = SimCaptureSource.from(chosen)
                            EnhancedCapturePreferences.setSimSource(context, simCaptureSource)
                            scope.launch { ExperimentalCaptureDaemon.setMode(context, Prefs.recordingEnabled(context)) }
                        }
                    }
                    Text(
                        "Dual captures uplink and downlink as separate stereo channels. " +
                            "A selected source may be unavailable or silent on some phones; " +
                            "changes during a call apply after the call ends. " + simSourceMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        if (section == "VoIP") SettingCard("VoIP") {
            ToggleRow(
                "Record VoIP calls",
                "Use the independent ADB helper for supported apps on Android 14+ when permitted.",
                recordVoip,
            ) {
                recordVoip = it
                putBool(Prefs.RECORD_VOIP, it)
                scope.launch { ExperimentalCaptureDaemon.setMode(context, Prefs.recordingEnabled(context)) }
            }
            if (true) {
                Text(
                    if (Build.VERSION.SDK_INT < 34)
                        "Enhanced VoIP playback capture requires Android 14 or newer."
                    else if (voipArmed) "Enhanced ADB VoIP ready · WhatsApp, Telegram, Signal, Viber and other supported apps."
                    else voipMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("The shell manages its own audio path; VRec does not silently fall back to microphone recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recordVoip) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notification access", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (voipNotificationGranted) "Granted · App call detection available"
                            else "Required to detect supported app calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!voipNotificationGranted) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Allow") }
                    }
                }
            }
        }

        if (section == "Recording") Text(
            "Recordings are saved privately inside VREC's own storage and never leave your phone unless you share them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SourceOption(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected == value) { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}



/**
 * ADB network transport is automatic after UID 2000 verification.
 * Users may disable TCP mode; reboot-restoration remains opt-in.
 */
@Composable
private fun AdbSecurityChoices() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by ExperimentalRecording.status.collectAsState()
    var restore by remember { mutableStateOf(com.vrec.app.experimental.adb.AdbUserChoices.restoreAfterRestart(context)) }
    var tcp by remember { mutableStateOf(com.vrec.app.experimental.adb.AdbUserChoices.tcpMode(context)) }
    var note by remember { mutableStateOf<String?>(null) }
    val verified = connection.connected && connection.shellUid == 2000

    SettingCard("ADB restore and network access") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Restore after restart", style = MaterialTheme.typography.bodyLarge)
                Text("After a reboot, VRec may turn Wireless debugging back on (on Wi-Fi) to restart " +
                    "the recording helper with your existing pairing. Never if you turn Wireless " +
                    "debugging off yourself. Needs one extra Android permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = restore, onCheckedChange = { on ->
                restore = on
                com.vrec.app.experimental.adb.AdbUserChoices.setRestoreAfterRestart(context, on)
                scope.launch {
                    note = when {
                        on && verified ->
                            if (com.vrec.app.experimental.adb.AdbSelfRecovery.grantSecureSettings(context))
                                "Restore after restart is on."
                            else "Android refused the permission; restore can't run on this phone."
                        on -> "Restore turns on the next time ADB is verified."
                        verified -> {
                            com.vrec.app.experimental.adb.AdbSelfRecovery.revokeSecureSettings(context)
                            "Restore after restart is off."
                        }
                        else -> "Restore after restart is off. VRec won't change Wireless debugging."
                    }
                }
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep ADB without Wi-Fi (TCP/5555)", style = MaterialTheme.typography.bodyLarge)
                Text("Automatic after verified UID 2000 setup, repair or reconnection. " +
                    "TCP/5555 may be reachable from other devices on your network. " +
                    "Turn this off to use encrypted Wireless debugging instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = tcp, onCheckedChange = { on ->
                tcp = on
                com.vrec.app.experimental.adb.AdbUserChoices.setTcpMode(context, on)
                if (on) {
                    note = "Automatic TCP/5555 enabled. It applies on the next verified wireless connection."
                } else if (connection.tcpPortOpen && verified) {
                    scope.launch {
                        note = ExperimentalRecording.disableTcp(context).fold(
                            { "TCP/5555 closed. Automatic TCP switching is off." },
                            { "Automatic switching is off, but TCP/5555 may still be open: " +
                                (it.message ?: "check Developer options.") })
                    }
                } else {
                    note = if (connection.tcpPortOpen)
                        "Automatic switching is off. TCP/5555 is still listening; connect to disable it or turn USB debugging off."
                    else "Automatic TCP/5555 switching is off."
                }
            })
        }
        if (connection.tcpPortOpen && verified) {
            OutlinedButton(onClick = {
                scope.launch {
                    note = ExperimentalRecording.disableTcp(context).fold(
                        { "TCP/5555 is closed." }, { it.message ?: "Couldn't close TCP/5555." })
                }
            }, enabled = !connection.busy, modifier = Modifier.fillMaxWidth()) { Text("Disable TCP/5555") }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

}
