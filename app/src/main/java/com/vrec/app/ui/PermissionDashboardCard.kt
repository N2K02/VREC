package com.vrec.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import com.vrec.app.calls.VoipNotifListener
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.AdbForegroundSetup
import com.vrec.app.experimental.adb.LocalAdbMiddleware
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.launch

/** Actual Android grants, not the permissions we merely requested. */
internal object PermissionDashboardPolicy {
    fun visibleFor(isMicrophone: Boolean): Boolean = !isMicrophone

    fun grantedCount(grants: List<Boolean>): Int = grants.count { it }

    fun permissionBadge(granted: Boolean): String = if (granted) "Granted" else "Not granted"

    fun badgeColor(granted: Boolean): Color = if (granted) Color(0xFF2E7D32) else Color(0xFFC62828)
}

/** Android controls special access; only runtime grants are requested directly. */
internal fun permissionIntent(label: String): String = when (label) {
    "Accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
    "Call notification access" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
    "Floating controls" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
    else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
}

@Composable
fun PermissionDashboardCard() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var actionMessage by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var working by remember { androidx.compose.runtime.mutableStateOf(false) }
    val runtimePermission = mapOf(
        "Phone state" to Manifest.permission.READ_PHONE_STATE,
        "Call history" to Manifest.permission.READ_CALL_LOG,
        "Contacts" to Manifest.permission.READ_CONTACTS,
        "Notifications" to Manifest.permission.POST_NOTIFICATIONS,
        "Microphone" to Manifest.permission.RECORD_AUDIO,
    )
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { tick++ }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    // Observe real grant/helper transitions instead of polling Android every
    // five seconds while Home is visible. ON_RESUME still catches Settings changes.
    val grants by com.vrec.app.experimental.adb.AdbPermissionBootstrap.status.collectAsState()
    val helperReady by ExperimentalCaptureDaemon.ready.collectAsState()
    val foregroundBusy by AdbForegroundSetup.busy.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val permissions = remember(tick, helperReady, helperState, grants) {
        fun granted(name: String) =
            context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        buildList {
            add("Phone state" to granted(Manifest.permission.READ_PHONE_STATE))
            add("Call history" to granted(Manifest.permission.READ_CALL_LOG))
            add("Contacts" to granted(Manifest.permission.READ_CONTACTS))
            if (Build.VERSION.SDK_INT >= 33)
                add("Notifications" to granted(Manifest.permission.POST_NOTIFICATIONS))
            if (PermissionDashboardPolicy.visibleFor(true))
                add("Microphone" to granted(Manifest.permission.RECORD_AUDIO))
            add("Accessibility" to VrecAccessibilityService.isEnabled(context))
            add("Call notification access" to VoipNotifListener.isEnabled(context))
            add("Floating controls" to (Build.VERSION.SDK_INT < 23 ||
                Settings.canDrawOverlays(context)))
            if (Build.VERSION.SDK_INT >= 31)
                add("Advanced detection" to CallDetectionAccess.isGranted(context))
        }
    }
    fun tryGrant(label: String) {
        if (working || foregroundBusy) return
        actionMessage = null
        val runtime = runtimePermission[label]
        if (label in setOf("Phone state", "Call history", "Contacts", "Notifications", "Advanced detection")) {
            scope.launch {
                working = true
                try {
                    val shell = ExperimentalRecording.status.value
                    val authorized = shell.connected && shell.shellUid == 2000 &&
                        com.vrec.app.experimental.adb.AdbTransport.isConnected
                    // A runtime permission can be requested without ADB. Avoid
                    // making Android's permission dialog depend on shell access.
                    val result = if (authorized)
                        com.vrec.app.experimental.adb.AdbPermissionBootstrap.grantEligible(context)
                    else null
                    val stillDenied = runtime != null &&
                        context.checkSelfPermission(runtime) != PackageManager.PERMISSION_GRANTED
                    actionMessage = when {
                        result != null && result.finished && result.unavailable.isEmpty() ->
                            "ADB permission checks completed."
                        result != null && result.finished -> result.unavailable.joinToString("; ")
                        label == "Advanced detection" ->
                            "Advanced Telecom detection needs verified shell UID 2000. Repair ADB on Home; this AppOp is optional."
                        else -> "Android can request this permission directly without ADB."
                    }
                    if (stillDenied) {
                        // Restricted call log permission may still require
                        // Android installer allowlisting or special approval.
                        actionMessage = if (label == "Call history")
                            "Requesting Call history. Android may require restricted-settings approval in App info."
                        else "Requesting $label from Android."
                        launcher.launch(runtime!!)
                    } else if (label == "Advanced detection" &&
                        !CallDetectionAccess.isGranted(context)) {
                        actionMessage = "Advanced Telecom detection is optional; recording can still use standard call-state detection."
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    actionMessage = "Could not check $label: " +
                        (failure.message ?: "Android permission request failed") +
                        ". Open Android App info > Permissions."
                } finally {
                    working = false
                    tick++
                }
            }
        } else if (runtime != null) {
            launcher.launch(runtime)
        } else if (label == "ADB shell helper") {
            // Start is application-owned so navigation to Android Settings
            // cannot cancel an in-progress pairing or shell launch.
            actionMessage = if (AdbForegroundSetup.begin(context))
                "Starting ADB helper. Connection, permissions and readiness will update automatically."
            else "ADB setup is already running; check its status on Home."
        } else {
            val action = permissionIntent(label)
            val intent = android.content.Intent(action).apply {
                if (action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION ||
                    action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }.onFailure {
                actionMessage = "Android settings could not be opened."
            }
        }
    }
    val count = PermissionDashboardPolicy.grantedCount(permissions.map { it.second })
    Surface(shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null,
                    tint = Color(0xFF66D9A4), modifier = Modifier.size(27.dp))
                Column(Modifier.weight(1f)) {
                    Text("Android permissions", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("ADB enhanced", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(50),
                    color = if (count == permissions.size) Color(0xFF146B3C)
                        else Color(0xFF8A2828)) {
                    Text("$count / ${permissions.size} enabled",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold)
                }
            }
            permissions.chunked(3).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEach { (label, granted) ->
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = androidx.compose.foundation.BorderStroke(1.dp,
                                PermissionDashboardPolicy.badgeColor(granted).copy(alpha = 0.55f)),
                            modifier = Modifier.weight(1f)
                                .then(if (!granted) Modifier.clickable { tryGrant(label) } else Modifier)) {
                            Row(Modifier.padding(horizontal = 7.dp, vertical = 10.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(if (granted) "✓" else "✕",
                                    modifier = Modifier
                                        .background(PermissionDashboardPolicy.badgeColor(granted),
                                            androidx.compose.foundation.shape.CircleShape)
                                        .padding(horizontal = 5.dp, vertical = 2.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold)
                                Text(label, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, lineHeight = 13.sp,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    repeat(3 - group.size) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            // Helper health is a service state, not an Android permission.
            // A broken helper must never make all granted permissions look denied.
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING ->
                            "ADB recording helper: Ready"
                        foregroundBusy || helperState == AdbPrivilegedApiHelper.State.STARTING ->
                            "ADB recording helper: Starting…"
                        else -> "ADB recording helper: Needs repair"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (!helperReady || helperState != AdbPrivilegedApiHelper.State.RUNNING) {
                    OutlinedButton(onClick = { tryGrant("ADB shell helper") },
                        enabled = !foregroundBusy && !working) { Text("Repair") }
                }
            }
            if (count != permissions.size) {
                Text(if (working) "Checking ADB grants…" else
                    "Tap a red ✕ for the available Android approval steps. VRec requests eligible grants after verified ADB UID 2000; Accessibility, notification access and overlays are Android-controlled and optional for some features.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actionMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
