package com.vrec.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.vrec.app.experimental.adb.AdbLifecycle
import com.vrec.app.experimental.adb.AdbTransport
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
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = HomePanelStyle.color(highlight = allReady),
            border = HomePanelStyle.border(highlight = allReady),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.size(46.dp),
                    ) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Icon(if (allReady) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                                contentDescription = if (allReady) "System ready" else "System needs attention",
                                tint = if (allReady) {
                                    if (dark) Color(0xFF91E3AF) else Color(0xFF237642)
                                } else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("System Status", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
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
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("Recovery", restore, Modifier.weight(1f))
                        Stat("Shell UID", if (verified) "2000" else "Detached", Modifier.weight(1f))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    // UID 2000 may describe the last verified transport, not a live
                    // connection (adbd restarts when switching to TCP/5555).
                    // The shared setup path re-probes/reconnects before starting
                    // the helper; never bypass it using a cached UI badge.
                    note = null
                    onStart()
                },
                enabled = !busy && !recordingActive && !healthy,
                modifier = Modifier.weight(1f).height(44.dp),
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
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text("Stop") }
            Button(
                onClick = {
                    scope.launch {
                        acting = true; note = null
                        note = AdbLifecycle.coordinateSetup {
                            // A cached verified badge is insufficient after adbd restarts.
                            // Do not stop a working helper until a live shell can restart it.
                            val verifiedLive = verified && AdbTransport.isConnected &&
                                AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()
                                    ?.lineSequence()?.any { it.trim() == "2000" } == true
                            val authorized = verifiedLive ||
                                (ExperimentalRecording.tcpPortListening() &&
                                    ExperimentalRecording.connect(context, automatic = false).isSuccess)
                            if (!authorized) {
                                "Restart needs a live UID 2000 ADB session. Repair ADB first; the working helper was preserved."
                            } else {
                                val problem = describe(AdbPrivilegedApiHelper.stop(context))
                                if (problem != null) problem
                                else AdbPrivilegedApiHelper.startWithAdb(context)
                                    .exceptionOrNull()?.message
                            }
                        }
                        acting = false
                    }
                },
                enabled = !busy && !recordingActive && healthy && canReconnect,
                modifier = Modifier.weight(1f).height(44.dp),
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = accent.copy(alpha = 0.09f),
        border = BorderStroke(0.75.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
        }
    }
}
