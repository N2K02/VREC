package com.vrec.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rikka.shizuku.Shizuku

/** The Home dashboard reports live binder permission, not cached pairing history. */
internal data class ShizukuHomeStatus(
    val serverOnline: Boolean,
    val permissionGranted: Boolean,
    val shellUid: Int?,
) {
    val ready: Boolean get() = serverOnline && permissionGranted && shellUid == Process.SHELL_UID
    val label: String get() = when {
        !serverOnline -> "Server offline"
        !permissionGranted -> "Permission required"
        shellUid != Process.SHELL_UID -> "Shell not verified"
        else -> "Connected"
    }
}

@Composable
internal fun ShizukuHomeCard(onManageAdb: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val received = Shizuku.OnBinderReceivedListener { revision++ }
        val died = Shizuku.OnBinderDeadListener { revision++ }
        val granted = Shizuku.OnRequestPermissionResultListener { _, _ -> revision++ }
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(died)
        Shizuku.addRequestPermissionResultListener(granted)
        onDispose {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(died)
            Shizuku.removeRequestPermissionResultListener(granted)
        }
    }
    val state = remember(revision) {
        val online = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permission = online && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (permission) runCatching { Shizuku.getUid() }.getOrNull() else null
        ShizukuHomeStatus(online, permission, uid)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = HomePanelStyle.color()),
        border = HomePanelStyle.border(highlight = state.ready),
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (state.ready) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Icon(
                        if (state.ready) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("ADB access", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (state.ready) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        if (state.ready) "READY" else "SETUP",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeAccessMetric("Server", if (state.serverOnline) "Online" else "Offline",
                    Modifier.weight(1f))
                HomeAccessMetric("Permission", if (state.permissionGranted) "Granted" else "Required",
                    Modifier.weight(1f))
                HomeAccessMetric("Shell", if (state.ready) "UID 2000" else "—",
                    Modifier.weight(1f))
            }
            if (!state.ready) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.WarningAmber, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp))
                    Text(state.label + " · Set up access to use privileged recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Shell permission verified. Recorder activity is shown separately above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.serverOnline) {
                Button(
                    onClick = {
                        val intent = context.packageManager
                            .getLaunchIntentForPackage("dev.n2k02.localadb")
                        if (intent != null) context.startActivity(intent)
                        else context.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/N2K02/LocalAdb/releases")))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open LocalADB") }
            } else if (!state.permissionGranted) {
                Button(
                    onClick = { runCatching { Shizuku.requestPermission(2902) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Grant ADB permission") }
            }
            FilledTonalButton(onClick = onManageAdb, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.ready) "View ADB details" else "Manage ADB connection")
            }
            if (!state.permissionGranted) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/N2K02/LocalAdb/releases")))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Get LocalADB · GitHub Releases") }
            }
        }
    }
}

@Composable
private fun HomeAccessMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
