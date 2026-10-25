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
import com.vrec.app.recording.RecordingEngine

@Composable
fun SettingsScreen(
    section: String?,
    onSectionChange: (String?) -> Unit,
    onOpenLog: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
    onOpenRecordings: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
) {
    val updateStatus by LiveUpdater.controller.status.collectAsState()
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }
    val scope = rememberCoroutineScope()
    val resumeTick = rememberOnResumeTick()
    val voipNotificationGranted = remember(resumeTick) { VoipNotifListener.isEnabled(context) }
    var recordingMethod by remember { mutableStateOf(ExperimentalRecording.method(context)) }
    val connection by ExperimentalRecording.status.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val helperReady by ExperimentalCaptureDaemon.ready.collectAsState()
    var permissionCheckTick by remember { mutableIntStateOf(0) }
    // Recheck after an ADB connection finishes its automatic AppOp command.
    val advancedPermissionGranted = remember(
        resumeTick, section, permissionCheckTick, connection.message, connection.busy
    ) {
        CallDetectionAccess.isGranted(context)
    }
    val advancedDetectionEnabled = remember(
        resumeTick, section, permissionCheckTick, connection.message, connection.busy,
        recordingMethod
    ) {
        Prefs.experimentalCallDetection(context)
    }
    LaunchedEffect(resumeTick, section, recordingMethod) {
        if (recordingMethod == ExperimentalRecording.Method.BUILT_IN_ADB) {
            AdbPrivilegedApiHelper.checkHealth(context)
        }
    }

    fun selectRecordingMethod(method: ExperimentalRecording.Method) {
        if (recordingMethod == method) return
        // Never start an unauthorized ADB session just by selecting a radio.
        recordingMethod = method
        ExperimentalRecording.select(context, method)
        val alreadyGranted = method == ExperimentalRecording.Method.BUILT_IN_ADB &&
            CallDetectionAccess.isGranted(context)
        Prefs.of(context).edit().putBoolean(
            Prefs.EXPERIMENTAL_CALL_DETECTION, alreadyGranted
        ).apply()
        permissionCheckTick++
        scope.launch {
            // A verified existing ADB session can finish setup immediately,
            // without another pairing step or a manually entered shell command.
            if (method == ExperimentalRecording.Method.BUILT_IN_ADB &&
                connection.connected && connection.shellUid == 2000) {
                AdbPrivilegedApiHelper.startWithAdb(context)
                ExperimentalRecording.grantAdvancedDetection(context)
                permissionCheckTick++
            }
            AdbPrivilegedApiHelper.reconnect(context)
        }
    }

    // Local mirror of the stored values, so the UI updates immediately.
    var autoRecord by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_RECORD, true)) }
    var autoSpeaker by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_SPEAKER, false)) }
    var inComm by remember { mutableStateOf(prefs.getBoolean(Prefs.IN_COMM_MODE, true)) }
    var recordVoip by remember { mutableStateOf(prefs.getBoolean(Prefs.RECORD_VOIP, false)) }
    var discardSilent by remember { mutableStateOf(prefs.getBoolean(Prefs.DISCARD_SILENT, false)) }
    var showOverlay by remember { mutableStateOf(Prefs.showOverlay(context)) }
    val overlayPermissionGranted = remember(resumeTick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || AndroidSettings.canDrawOverlays(context)
    }
    var sourceMode by remember { mutableStateOf(Prefs.sourceMode(context)) }

    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (section == null) {
            Text(
                "Make recording your own",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 12.dp),
            )
            SettingCard("Recording method") {
                RecordingMethodOption(
                    label = "Regular recording",
                    detail = "Uses the phone microphone and existing call permissions.",
                    selected = recordingMethod == ExperimentalRecording.Method.STANDARD,
                    onSelect = { selectRecordingMethod(ExperimentalRecording.Method.STANDARD) },
                )
                RecordingMethodOption(
                    label = "ADB privileged helper",
                    detail = "Optional enhanced helper. Requires separate Android debugging setup.",
                    selected = recordingMethod == ExperimentalRecording.Method.BUILT_IN_ADB,
                    onSelect = { selectRecordingMethod(ExperimentalRecording.Method.BUILT_IN_ADB) },
                )
            }
            if (recordingMethod == ExperimentalRecording.Method.BUILT_IN_ADB) {
                AdbSetupProgressCard(
                    adbAuthorized = (connection.connected && connection.shellUid == 2000) ||
                        (helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING),
                    helperReady = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING,
                    advancedGranted = advancedPermissionGranted,
                    advancedEnabled = advancedDetectionEnabled,
                    onConfigure = { onSectionChange("Experimental") },
                )
            }
            listOf(
                Triple("Recording", "Automatic recording and silent files", Icons.Rounded.Mic),
                Triple("Microphone source", "Choose the audio input", Icons.Rounded.Tune),
                Triple("Capturing the other side", "Speaker and communication mode", Icons.Rounded.Call),
                Triple("Floating controls", "Small recording overlay", Icons.Rounded.Notifications),
                Triple("VoIP", "Internet call recording", Icons.Rounded.Call),
                Triple("Experimental", "ADB connection, helper and advanced access", Icons.Rounded.Tune),
            ).forEach { (name, detail, icon) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSectionChange(name) },
                )
            }
            ListItem(
                headlineContent = { Text("Recordings") },
                supportingContent = { Text("Browse, play and share recorded calls") },
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenRecordings),
            )
            ListItem(
                headlineContent = { Text("Permissions & setup") },
                supportingContent = { Text("Recorder permissions and accessibility requirements") },
                leadingContent = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenSetup),
            )
            HorizontalDivider()
            // Diagnostics and updates remain together at the bottom like mRadio.
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

        if (section == "Microphone source") SettingCard("Microphone source") {
            Text(
                "Automatic tries each source and keeps the first with sound. Pick one yourself only if you know what works on your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceOption("Automatic (recommended)", Prefs.SOURCE_MODE_AUTO, sourceMode) {
                sourceMode = it; putString(Prefs.SOURCE_MODE, it)
            }
            RecordingEngine.Source.entries.forEach { src ->
                SourceOption(src.label, src.name, sourceMode) {
                    sourceMode = it; putString(Prefs.SOURCE_MODE, it)
                }
            }
        }

        if (section == "Capturing the other side") SettingCard("Capturing the other side") {
            ToggleRow(
                "Put call on loudspeaker",
                "Last-resort way to catch both sides: the mic records the room. Turns the speaker on while recording and restores it after.",
                autoSpeaker,
            ) { autoSpeaker = it; putBool(Prefs.AUTO_SPEAKER, it) }
            ToggleRow(
                "Force in-communication mode",
                "Helps some phones route the far side to the recording. If calls sound odd while recording, turn this off.",
                inComm,
            ) { inComm = it; putBool(Prefs.IN_COMM_MODE, it) }
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
            if (recordingMethod == ExperimentalRecording.Method.BUILT_IN_ADB) {
                AdbSetupProgressCard(
                    adbAuthorized = (connection.connected && connection.shellUid == 2000) ||
                        (helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING),
                    helperReady = helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING,
                    advancedGranted = advancedPermissionGranted,
                    advancedEnabled = advancedDetectionEnabled,
                )
                ExperimentalRecorderCard()
                // Advanced AppOp is a separate explicit user action.
                TelecomAdbInstructionCard(onAccessChanged = { permissionCheckTick++ })
            } else {
                SettingCard("ADB privileged helper") {
                    Text(
                        "The ADB helper is off. Select it under Recording method in the main Settings page to configure connection and special access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onSectionChange(null) }) {
                        Text("Go to recording method")
                    }
                }
            }
        }

        if (section == "VoIP") SettingCard("VoIP") {
            ToggleRow(
                "Record VoIP calls",
                "WhatsApp, Telegram and similar. Call detection uses Notification access; capturing the other voice depends on Android audio restrictions.",
                recordVoip,
            ) { recordVoip = it; putBool(Prefs.RECORD_VOIP, it) }
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

@Composable
private fun RecordingMethodOption(
    label: String, detail: String, selected: Boolean, onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Persistent setup feedback for ADB; disconnecting a healthy daemon doesn't undo setup. */
@Composable
private fun AdbSetupProgressCard(
    adbAuthorized: Boolean,
    helperReady: Boolean,
    advancedGranted: Boolean,
    advancedEnabled: Boolean,
    onConfigure: (() -> Unit)? = null,
) {
    // Advanced Telecom detection is only supported from Android 12. On earlier
    // versions it is skipped; the shell helper still works independently.
    val supportsAdvanced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val advancedReady = !supportsAdvanced || (advancedGranted && advancedEnabled)
    val done = listOf(adbAuthorized, helperReady, advancedReady).count { it }
    val complete = done == 3

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (complete) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (complete) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (complete) "ADB setup complete" else "ADB setup required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "$done of 3 checks complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LinearProgressIndicator(
                progress = { done / 3f },
                modifier = Modifier.fillMaxWidth(),
            )
            AdbSetupStep("ADB authorization", adbAuthorized,
                if (adbAuthorized) "Verified" else "Connect using Wireless or TCP/5555")
            AdbSetupStep("Recording helper", helperReady,
                if (helperReady) "Ready" else "Connect ADB to start or restore")
            AdbSetupStep(
                "Advanced call detection",
                advancedReady,
                when {
                    !supportsAdvanced -> "Not supported on this Android version"
                    !advancedGranted -> "Grant special access in Experimental"
                    !advancedEnabled -> "Enable advanced detection in Experimental"
                    else -> "Granted and enabled"
                },
            )
            if (onConfigure != null && !complete) {
                TextButton(onClick = onConfigure) {
                    Text("Complete ADB setup →")
                }
            }
            if (complete) {
                Text(
                    "The helper is ready, but recording both voices still requires device testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AdbSetupStep(label: String, done: Boolean, message: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.padding(start = 2.dp),
            tint = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
