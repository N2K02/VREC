package com.vrec.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.CallDetectionAccess

/** Instructions only: Android's elevated AppOp cannot be granted by an ordinary app. */
@Composable
fun TelecomAdbInstructionCard() {
    val context = LocalContext.current
    val resumeTick = rememberOnResumeTick()
    var checkTick by remember { mutableIntStateOf(0) }
    val granted = remember(resumeTick, checkTick) { CallDetectionAccess.isGranted(context) }
    val command = "adb shell appops set com.vrec.app android:manage_ongoing_calls allow"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enable InCallService detection (optional)",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "VREC still detects regular calls via phone state and supported VoIP calls via notifications without this command. Use ADB only to unlock the additional Telecom detection source.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text("The advanced non-UI InCallService observer requires Android 12 or newer.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("1. Enable Developer options and USB debugging. Connect your phone to a computer and authorize its ADB connection.",
                    style = MaterialTheme.typography.bodySmall)
                Text("2. Run this command in the computer's terminal:",
                    style = MaterialTheme.typography.bodySmall)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            command,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VREC InCallService ADB command", command))
                    }) { Text("Copy command") }
                    OutlinedButton(onClick = { checkTick++ }) { Text("Check status") }
                }
                Text("3. Return to VREC, tap Check status, then enable Experimental call detection in Settings.",
                    style = MaterialTheme.typography.bodySmall)
                Text(
                    if (granted) "Status: Authorized — Telecom detection can be used."
                    else "Status: Not authorized — default phone and notification detection remain active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("You can alternatively run only the shell portion of the command in an authorized Shizuku shell. The app cannot run or grant it automatically.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
