package com.vrec.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import androidx.compose.runtime.collectAsState
import com.vrec.app.isReady

@Composable
fun rememberReadiness(): Boolean {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    val shellReady by ExperimentalCaptureDaemon.ready.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    // Update the Home readiness indicator as ADB helper state changes, not just on resume.
    return remember(tick, shellReady, helperState) { isReady(context) }
}

/** Recording setup is separate from the Contacts permission used for caller name matching. */
@Composable
fun SetupScreen(onOpenAdbSetup: () -> Unit = {}) {
    val context = LocalContext.current
    val adbSelected = true
    val shellReady by ExperimentalCaptureDaemon.ready.collectAsState()
    val helperState by AdbPrivilegedApiHelper.state.collectAsState()
    val adbReady = shellReady && helperState == AdbPrivilegedApiHelper.State.RUNNING
    val resumeTick = rememberOnResumeTick()
    var permissionTick by remember { mutableIntStateOf(0) }
    val tick = resumeTick + permissionTick

    val microphoneOn = remember(tick) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val phoneStateOn = remember(tick) {
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val basicsReady = phoneStateOn && microphoneOn
    val accessibilityOn = remember(tick) { VrecAccessibilityService.isEnabled(context) }
    val contactsOn = remember(tick) {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    val callLogOn = remember(tick) {
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }
    val appAlertsOn = remember(tick) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val floatingOn = remember(tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var accessibilityHelpOpen by rememberSaveable { mutableStateOf(false) }
    val optionalCount = (if (floatingOn) 1 else 0) +
        (if (adbSelected && microphoneOn) 1 else 0) +
        (if (adbSelected && accessibilityOn) 1 else 0) +
        (if (Build.VERSION.SDK_INT >= 33 && appAlertsOn) 1 else 0)
    val optionalTotal = (if (Build.VERSION.SDK_INT >= 33) 2 else 1) +
        if (adbSelected) 2 else 0
    val completed = if (adbSelected)
        listOf(phoneStateOn, adbReady, callLogOn).count { it }
    else listOf(basicsReady, accessibilityOn, callLogOn).count { it }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }
    fun request(vararg permissions: String) {
        val missing = permissions.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up VREC", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (completed == 3) "VREC's selected recording mode is set up."
            else if (adbSelected) "Complete enhanced ADB setup. Microphone access is only for fallback."
            else "Complete standard microphone recording setup.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$completed of 3 complete", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { completed / 3f },
            modifier = Modifier.fillMaxWidth(),
        )

        if (adbSelected) {
            SetupStepCard(
                number = "1",
                title = "Allow SIM call detection",
                detail = "Phone state helps identify call lifecycle. Enhanced shell capture does not use VREC's microphone permission.",
                done = phoneStateOn,
                actionLabel = "Allow calls",
                onAction = { request(Manifest.permission.READ_PHONE_STATE) },
            )
            SetupStepCard(
                number = "2",
                title = "Authorize enhanced ADB shell capture",
                detail = "Connect and authorize the shell UID 2000 helper. This is the enhanced audio recorder, not the phone microphone.",
                done = adbReady,
                actionLabel = "Open ADB setup",
                onAction = onOpenAdbSetup,
            )
        } else {
            SetupStepCard(
                number = "1",
                title = "Allow microphone & calls",
                detail = "Both permissions are needed for standard microphone recording.",
                done = basicsReady,
                actionLabel = "Allow access",
                onAction = { request(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE) },
            )
            SetupStepCard(
                number = "2",
                title = "Turn on VREC Accessibility",
                detail = "Required for standard call capture on supported phones.",
                done = accessibilityOn,
                actionLabel = "Open settings",
                onAction = { context.openPermissionSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                footer = {
                    if (!accessibilityOn) {
                        TextButton(onClick = { accessibilityHelpOpen = !accessibilityHelpOpen }) {
                            Text(if (accessibilityHelpOpen) "Hide help" else "Can't turn it on?")
                        }
                        if (accessibilityHelpOpen) {
                            Text(
                                "If Android says Restricted setting: Settings → Apps → VREC → ⋮ → Allow restricted settings. Then return and turn on VREC Accessibility.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }

        SetupStepCard(
            number = "3",
            title = "Allow call history (required for SIM identification)",
            detail = "Matches finished SIM recordings to their caller numbers. " +
                "Android may restrict this permission depending on how VREC was installed.",
            done = callLogOn,
            actionLabel = "Allow Call log",
            onAction = { request(Manifest.permission.READ_CALL_LOG) },
            footer = {
                if (!callLogOn) Text(
                    "If permission cannot be granted, recording still works. " +
                        "Android sometimes requires installer allowlisting for Call log access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )

        // Contacts are recommended; call-log names also work when the dialer cached a name.
        SetupStepCard(
            number = "4",
            title = "Show saved contact names (recommended)",
            detail = "Allow Contacts so VREC can match numbers recovered from call history " +
                "to your saved contacts.",
            done = contactsOn,
            actionLabel = "Allow Contacts",
            onAction = { request(Manifest.permission.READ_CONTACTS) },
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { moreOpen = !moreOpen },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Optional permissions", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("$optionalCount of $optionalTotal granted · Only what you need",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (moreOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (moreOpen) "Collapse extra features" else "Expand extra features",
                    )
                }
                if (moreOpen) {
                    HorizontalDivider()
                    if (adbSelected) {
                        Text("Standard fallback (optional)", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "The ADB shell helper records without VREC microphone permission. " +
                                "Allow microphone and Accessibility only if you want standard recording " +
                                "to continue when the enhanced helper is unavailable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ExtraPermissionRow(
                            title = "Fallback microphone",
                            detail = "RECORD_AUDIO for standard fallback only",
                            enabled = microphoneOn,
                        ) { request(Manifest.permission.RECORD_AUDIO) }
                        ExtraPermissionRow(
                            title = "Fallback Accessibility",
                            detail = "Standard call-audio capture helper",
                            enabled = accessibilityOn,
                        ) {
                            context.openPermissionSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        }
                        HorizontalDivider()
                    }
                    Text("Recording controls", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Build.VERSION.SDK_INT >= 33) {
                        ExtraPermissionRow(
                            title = "App notifications",
                            detail = "Show recording progress and controls",
                            enabled = appAlertsOn,
                        ) { request(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                    ExtraPermissionRow(
                        title = "Floating controls",
                        detail = "Show recording controls over other apps",
                        enabled = floatingOn,
                    ) {
                        context.openPermissionSettings(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                }
            }
        }

        Text(
            "Audio quality depends on your phone. Recording calls may require consent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupStepCard(
    number: String,
    title: String,
    detail: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (done) Icon(Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                else Text(number, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (done) {
                Text("Enabled", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
            footer?.invoke()
        }
    }
}

@Composable
private fun ExtraPermissionRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Text("Granted", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium)
            }
        } else {
            OutlinedButton(onClick = onAction) { Text("Allow") }
        }
    }
}

private fun Context.openPermissionSettings(action: String, uri: Uri? = null) {
    runCatching {
        val intent = if (uri == null) Intent(action) else Intent(action, uri)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
