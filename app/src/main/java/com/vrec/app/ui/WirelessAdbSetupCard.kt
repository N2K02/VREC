package com.vrec.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vrec.app.experimental.adb.AdbUserChoices
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.calls.VrecAccessibilityService

/**
 * Shizuku-style code entry: Android owns Wireless debugging switch and the
 * six-digit code, VRec listens for mDNS and accepts code by notification.
 * It does not use Shizuku's service or grant itself debugging privileges.
 */
@Composable
fun WirelessAdbSetupCard() {
    val context = LocalContext.current
    val wireless by LocalWirelessAdb.status.collectAsState()
    val adb by ExperimentalRecording.status.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(wireless.connectPort, wireless.pairingPort) {
        if (wireless.connectPort != null || wireless.pairingPort != null) expanded = true
    }
    var code by remember { mutableStateOf("") }
    var tcpApproved by remember { mutableStateOf(AdbUserChoices.tcpMode(context)) }
    var autoPair by remember { mutableStateOf(AdbUserChoices.autoPairCode(context)) }
    val resumeTick = rememberOnResumeTick()
    val accessibilityActive = remember(resumeTick) { VrecAccessibilityService.isEnabled(context) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Keep notification workflow compatible with split-screen Android Settings.
        LocalWirelessAdb.openSettings(context)
    }
    if (!wireless.supported) {
        Text("Wireless debugging is not supported on Android 10 or earlier. Use the USB host instructions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
            Text("Wireless debugging · pair on this phone", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Text(if (expanded) "▲" else "▼")
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automatic TCP/5555 after UID 2000", modifier = Modifier.weight(1f))
                Switch(checked = tcpApproved, onCheckedChange = { enabled ->
                    tcpApproved = enabled
                    AdbUserChoices.setTcpMode(context, enabled)
                })
            }
            Text("Enabled by default: VRec switches to TCP/5555 after verified pairing, then automatically reconnects on this phone. Other devices on the network may reach TCP/5555. Disable in Settings to close an active port when authorized.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Automatic code detection", modifier = Modifier.weight(1f))
                Switch(checked = autoPair, onCheckedChange = { enabled ->
                    autoPair = enabled
                    AdbUserChoices.setAutoPairCode(context, enabled)
                })
            }
            Text("On by default when VRec Accessibility is enabled. VRec reads only the visible Android " +
                "Wireless debugging pairing dialog. Code is never saved or logged. " +
                "Turn this off to use manual pairing only.", style = MaterialTheme.typography.bodySmall)
            if (autoPair && !accessibilityActive) {
                Text("Enable VRec's Accessibility helper to read codes automatically; " +
                    "until then, enter the code below or through the pairing notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                when {
                    adb.connected && adb.shellUid == 2000 -> "Android shell UID 2000 verified."
                    wireless.pairing -> "Pairing with Android…"
                    wireless.pairingPort != null -> if (autoPair && accessibilityActive)
                        "Pairing service found. Reading the code from Android Settings automatically…"
                    else "Pairing service found. Enter the six-digit code below or via notification."
                    wireless.connectPort != null && wireless.paired -> "Paired with Android; connecting…"
                    wireless.connectPort != null -> "Wireless debugging detected. Open its 'Pair device with pairing code' screen."
                    else -> "If your phone supports Wireless debugging, enable it in Developer options. " +
                        "Otherwise, use the USB host setup below."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (adb.shellUid != 2000) {
                Text(
                    "1. For first-time setup, enable USB debugging and Wireless debugging in Android Developer options (Wi-Fi required). " +
                        "If Android asks, tick \"Always allow on this network\".\n" +
                    "2. Tap 'Pair device with pairing code'. Keep that Android screen open.\n" +
                    "3. VRec reads the code automatically with Accessibility enabled; otherwise use notification or manual entry. Android still requires you to open its pairing dialog.\n" +
                    "4. VRec pairs, connects, verifies UID 2000 and configures the recording helper automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    if (autoPair && accessibilityActive) {
                        LocalWirelessAdb.openSettings(context)
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        LocalWirelessAdb.openSettings(context)
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Android Developer options")
                }
                if (wireless.pairingPort != null) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Six-digit pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                    Button(onClick = {
                        LocalWirelessAdb.pair(context, code)
                        code = ""
                    }, enabled = code.length == 6 && !wireless.pairing,
                        modifier = Modifier.fillMaxWidth()) { Text("Pair and configure ADB") }
                }
                if (wireless.connectPort != null && wireless.paired && adb.shellUid != 2000) {
                    OutlinedButton(onClick = { LocalWirelessAdb.retry(context) },
                        modifier = Modifier.fillMaxWidth()) { Text("Connect paired ADB") }
                }
                Text(wireless.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
                    Text("Notifications are off: automatic code detection or the manual field can still pair.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
