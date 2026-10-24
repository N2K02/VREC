package com.vrec.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.VoipNotifListener
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.corePermissions
import com.vrec.app.isReady

@Composable
fun rememberReadiness(): Boolean {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    return androidx.compose.runtime.remember(tick) { isReady(context) }
}

@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val resumeTick = rememberOnResumeTick()
    var permissionTick by remember { mutableIntStateOf(0) }
    val tick = resumeTick + permissionTick

    val micGranted = androidx.compose.runtime.remember(tick) {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val phoneGranted = androidx.compose.runtime.remember(tick) { context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }
    val contactsGranted = androidx.compose.runtime.remember(tick) { context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }
    val notificationsGranted = androidx.compose.runtime.remember(tick) { Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val accessibilityOn = androidx.compose.runtime.remember(tick) { VrecAccessibilityService.isEnabled(context) }
    val notifAccessOn = androidx.compose.runtime.remember(tick) { VoipNotifListener.isEnabled(context) }
    val incallGranted = androidx.compose.runtime.remember(tick) { CallDetectionAccess.isGranted(context) }
    val experimentalEnabled = androidx.compose.runtime.remember(tick) { Prefs.experimentalCallDetection(context) }
    val overlayAllowed = androidx.compose.runtime.remember(tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "VREC records calls without root, Shizuku or debugging. Two steps are required; the rest are optional.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Step(
            number = "1",
            title = "Microphone & phone permissions",
            done = micGranted && phoneGranted,
            body = "Needed to record and to notice when a call starts. Contacts is optional and only used to show who you were talking to.",
            actionLabel = if (micGranted && phoneGranted) "Granted" else "Grant permissions",
            onAction = { permissionLauncher.launch(corePermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()) },
            extra = {
                Note("Microphone: ${if (micGranted) "Granted" else "Not granted"}")
                Note("Phone state: ${if (phoneGranted) "Granted" else "Not granted"}")
                Note("Contacts (optional): ${if (contactsGranted) "Granted" else "Not granted"}")
                if (Build.VERSION.SDK_INT >= 33) Note("Notifications (optional): ${if (notificationsGranted) "Granted" else "Not granted"}")
            },
        )

        Step(
            number = "2",
            title = "Accessibility service",
            done = accessibilityOn,
            body = "Enable VREC in Accessibility settings, then return here and tap Check access. This does not guarantee two-sided call audio capture.",
            actionLabel = if (accessibilityOn) "Enabled" else "Open Accessibility settings",
            onAction = { context.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            onVerify = { permissionTick++ },
            extra = {
            Note(
                "If the switch is greyed out with \"Restricted setting\" (Android 13+), open " +
                    "Settings ▸ Apps ▸ VREC ▸ ⋮ ▸ Allow restricted settings, then try again. " +
                    "On Android 16 you can't turn accessibility on during a call, so set this up beforehand.",
            )
            },
        )

        Step(
            number = "3",
            title = "Floating recording controls (optional)",
            done = overlayAllowed,
            body = "Allow VREC to display a small movable Pause/Resume and Stop panel over other apps while recording. The required silent foreground-service notification remains available as a fallback.",
            actionLabel = if (overlayAllowed) "Enabled" else "Allow floating controls",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                }
            },
        )

        Step(
            number = "4",
            title = "VoIP calls (optional)",
            done = notifAccessOn,
            body = "Optional: let VREC detect call notifications from supported VoIP apps. This identifies call activity, but does not grant access to protected call audio.",
            actionLabel = if (notifAccessOn) "Granted" else "Open Notification access",
            onAction = { context.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
        )

        Step(
            number = "5",
            title = "Experimental Telecom detection (optional)",
            done = Build.VERSION.SDK_INT >= 31 && incallGranted,
            body = "Use InCallService to detect SIM and Telecom-managed third-party calls without replacing the default dialer. Android 12+ and a one-time elevated AppOp grant are required.",
            actionLabel = if (incallGranted) "Authorized" else "Check AppOp",
            onAction = { permissionTick++ },
            onVerify = { permissionTick++ },
            extra = {
                Note("Experimental detector: " + if (experimentalEnabled) "On (Settings)" else "Off (enable in Settings)")
                Note("AppOp: " + if (incallGranted) "Allowed" else "Missing — phone-state/VoIP fallback remains active")
                Note("This permission is optional. Without it, VREC retains phone-state and notification-based call detection. Use the ADB instruction card below if you want to enable InCallService.")
            },
        )

        TelecomAdbInstructionCard()

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Good to know", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Note("Whether the other person is captured depends on your phone. VREC tries several microphone sources and keeps the first that has sound; if none do, turn on \"Put call on loudspeaker\" in Settings so the mic can pick up both sides from the room.")
                Note("Recording calls may require the other person's consent where you live. You're responsible for using VREC lawfully.")
            }
        }
    }
}

@Composable
private fun Step(
    number: String,
    title: String,
    done: Boolean,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    extra: @Composable (() -> Unit)? = null,
    onVerify: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (done) "✓" else number,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  $title",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            extra?.invoke()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Granted", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                } else {
                    Button(onClick = onAction) { Text(actionLabel) }
                    if (onVerify != null) {
                        TextButton(onClick = onVerify) { Text("Check access") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun Context.openSettings(action: String, uri: Uri? = null) {
    runCatching {
        val intent = if (uri != null) Intent(action, uri) else Intent(action)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
