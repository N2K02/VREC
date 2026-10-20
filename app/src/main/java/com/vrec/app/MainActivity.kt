package com.vrec.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.CallMonitor
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import com.vrec.app.recording.RecordingService
import com.vrec.app.ui.LogScreen
import com.vrec.app.ui.RecordingsList
import com.vrec.app.ui.SettingsScreen
import com.vrec.app.ui.SetupScreen
import com.vrec.app.ui.rememberReadiness
import com.vrec.app.ui.theme.VrecTheme
import com.vrec.app.update.AppUpdater
import com.vrec.app.update.UpdateCard
import com.vrec.app.update.UpdateScreen

private enum class Screen { HOME, RECORDINGS, SETUP, SETTINGS, LOG, UPDATE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind system bars; Scaffold/Material components apply safe system-bar insets.
        enableEdgeToEdge()
        setContent { VrecTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.onResume(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Secondary pages should return to Home on Android system back / gesture.
    // Leave the Home screen unhandled so Android can close the activity normally.
    BackHandler(enabled = screen != Screen.HOME) {
        screen = Screen.HOME
    }

    val title = when (screen) {
        Screen.HOME -> "VREC"
        Screen.RECORDINGS -> "Recordings"
        Screen.SETUP -> "Setup"
        Screen.SETTINGS -> "Settings"
        Screen.LOG -> "Activity log"
        Screen.UPDATE -> "Updates"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { screen = Screen.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(onOpenSetup = { screen = Screen.SETUP }, onNavigate = { screen = it })
                Screen.RECORDINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { RecordingsList() }
                Screen.SETUP -> SetupScreen()
                Screen.SETTINGS -> SettingsScreen()
                Screen.LOG -> LogScreen()
                Screen.UPDATE -> UpdateScreen()
            }
        }
    }
}

@Composable
private fun HomeScreen(onOpenSetup: () -> Unit, onNavigate: (Screen) -> Unit) {
    val ready = rememberReadiness()
    val recState by RecordingService.state.collectAsState()
    val recording = recState !is RecordingService.State.Idle

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        (recState as? RecordingService.State.Recording)?.let { RecordingNowCard(it) }
        UpdateCard()
        if (!ready) SetupPromptCard(onOpenSetup)
        if (ready) ManualRecordButton(recording)

        Text("Quick access", style = MaterialTheme.typography.titleMedium)
        NavigationCard(
            title = "Recordings",
            subtitle = "Browse, play and manage saved recordings",
            icon = Icons.Filled.FolderOpen,
            onClick = { onNavigate(Screen.RECORDINGS) },
        )
        NavigationCard(
            title = "Setup",
            subtitle = "Permissions and recording accessibility",
            icon = Icons.Filled.Tune,
            onClick = { onNavigate(Screen.SETUP) },
        )
        NavigationCard(
            title = "Settings",
            subtitle = "Configure recording behavior",
            icon = Icons.Filled.Settings,
            onClick = { onNavigate(Screen.SETTINGS) },
        )
        NavigationCard(
            title = "Activity log",
            subtitle = "Review app events and diagnostics",
            icon = Icons.Filled.Article,
            onClick = { onNavigate(Screen.LOG) },
        )
        NavigationCard(
            title = "Updates",
            subtitle = "Check version and release information",
            icon = Icons.Filled.SystemUpdate,
            onClick = { onNavigate(Screen.UPDATE) },
        )
    }
}


@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Open $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualRecordButton(recording: Boolean) {
    val context = LocalContext.current
    if (recording) {
        Button(
            onClick = { CallMonitor.manualStop(context) },
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("■  Stop recording") }
    } else {
        FilledTonalButton(onClick = { CallMonitor.manualStart(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("●  Record now")
        }
    }
}

@Composable
private fun RecordingNowCard(state: RecordingService.State.Recording) {
    val level by RecordingService.level.collectAsState()
    // Perceptual 0..1 bar; audio amplitude is 0..32767.
    val fraction = sqrt((level.toFloat() / 32767f).coerceIn(0f, 1f))

    // Only nag about silence after a few seconds, so a normal quiet start isn't flagged.
    var silentLongEnough by remember { mutableStateOf(false) }
    LaunchedEffect(state.silentSoFar) {
        if (state.silentSoFar) { delay(3500); silentLongEnough = true } else silentLongEnough = false
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("● Recording now", color = MaterialTheme.colorScheme.onTertiary, style = MaterialTheme.typography.titleMedium)
            Text(
                (state.peer?.let { "$it · " } ?: "") + state.sourceLabel,
                color = MaterialTheme.colorScheme.onTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { fraction },
                color = MaterialTheme.colorScheme.onTertiary,
                trackColor = MaterialTheme.colorScheme.onTertiary.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.silentSoFar && silentLongEnough) {
                Text(
                    "No sound yet — this phone may be blocking call capture. Try turning on " +
                        "\"Put call on loudspeaker\" in Settings, or pick another source.",
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SetupPromptCard(onOpenSetup: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            Text(
                "VREC needs the microphone permission and its accessibility service switched on " +
                    "before it can record calls.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSetup) { Text("Open setup") }
        }
    }
}

/** Mic + accessibility together are the minimum for recording to work. */
fun isReady(context: Context): Boolean {
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return mic && com.vrec.app.calls.VrecAccessibilityService.isEnabled(context)
}

/** Build the runtime permission set VREC asks for, by SDK level. */
fun corePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
