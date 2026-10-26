package com.vrec.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.TermuxCommandBridge

/**
 * A console front-end to real Termux commands. It does not copy Termux into
 * VRec, nor grant shell UID 2000 to VRec or Termux.
 */
@Composable
fun TermuxTerminalCard() {
    val context = LocalContext.current
    val session by TermuxCommandBridge.status.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("adb version") }
    val installed = TermuxCommandBridge.installed(context)
    val permitted = TermuxCommandBridge.permitted(context)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
        ) {
            Text("Termux command console (optional)", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall)
            Text(if (expanded) "▲" else "▼")
        }
        if (!expanded) return@Column
        Text(
            "Run Android Platform Tools in Termux and view output here. " +
                "This is a normal app terminal. It cannot enable TCP/5555 or authorize ADB " +
                "on this same phone without an already authorized external USB host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!installed) {
            Text("Termux is not installed. Install it from F-Droid and open it once.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
                ) }
            }) { Text("Get Termux") }
        } else if (!permitted) {
            Text(
                "Allow VRec to run commands in Termux: Android Settings → Apps → VRec → " +
                    "Permissions → Additional permissions → Run commands in Termux environment.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))
                    )
                }
            }) { Text("Open VRec permissions") }
        }
        Text(
            "In Termux run once: pkg install android-tools. To let VRec send commands, set " +
                "allow-external-apps=true in ~/.termux/termux.properties, then restart Termux.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = command,
            onValueChange = { command = it.take(2048) },
            label = { Text("Termux command") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { command = "pkg install -y android-tools" },
                modifier = Modifier.weight(1f)) { Text("Install tools") }
            OutlinedButton(onClick = { command = "adb devices -l" },
                modifier = Modifier.weight(1f)) { Text("List devices") }
        }
        Button(
            onClick = { TermuxCommandBridge.run(context, command) },
            enabled = installed && permitted && !session.busy && command.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (session.busy) "Running in Termux…" else "Run command in Termux") }
        androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    session.output,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        session.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
