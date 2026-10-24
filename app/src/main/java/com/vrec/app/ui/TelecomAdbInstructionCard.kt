package com.vrec.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.Prefs
import com.vrec.app.calls.CallDetectionAccess

/** An optional, collapsed setup card; the standard detector never requires ADB. */
@Composable
fun TelecomAdbInstructionCard() {
    val context = LocalContext.current
    val resumeTick = rememberOnResumeTick()
    var checkTick by remember { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var experimentalEnabled by remember { mutableStateOf(Prefs.experimentalCallDetection(context)) }
    val granted = remember(resumeTick, checkTick) { CallDetectionAccess.isGranted(context) }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val command = "adb shell appops set com.vrec.app android:manage_ongoing_calls allow"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced call detection", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        if (!supported) "Android 12 or newer required"
                        else if (granted) "ADB access granted"
                        else "Optional • normal call detection still works",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse ADB setup" else "Expand ADB setup")
            }
            if (expanded) {
                if (!supported) {
                    Text("This option needs Android 12 or newer.",
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Use advanced detection", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = experimentalEnabled, onCheckedChange = {
                            experimentalEnabled = it
                            Prefs.of(context).edit()
                                .putBoolean(Prefs.EXPERIMENTAL_CALL_DETECTION, it).apply()
                        })
                    }
                    Text("Connect your phone to a computer with USB debugging, then run:",
                        style = MaterialTheme.typography.bodySmall)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        SelectionContainer {
                            Text(command, modifier = Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("VREC ADB command", command))
                        }) { Text("Copy command") }
                        OutlinedButton(onClick = { checkTick++ }) { Text("Check access") }
                    }
                    Text(
                        if (granted) "Ready. Advanced detection is authorized."
                        else "Not enabled yet. VREC will keep using its normal call detection.",
                        color = if (granted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
