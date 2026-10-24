package com.vrec.app.ui

import androidx.compose.foundation.clickable
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
import com.vrec.app.Prefs
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.recording.RecordingEngine

@Composable
fun SettingsScreen(
    section: String?,
    onSectionChange: (String?) -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenUpdates: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { Prefs.of(context) }

    // Local mirror of the stored values, so the UI updates immediately.
    var autoRecord by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_RECORD, true)) }
    var autoSpeaker by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTO_SPEAKER, false)) }
    var inComm by remember { mutableStateOf(prefs.getBoolean(Prefs.IN_COMM_MODE, true)) }
    var recordVoip by remember { mutableStateOf(prefs.getBoolean(Prefs.RECORD_VOIP, false)) }
    var discardSilent by remember { mutableStateOf(prefs.getBoolean(Prefs.DISCARD_SILENT, false)) }
    var showOverlay by remember { mutableStateOf(prefs.getBoolean(Prefs.SHOW_OVERLAY, false)) }
    var experimentalDetection by remember { mutableStateOf(Prefs.experimentalCallDetection(context)) }
    var sourceMode by remember { mutableStateOf(Prefs.sourceMode(context)) }

    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (section == null) {
            Text("Make recording your own",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            listOf(
                Triple("Recording", "Automatic recording and silent files", Icons.Rounded.Mic),
                Triple("Microphone source", "Choose the audio input", Icons.Rounded.Tune),
                Triple("Capturing the other side", "Speaker and communication mode", Icons.Rounded.Call),
                Triple("Floating controls", "Small recording overlay", Icons.Rounded.Notifications),
                Triple("VoIP", "Internet call recording", Icons.Rounded.Call),
                Triple("Experimental call detection", "Advanced detection and ADB setup", Icons.Rounded.Tune),
            ).forEach { (name, detail, icon) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable { onSectionChange(name) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            listOf(
                Triple("Setup & permissions", "Check recording access", Icons.Rounded.Settings),
                Triple("Activity log", "Recording events and diagnostics", Icons.Rounded.Article),
                Triple("About & updates", "Version and update checks", Icons.Rounded.SystemUpdate),
            ).forEachIndexed { index, (name, detail, icon) ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(detail) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable {
                        when (index) {
                            0 -> onOpenSetup()
                            1 -> onOpenLog()
                            else -> onOpenUpdates()
                        }
                    },
                )
            }
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
            ToggleRow("Show compact overlay", "Optional icon-only Pause/Resume and Stop controls during recording. Requires Display over other apps permission in Setup.", showOverlay) {
                showOverlay = it; putBool(Prefs.SHOW_OVERLAY, it)
            }
        }

        if (section == "Experimental call detection") ExperimentalRecorderCard()

        if (section == "Experimental call detection") SettingCard("Experimental call detection") {
            ToggleRow(
                "Use InCallService detection (Android 12+)",
                "Opt in to Telecom call-state tracking with notification, Contacts and Accessibility metadata fallbacks. Does not affect audio capture or change your dialer.",
                experimentalDetection,
            ) {
                experimentalDetection = it
                putBool(Prefs.EXPERIMENTAL_CALL_DETECTION, it)
            }
            Text(
                if (CallDetectionAccess.isGranted(context)) "Telecom AppOp: authorized"
                else "Telecom AppOp: not authorized. Legacy phone and notification detection remains available.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Requires a one-time elevated AppOp grant with ADB or a Shizuku shell. Enable Notification access for VoIP and caller names; Accessibility can enrich visible call screens.",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSetup)) {
                ListItem(headlineContent = { Text("Open permission setup") })
            }
        }

        if (section == "Experimental call detection") TelecomAdbInstructionCard()

        if (section == "VoIP") SettingCard("VoIP") {
            ToggleRow(
                "Record VoIP calls",
                "WhatsApp, Telegram and similar, detected through Notification access (grant it in Setup). These usually need loudspeaker to catch both sides.",
                recordVoip,
            ) { recordVoip = it; putBool(Prefs.RECORD_VOIP, it) }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
