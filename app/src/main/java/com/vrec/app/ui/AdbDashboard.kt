package com.vrec.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.QuitResult
import kotlinx.coroutines.launch

/**
 * Home status for the ADB recording helper with Start / Stop / Restart.
 * Stop is refused by the helper during a call or import. Restart first makes
 * sure a verified ADB session exists, so it never stops a helper it can't start.
 */
@Composable
fun AdbDashboard(
    healthy: Boolean,
    verified: Boolean,
    working: Boolean,
    failed: Boolean,
    canReconnect: Boolean,
    recordingOn: Boolean,
    restore: String,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var acting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val busy = working || acting
    val lastRecovery by AdbHelperAutoRecovery.lastResult.collectAsState()

    fun describe(result: QuitResult): String? = when (result) {
        QuitResult.STOPPED, QuitResult.NOT_RUNNING -> null
        QuitResult.BUSY -> "A call or import is in progress. Try again after it ends."
        QuitResult.FAILED -> "The helper didn't confirm it stopped."
    }

    val (icon, title) = when {
        busy -> Icons.Rounded.Info to "Working…"
        healthy -> Icons.Rounded.CheckCircle to "Running"
        failed -> Icons.Rounded.ErrorOutline to "Needs attention"
        else -> Icons.Rounded.Info to "Not running"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (healthy) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(icon, contentDescription = null,
                    tint = if (failed && !healthy) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Stat("Connection", when {
                            verified -> "ADB"
                            healthy -> "Detached"
                            else -> "Off"
                        }, Modifier.weight(1f))
                        StatDivider()
                        Stat("Restore", restore, Modifier.weight(1f))
                        StatDivider()
                        Stat("Recording", when {
                            !healthy -> "—"
                            recordingOn -> "Armed"
                            else -> "Standby"
                        }, Modifier.weight(1f))
                        StatDivider()
                        Stat("UID", if (verified || healthy) "2000" else "—", Modifier.weight(1f),
                            hint = if (verified || healthy) "(shell)" else null)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    note = null
                    if (verified) scope.launch {
                        acting = true
                        AdbPrivilegedApiHelper.startWithAdb(context)
                            .onFailure { note = it.message ?: "Helper didn't start." }
                        acting = false
                    } else onStart()
                },
                enabled = !busy && !healthy,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Start") }
            Button(
                onClick = {
                    scope.launch {
                        acting = true; note = null
                        note = describe(AdbPrivilegedApiHelper.stop(context))
                        acting = false
                    }
                },
                enabled = !busy && healthy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Stop") }
            Button(
                onClick = {
                    scope.launch {
                        acting = true; note = null
                        val session = verified || ExperimentalRecording.connect(context).isSuccess
                        note = if (!session) "Restart needs ADB, so the helper was left running."
                        else describe(AdbPrivilegedApiHelper.stop(context))
                            ?: AdbPrivilegedApiHelper.startWithAdb(context)
                                .exceptionOrNull()?.let { it.message ?: "Helper didn't start again." }
                        acting = false
                    }
                },
                enabled = !busy && healthy && canReconnect,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Restart") }
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        lastRecovery?.let {
            Text("Last automatic recovery: $it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, hint: String? = null) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatDivider() {
    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
}
