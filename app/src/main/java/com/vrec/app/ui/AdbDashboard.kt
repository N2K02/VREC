package com.vrec.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
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
    recordingActive: Boolean = false,
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

    // The detached helper can keep recording without an active ADB transport.
    // Readiness is based on helper health and the armed recording state.
    val allReady = healthy && recordingOn && !busy

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = if (healthy) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(if (allReady) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                        contentDescription = if (allReady) "System ready" else "System not ready",
                        tint = if (allReady) Color(0xFF43A66A) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp))
                    Column {
                        Text("System Status", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                busy -> "Working · Checking helper connection and readiness"
                                healthy && verified -> "Connected · Helper active · ADB verified"
                                healthy -> "Ready · Helper active · ADB session detached"
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                    Stat("Recovery", restore, Modifier.weight(1f))
                    Stat("Shell UID", if (verified) "2000" else "Detached",
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
                enabled = !busy && !recordingActive && !healthy,
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("Start") }
            Button(
                onClick = {
                    scope.launch {
                        acting = true; note = null
                        note = describe(AdbPrivilegedApiHelper.stop(context))
                        acting = false
                    }
                },
                enabled = !busy && !recordingActive && healthy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.weight(1f).height(40.dp),
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
                enabled = !busy && !recordingActive && healthy && canReconnect,
                modifier = Modifier.weight(1f).height(40.dp),
            ) { Text("Restart") }
        }
        if (recordingActive) Text("Helper controls are temporarily disabled during an active recording.",
            style = MaterialTheme.typography.bodySmall)
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
    val accent = when (label) {
        "Connection" -> when (value) {
            "Connected" -> Color(0xFF43A66A)
            "Detached" -> Color(0xFFE2B448)
            else -> Color(0xFFCF5454)
        }
        "Recording" -> when (value) {
            "Armed" -> Color(0xFF43A66A)
            "Waiting" -> Color(0xFFE2B448)
            else -> Color(0xFF929AA5)
        }
        "Recovery" -> when (value) {
            "Wi-Fi" -> Color(0xFF43A66A)
            "Computer" -> Color(0xFF4BA6DD)
            "Automatic" -> Color(0xFFB27DDA)
            else -> Color(0xFF929AA5)
        }
        else -> if (value == "2000") Color(0xFFB27DDA) else Color(0xFFCF5454)
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f))) {
        Column(Modifier.padding(horizontal = 3.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                color = accent, maxLines = 1)
        }
    }
}
