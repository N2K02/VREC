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
import androidx.compose.ui.unit.sp
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import com.vrec.app.calls.VoipNotifListener
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Actual Android grants, not the permissions we merely requested. */
internal object PermissionDashboardPolicy {
    fun visibleFor(method: ExperimentalRecording.Method, isMicrophone: Boolean): Boolean =
        !isMicrophone || method == ExperimentalRecording.Method.STANDARD

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
fun PermissionDashboardCard(method: ExperimentalRecording.Method) {
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
    LaunchedEffect(Unit) {
        while (true) { delay(5_000L); tick++ }
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
    fun tryGrant(label: String) {
        if (working) return
        actionMessage = null
        val runtime = runtimePermission[label]
        if (method == ExperimentalRecording.Method.BUILT_IN_ADB &&
            label in setOf("Phone state", "Call history", "Contacts", "Notifications", "Advanced detection")) {
            scope.launch {
                working = true
                val result = com.vrec.app.experimental.adb.AdbPermissionBootstrap.grantEligible(context)
                val stillDenied = runtime != null &&
                    context.checkSelfPermission(runtime) != PackageManager.PERMISSION_GRANTED
                actionMessage = if (result.unavailable.isEmpty()) "ADB access checks completed."
                    else result.unavailable.joinToString("; ")
                working = false
                // READ_CALL_LOG is restricted on some Android installers. ADB
                // shell UID 2000 does not override installer allowlisting.
                // Offer Android's supported runtime request as a fallback.
                if (stillDenied) {
                    actionMessage = "Android refused the ADB grant. Requesting $label directly."
                    launcher.launch(runtime!!)
                } else if (label == "Advanced detection" &&
                    !CallDetectionAccess.isGranted(context)) {
                    actionMessage = "Android refused advanced Telecom access. Check app permissions and ADB authorization."
                }
                tick++
            }
        } else if (runtime != null) {
            launcher.launch(runtime)
        } else if (label == "ADB shell helper") {
            actionMessage = "Open Experimental settings to connect and authorize the ADB helper."
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
                    Text("App Permissions", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text("ADB enhanced", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(50),
                    color = if (count == permissions.size) Color(0xFF146B3C)
                        else Color(0xFF8A2828)) {
                    Text("$count / ${permissions.size} granted",
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
            if (count != permissions.size) {
                Text(if (working) "Checking ADB grants…" else
                    "Tap any red ✕ permission badge to request access manually. VRec automatically tries supported grants when ADB shell UID 2000 connects; Android may still require your approval.",
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
