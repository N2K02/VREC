package com.vrec.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.vrec.app.experimental.adb.ExperimentalRecording
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.Prefs
import com.vrec.app.calls.CallDetectionAccess

/** One source of truth for the advanced call detection toggle and its AppOp status. */
@Composable
fun TelecomAdbInstructionCard(onAccessChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val resumeTick = rememberOnResumeTick()
    var checkTick by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(Prefs.experimentalCallDetection(context)) }
    val connection by ExperimentalRecording.status.collectAsState()
    val scope = rememberCoroutineScope()
    var granting by remember { mutableStateOf(false) }
    var grantError by remember { mutableStateOf<String?>(null) }
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // Re-read after coming back to VRec or explicitly tapping Check access.
    // The older Settings card did neither, so it could show a stale "not authorized".
    val granted = remember(context, resumeTick, checkTick) {
        supported && CallDetectionAccess.isGranted(context)
    }
    val command = "adb shell appops set ${context.packageName} android:manage_ongoing_calls allow"

    fun updateEnabled(newValue: Boolean) {
        enabled = newValue
        Prefs.of(context).edit()
            .putBoolean(Prefs.EXPERIMENTAL_CALL_DETECTION, newValue).apply()
        onAccessChanged()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Advanced call detection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Improve caller identification (Android 12+).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PermissionStatusBadge(
                    label = when {
                        !supported -> "Unavailable"
                        granted -> "Granted"
                        else -> "Not granted"
                    },
                    granted = granted,
                )
                IconButton(
                    onClick = { checkTick++; onAccessChanged() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Recheck permission")
                }
            }

            if (!supported) {
                Text(
                    "Requires Android 12 or newer. Normal call detection still works.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Use advanced detection", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = ::updateEnabled)
                }

                if (!granted) {
                    Text(
                        "Advanced call detection needs a separate Android AppOp grant. " +
                            "This does not give the recorder root or unlock two-sided audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (connection.connected && connection.shellUid == 2000) {
                        Button(
                            enabled = !granting && !connection.busy,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                scope.launch {
                                    granting = true
                                    grantError = null
                                    val result = CallDetectionAccess.grantWithAdb(context)
                                    granting = false
                                    if (result.isSuccess) {
                                        updateEnabled(true)
                                        checkTick++
                                        onAccessChanged()
                                    } else grantError = result.exceptionOrNull()?.message
                                }
                            },
                        ) {
                            Text(if (granting) "Granting special access…" else "Grant special access")
                        }
                    } else {
                        Text(
                            "Connect ADB above to grant special access directly in VRec. " +
                                "If unavailable, use the USB command below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    grantError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "Alternative command from a computer with USB debugging:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                command,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    // Equal-width buttons use the full available screen width.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("VRec ADB command", command),
                                )
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Copy command")
                        }
                        OutlinedButton(
                            onClick = { checkTick++; onAccessChanged() },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Check access")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusBadge(label: String, granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (granted) Color(0xFF176C45) else Color(0xFFB3261E),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = Color.White,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
