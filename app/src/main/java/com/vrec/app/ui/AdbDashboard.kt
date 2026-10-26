package com.vrec.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null,
                        tint = if (failed && !healthy) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp))
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                busy -> "Checking helper connection and readiness"
                                healthy && verified -> "Helper active · ADB session verified"
                                healthy -> "Helper active · ADB session detached"
                                failed -> "Check ADB setup or diagnostics"
                                else -> "Start the helper to prepare call capture"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Connection", when {
                        verified -> "Connected"
                        healthy -> "Detached"
                        else -> "Offline"
                    }, Modifier.weight(1f))
                    Stat("Recording", when {
                        !recordingOn -> "Off"
                        !healthy -> "Waiting"
                        else -> "Armed"
                    }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stat("Recovery via", restore, Modifier.weight(1f))
                    Stat("Shell identity", if (verified || healthy) "UID 2000" else "Not verified",
                        Modifier.weight(1f))
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
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

