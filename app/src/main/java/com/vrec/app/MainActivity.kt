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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ArrowForwardIos
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.CallMonitor
import com.vrec.app.calls.VoipNotifListener
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
import com.vrec.app.update.UpdateScreen
import dev.n2k02.updater.UpdateStatus

private enum class Screen { HOME, RECORDINGS, SETUP, SETTINGS, LOG, UPDATE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.initialize(applicationContext)
        LiveUpdater.init(this)
        // Draw behind system bars; Scaffold/Material components apply safe system-bar insets.
        enableEdgeToEdge()
        setContent { VrecTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Keep Settings subsection state at the navigation level, so both system
    // Back and the app-bar arrow can return to the Settings overview.
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToSettings by rememberSaveable { mutableStateOf(false) }

    fun navigateTo(destination: Screen, fromSettings: Boolean = false) {
        screen = destination
        settingsSection = null
        returnToSettings = fromSettings
    }

    fun navigateBack() {
        when {
            screen == Screen.SETTINGS && settingsSection != null -> settingsSection = null
            returnToSettings && screen != Screen.HOME -> {
                screen = Screen.SETTINGS
                returnToSettings = false
            }
            else -> navigateTo(Screen.HOME)
        }
    }

    // Leave Home unhandled so Android can close the activity normally.
    BackHandler(enabled = screen != Screen.HOME) { navigateBack() }

    val title = when (screen) {
        Screen.HOME -> "VREC"
        Screen.RECORDINGS -> "Recordings"
        Screen.SETUP -> "Setup"
        Screen.SETTINGS -> "Settings"
        Screen.LOG -> "Activity log"
        Screen.UPDATE -> "About & updates"
    }

    val updateStatus by LiveUpdater.controller.status.collectAsState()
    val hasUpdate = updateStatus is UpdateStatus.Available ||
        updateStatus is UpdateStatus.Ready ||
        updateStatus is UpdateStatus.NeedsInstallPermission

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val subpage = screen == Screen.SETUP || screen == Screen.LOG ||
                            screen == Screen.UPDATE ||
                            (screen == Screen.SETTINGS && settingsSection != null)
                        if (subpage) {
                            IconButton(onClick = { navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back")
                            }
                        } else {
                            val sectionIcon = when (screen) {
                                Screen.RECORDINGS -> Icons.Rounded.Folder
                                Screen.SETTINGS -> Icons.Rounded.Settings
                                else -> Icons.Rounded.Home
                            }
                            Icon(sectionIcon, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp))
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.ImageView(ctx).apply {
                                    setImageDrawable(ctx.packageManager.getApplicationIcon(ctx.packageName))
                                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                    contentDescription = null
                                }
                            },
                            modifier = Modifier.size(28.dp),
                        )
                        Text("VREC", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        NavigationBarItem(
                            selected = screen == Screen.HOME,
                            onClick = { navigateTo(Screen.HOME) },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                            label = { Text("Home") },
                            alwaysShowLabel = true,
                        )
                        NavigationBarItem(
                            selected = screen == Screen.RECORDINGS,
                            onClick = { navigateTo(Screen.RECORDINGS) },
                            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                            label = { Text("Recordings") },
                            alwaysShowLabel = true,
                        )
                        NavigationBarItem(
                            selected = screen == Screen.SETTINGS,
                            onClick = { navigateTo(Screen.SETTINGS) },
                            icon = {
                                BadgedBox(badge = { if (hasUpdate) Badge() }) {
                                    Icon(Icons.Rounded.Settings,
                                        contentDescription = if (hasUpdate) "Settings, update available" else null)
                                }
                            },
                            label = { Text("Settings") },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(onOpenSetup = { navigateTo(Screen.SETUP) }, onNavigate = { navigateTo(it) })
                Screen.RECORDINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { RecordingsList() }
                Screen.SETUP -> SetupScreen()
                Screen.SETTINGS -> SettingsScreen(
                    section = settingsSection,
                    onSectionChange = { settingsSection = it },
                    onOpenSetup = { navigateTo(Screen.SETUP, fromSettings = true) },
                    onOpenLog = { navigateTo(Screen.LOG, fromSettings = true) },
                    onOpenUpdates = { navigateTo(Screen.UPDATE, fromSettings = true) },
                )
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (recording) {
            (recState as? RecordingService.State.Recording)?.let { RecordingNowCard(it) }
            if (recState !is RecordingService.State.Recording) ManualRecordButton(true)
        } else if (ready) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Mic, contentDescription = null,
                                modifier = Modifier.padding(18.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("READY TO RECORD", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text("Call recording", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text("VREC is ready when you are",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ManualRecordButton(false)
                }
            }
        } else {
            SetupPromptCard(onOpenSetup)
        }

        Text("Your VREC", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        NavigationCard("Recordings", "Browse and play saved calls",
            Icons.Rounded.Folder) { onNavigate(Screen.RECORDINGS) }
        if (!ready) NavigationCard("Finish setup", "Allow recording permissions",
            Icons.Rounded.Tune, onOpenSetup)
        NavigationCard("Settings", "Recording and audio options",
            Icons.Rounded.Settings) { onNavigate(Screen.SETTINGS) }
        NavigationCard("Activity log", "Recording events and diagnostics",
            Icons.Rounded.Article) { onNavigate(Screen.LOG) }
    }
}

@Composable
private fun NavigationCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.Rounded.ArrowForwardIos,
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

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NOW RECORDING", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                (state.peer?.let { "$it · " } ?: "") + state.sourceLabel,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { fraction },
                color = MaterialTheme.colorScheme.onSurface,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.silentSoFar && silentLongEnough) {
                Text(
                    "No sound yet — this phone may be blocking call capture. Try turning on " +
                        "\"Put call on loudspeaker\" in Settings, or pick another source.",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ManualRecordButton(true)
        }
    }
}

@Composable
private fun SetupPromptCard(onOpenSetup: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "VREC needs the microphone permission and its accessibility service switched on " +
                    "before it can record calls.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSetup) { Text("Complete setup") }
        }
    }
}

/** Mic + accessibility together are the minimum for recording to work. */
fun isReady(context: Context): Boolean {
    val mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val phone = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val accessibility = com.vrec.app.calls.VrecAccessibilityService.isEnabled(context)
    return mic && phone && accessibility
}

/** Build the runtime permission set VREC asks for, by SDK level. */
fun corePermissions(): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
