package com.vrec.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.VoipNotifListener
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.delay

/** Actual Android grants, not the permissions we merely requested. */
internal object PermissionDashboardPolicy {
    fun visibleFor(method: ExperimentalRecording.Method, isMicrophone: Boolean): Boolean =
        !isMicrophone || method == ExperimentalRecording.Method.STANDARD

    fun grantedCount(grants: List<Boolean>): Int = grants.count { it }
}

@Composable
fun PermissionDashboardCard(method: ExperimentalRecording.Method) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(5_000L)
        }
    }
    val permissions = remember(tick, method) {
        fun granted(name: String) =
            context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        buildList {
            add("Phone state" to granted(Manifest.permission.READ_PHONE_STATE))
            add("Call history" to granted(Manifest.permission.READ_CALL_LOG))
            add("Contacts" to granted(Manifest.permission.READ_CONTACTS))
            if (Build.VERSION.SDK_INT >= 33)
                add("Notifications" to granted(Manifest.permission.POST_NOTIFICATIONS))
            if (PermissionDashboardPolicy.visibleFor(method, true))
                add("Microphone" to granted(Manifest.permission.RECORD_AUDIO))
            add("Accessibility" to VrecAccessibilityService.isEnabled(context))
            add("Call notification access" to VoipNotifListener.isEnabled(context))
            add("Floating controls" to (Build.VERSION.SDK_INT < 23 ||
                Settings.canDrawOverlays(context)))
            if (method == ExperimentalRecording.Method.BUILT_IN_ADB)
                add("ADB shell helper" to (ExperimentalCaptureDaemon.ready.value &&
                    AdbPrivilegedApiHelper.state.value == AdbPrivilegedApiHelper.State.RUNNING))
        }
    }
    val grantedCount = PermissionDashboardPolicy.grantedCount(permissions.map { it.second })
    Surface(shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App permissions", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("$grantedCount / ${permissions.size} available",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text("Live access status · ${if (method == ExperimentalRecording.Method.STANDARD) "Standard" else "ADB enhanced"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            permissions.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, granted) ->
                        Surface(shape = RoundedCornerShape(14.dp),
                            color = if (granted) Color(0xFF1B5E20).copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text(if (granted) "✓ Granted" else "Needs access",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (granted) Color(0xFF66BB6A)
                                        else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (pair.size == 1) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text("Some permissions require Android approval. ADB cannot bypass protected system consent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
