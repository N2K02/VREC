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
    var sourceMode by remember { mutableStateOf(Prefs.sourceMode(context)) }

    fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (section == null) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            listOf(
                Triple("Recording", "Automatic recording and silent files", Icons.Filled.Mic),
                Triple("Microphone source", "Choose the audio input", Icons.Filled.Tune),
                Triple("Capturing the other side", "Speaker and communication mode", Icons.Filled.Call),
                Triple("Floating controls", "Recording overlay", Icons.Filled.Notifications),
                Triple("VoIP", "Internet call recording", Icons.Filled.Call),
            ).forEach { (name, detail, icon) ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onSectionChange(name) }) {
                    ListItem(headlineContent = { Text(name) }, supportingContent = { Text(detail) }, leadingContent = { Icon(icon, null) })
                }
            }
            HorizontalDivider()
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSetup)) { ListItem(headlineContent = { Text("Setup & permissions") }, supportingContent = { Text("Configure recording access") }, leadingContent = { Icon(Icons.Filled.Settings, null) }) }
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLog)) { ListItem(headlineContent = { Text("Activity log") }, supportingContent = { Text("Events and diagnostics") }, leadingContent = { Icon(Icons.Filled.Article, null) }) }
            Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenUpdates)) { ListItem(headlineContent = { Text("About & updates") }, supportingContent = { Text("Version, experimental builds and check for updates") }, leadingContent = { Icon(Icons.Filled.SystemUpdate, null) }) }
        } else {
            Text("‹  Settings", modifier = Modifier.fillMaxWidth().clickable { onSectionChange(null) }.padding(vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            Text(section ?: "", style = MaterialTheme.typography.headlineSmall)
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
