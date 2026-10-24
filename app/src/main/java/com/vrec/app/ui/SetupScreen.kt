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
import com.vrec.app.isReady

@Composable
fun rememberReadiness(): Boolean {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    return remember(tick) { isReady(context) }
}

/** Two required steps first; extra permissions and ADB are always optional and collapsed. */
@Composable
fun SetupScreen() {
    val context = LocalContext.current
    val resumeTick = rememberOnResumeTick()
    var permissionTick by remember { mutableIntStateOf(0) }
    val tick = resumeTick + permissionTick

    val basicsReady = remember(tick) {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val accessibilityOn = remember(tick) { VrecAccessibilityService.isEnabled(context) }
    val contactsOn = remember(tick) {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
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
    val optionalCount = listOf(contactsOn, floatingOn).count { it } +
        if (Build.VERSION.SDK_INT >= 33 && appAlertsOn) 1 else 0
    val optionalTotal = if (Build.VERSION.SDK_INT >= 33) 3 else 2
    val completed = listOf(basicsReady, accessibilityOn).count { it }
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
            if (completed == 2) "You're ready to record calls."
            else "Just two steps to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$completed of 2 complete", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(
            progress = { completed / 2f },
            modifier = Modifier.fillMaxWidth(),
        )

        SetupStepCard(
            number = "1",
            title = "Allow microphone & calls",
            detail = "To detect and record phone calls.",
            done = basicsReady,
            actionLabel = "Allow access",
            onAction = { request(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE) },
        )

        SetupStepCard(
            number = "2",
            title = "Turn on VREC Accessibility",
            detail = "Helps VREC capture audio during calls.",
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
                    Text("Caller identification", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExtraPermissionRow(
                        title = "Contacts",
                        detail = "Show saved caller names and numbers",
                        enabled = contactsOn,
                    ) { request(Manifest.permission.READ_CONTACTS) }
                    HorizontalDivider()
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
