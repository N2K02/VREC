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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
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
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
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

    fun permissionBadge(granted: Boolean): String = if (granted) "Granted" else "Not granted"

    fun badgeColor(granted: Boolean): Color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828)
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
            if (method == ExperimentalRecording.Method.BUILT_IN_ADB && Build.VERSION.SDK_INT >= 31)
                add("Advanced detection" to CallDetectionAccess.isGranted(context))
            if (method == ExperimentalRecording.Method.BUILT_IN_ADB)
                add("ADB shell helper" to (ExperimentalCaptureDaemon.ready.value &&
                    AdbPrivilegedApiHelper.state.value == AdbPrivilegedApiHelper.State.RUNNING))
        }
    }
    val grantedCount = PermissionDashboardPolicy.grantedCount(permissions.map { it.second })
    Surface(shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF156F4B).copy(alpha = 0.25f)) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(25.dp),
                        tint = Color(0xFF66D9A4))
                }
                Column(Modifier.weight(1f)) {
                    Text("App Permissions", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("Verified access · " + if (method == ExperimentalRecording.Method.STANDARD)
                        "Standard" else "ADB enhanced",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(50),
                    color = if (grantedCount == permissions.size) Color(0xFF146B3C)
                        else Color(0xFF8A2828)) {
                    Text("$grantedCount / ${permissions.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color.White, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold)
                }
            }
            permissions.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, granted) ->
                        Surface(shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(1.dp,
                                PermissionDashboardPolicy.badgeColor(granted).copy(alpha = 0.45f)),
                            modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(label, style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Surface(shape = RoundedCornerShape(50),
                                    color = PermissionDashboardPolicy.badgeColor(granted)) {
                                    Text((if (granted) "✓ " else "✕ ") +
                                        PermissionDashboardPolicy.permissionBadge(granted),
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    if (pair.size == 1)
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
            val allGranted = grantedCount == permissions.size
            Surface(shape = RoundedCornerShape(18.dp),
                color = if (allGranted) Color(0xFF135F3D).copy(alpha = 0.40f)
                    else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()) {
                Text(if (allGranted) "✓ All displayed access checks passed."
                    else "Some access is not granted. Check the red badges above.",
                    modifier = Modifier.padding(14.dp),
                    color = if (allGranted) Color(0xFF7CE0AB)
                        else MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("ADB grants eligible permissions after authorization. Android may reject restricted access; status is verified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
