package com.vrec.app.ui

import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.material3.OutlinedButton
import com.vrec.app.calls.VoipNotifListener
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.AdbUserChoices
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
    // Home and Settings share the same persisted recording method.
    // Local mirror of the stored values, so the UI updates immediately.
    var autoRecord by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_RECORD, true)) }
    var recordVoip by remember { mutableStateOf(prefs.getBoolean(Prefs.RECORD_VOIP, false)) }
    var simCaptureSource by remember { mutableStateOf(EnhancedCapturePreferences.simSource(context)) }
    val voipArmed by ExperimentalCaptureDaemon.voipArmed.collectAsState()
    val voipMessage by ExperimentalCaptureDaemon.voipMessage.collectAsState()
    val simSourceMessage by ExperimentalCaptureDaemon.simSourceMessage.collectAsState()
    var watcherHealthResult by remember { mutableStateOf<String?>(null) }
    var watcherHealthChecking by remember { mutableStateOf(false) }
    var trashRetentionDays by remember { mutableStateOf(prefs.getInt("trash_retention_days", 30)) }
    var savedRetentionDays by remember { mutableStateOf(prefs.getInt("saved_retention_days", 0)) }
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
                Triple("ADB connection", "Automatic pairing, reboot recovery and network access", Icons.Rounded.Settings),
            ).forEach { (name, detail, icon) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSectionChange(name) },
                )
            }
            // Connection diagnostics and permissions remain on Home.
            HorizontalDivider()
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
        if (section == "Recording") {
            RecordingSettingsCard("Recording behavior", Icons.Rounded.Tune) {
                ToggleRow("Record calls automatically", "Start recording as soon as a call connects.", autoRecord) {
                    autoRecord = it
                    putBool(Prefs.AUTO_RECORD, it)
                    scope.launch {
                        if (!ExperimentalCaptureDaemon.setMode(context, Prefs.recordingEnabled(context)))
                            com.vrec.app.Log.w("Recording mode update was not confirmed by shell")
                    }
                }
                HorizontalDivider()
                ToggleRow(
                    "Discard silent recordings",
                    "If capture is silent, delete the recording instead of keeping it.",
                    discardSilent,
                ) { discardSilent = it; putBool(Prefs.DISCARD_SILENT, it) }
            }

            RecordingSettingsCard("Recorder health", Icons.Rounded.Mic,
                action = {
                    OutlinedButton(
                        enabled = !watcherHealthChecking,
                        onClick = {
                            watcherHealthChecking = true
                            scope.launch {
                                try {
                                    val (healthy, detail) = ExperimentalCaptureDaemon.watchHealth(context)
                                    watcherHealthResult = (if (healthy) "Healthy: " else "Attention: ") + detail
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    com.vrec.app.Log.w("Call watcher health check failed", error)
                                    watcherHealthResult = "Attention: Could not check the recorder. Retry after reconnecting ADB."
                                } finally {
                                    watcherHealthChecking = false
                                }
                            }
                        },
                    ) {
                        Text(if (watcherHealthChecking) "Checking…" else "Check call watcher")
                    }
                },
            ) {
                watcherHealthResult?.let { result ->
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("Healthy:")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                }
            }

            RecordingSettingsCard("Recording retention", Icons.Default.FolderOpen) {
                Text(
                    "Older recordings move to Recently Deleted; favorites are protected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RecordingRetentionSelector(
                    label = "Keep recordings for",
                    value = savedRetentionDays,
                    options = listOf(0 to "Never", 30 to "30 days", 90 to "90 days",
                        180 to "180 days", 365 to "1 year"),
                ) { days ->
                    savedRetentionDays = days
                    prefs.edit().putInt("saved_retention_days", days).apply()
                }
            }

            RecordingSettingsCard("Recently Deleted retention", Icons.Rounded.Article) {
                RecordingRetentionSelector(
                    label = "Keep deleted recordings for",
                    value = trashRetentionDays,
                    options = listOf(7 to "7 days", 30 to "30 days", 90 to "90 days"),
                ) { days ->
                    trashRetentionDays = days
                    prefs.edit().putInt("trash_retention_days", days).apply()
                }
                androidx.compose.material3.Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            com.vrec.app.recording.RecordingStore.cleanOldSaved(context)
                            com.vrec.app.recording.RecordingStore.purgeTrash(context)
                            com.vrec.app.recording.RecordingStore.notifySaved()
                        }
                    },
                ) { Text("Run cleanup now") }
            }
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

        if (section == "ADB connection") {
            val shared = remember(context.applicationContext) {
                com.vrec.app.experimental.adb.SharedAdbHostAdapter.core(context)
            }
            dev.n2k02.adb.core.AdbPageLifecycle(shared)
            dev.n2k02.adb.core.AdbConnectionSettingsPage(
                    adb = shared,
                    accessibilityEnabled = remember(resumeTick) {
                        VrecAccessibilityService.isEnabled(context)
                    },
                    onOpenAccessibility = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
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
                    else if (voipArmed) "VoIP watcher armed · confirm audio quality after saving a test call."
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

@Composable
private fun RecordingSettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: @Composable (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun RecordingRetentionSelector(
    label: String,
    value: Int,
    options: List<Pair<Int, String>>,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == value }?.second ?: "Never",
                    style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Choose retention")
        }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (days, name) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelected(days) },
                    trailingIcon = if (days == value) {
                        { Icon(Icons.Default.CheckCircle, contentDescription = "Selected") }
                    } else null,
                )
            }
        }
    }
}
