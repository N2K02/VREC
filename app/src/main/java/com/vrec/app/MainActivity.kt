package com.vrec.app

import android.Manifest
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.n2k02.updater.UpdateStatus
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.calls.CallMonitor
import com.vrec.app.calls.VoipNotifListener
import kotlinx.coroutines.delay
import kotlin.math.sqrt
import com.vrec.app.recording.RecordingService
import com.vrec.app.recording.AdbFallbackNotice
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vrec.app.ui.LogScreen
import com.vrec.app.ui.RecordingsList
import com.vrec.app.ui.SettingsScreen
import com.vrec.app.ui.SetupScreen
import com.vrec.app.ui.rememberReadiness
import com.vrec.app.ui.theme.VrecTheme
import com.vrec.app.update.AppUpdater
import com.vrec.app.update.UpdateScreen

private enum class Screen { HOME, RECORDINGS, SETTINGS, LOG, UPDATE }

class MainActivity : ComponentActivity() {
    private var adbRecoveryJob: kotlinx.coroutines.Job? = null

    companion object {
        const val ACTION_OPEN_ADB_SETUP = "com.vrec.app.action.OPEN_ADB_SETUP"
    }

    private var openAdbSetup by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.initialize(applicationContext)
        LiveUpdater.init(this)
        // Draw behind system bars; Scaffold/Material components apply safe system-bar insets.
        enableEdgeToEdge()
        openAdbSetup = intent?.action == ACTION_OPEN_ADB_SETUP
        // Opening VRec after Quit resumes background ADB recovery.
        com.vrec.app.experimental.adb.AdbLifecycle.startBackground(applicationContext)
        setContent {
            VrecTheme {
                App(openAdbSetup = openAdbSetup, onAdbSetupHandled = { openAdbSetup = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_ADB_SETUP) openAdbSetup = true
    }

    override fun onResume() {
        super.onResume()
        LiveUpdater.onResume(this)
        adbRecoveryJob?.cancel()
        adbRecoveryJob = lifecycleScope.launch {
            while (isActive) {
                runCatching { AdbHelperAutoRecovery.check(applicationContext) }
                    .onFailure { Log.w("ADB helper recovery failed", it) }
                delay(30_000L)
            }
        }
    }

    override fun onPause() {
        adbRecoveryJob?.cancel()
        adbRecoveryJob = null
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(openAdbSetup: Boolean, onAdbSetupHandled: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Keep Settings subsection state at the navigation level, so both system
    // Back and the app-bar arrow can return to the Settings overview.
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToSettings by rememberSaveable { mutableStateOf(false) }
    val quitContext = LocalContext.current

    LaunchedEffect(openAdbSetup) {
        if (openAdbSetup) {
            screen = Screen.HOME
            settingsSection = null
            returnToSettings = false
            onAdbSetupHandled()
        }
    }

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
        Screen.HOME -> "Home"
        Screen.RECORDINGS -> "Recordings"
        Screen.SETTINGS -> "Settings"
        Screen.LOG -> "Activity log"
        Screen.UPDATE -> "About & updates"
    }

    Scaffold(
        topBar = {
            VrecBrandHeader(
                title = if (screen == Screen.SETTINGS) {
                    if (settingsSection == "Experimental") "ADB diagnostics"
                    else settingsSection ?: "Settings"
                } else title,
                screen = screen,
            )
        },
        bottomBar = {
            val recordingState by RecordingService.state.collectAsState()
            val updateStatus by LiveUpdater.controller.status.collectAsState()
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    if (screen != Screen.HOME &&
                        recordingState !is RecordingService.State.Idle) {
                        VrecRecordingMiniBar(recordingState) { navigateTo(Screen.HOME) }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        listOf(
                            Triple(Screen.HOME, "Home", Icons.Rounded.Home),
                            Triple(Screen.RECORDINGS, "Recordings", Icons.Rounded.Folder),
                            Triple(Screen.SETTINGS, "Settings", Icons.Rounded.Settings),
                        ).forEach { (destination, label, icon) ->
                            NavigationBarItem(
                                selected = when (destination) {
                                    Screen.HOME -> screen == Screen.HOME
                                    Screen.RECORDINGS -> screen == Screen.RECORDINGS
                                    else -> screen != Screen.HOME && screen != Screen.RECORDINGS
                                },
                                onClick = { navigateTo(destination) },
                                icon = {
                                    if (destination == Screen.SETTINGS) {
                                        dev.n2k02.updater.N2K02UpdateBadgedIcon(updateStatus) {
                                            Icon(icon, contentDescription = "Settings")
                                        }
                                    } else Icon(icon, contentDescription = null)
                                },
                                label = { Text(label) },
                                alwaysShowLabel = true,
                            )
                        }
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                // Disable all recording modes, stop and save any active segment,
                                // dismiss floating controls, and disable ADB capture.
                                CallMonitor.setRecordingEnabled(quitContext, false)
                                LiveUpdater.controller.cancel()
                                // Stops recovery loop, Wi-Fi listener, discovery, in-flight
                                // connects and closes VRec's ADB session.
                                com.vrec.app.experimental.adb.AdbLifecycle.quit(quitContext)
                                (quitContext as? ComponentActivity)?.finishAndRemoveTask()
                            },
                            icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = "Quit") },
                            label = { Text("Quit") },
                            alwaysShowLabel = true,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen()
                Screen.RECORDINGS -> androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                ) { RecordingsList() }
                Screen.SETTINGS -> SettingsScreen(
                    section = settingsSection,
                    onSectionChange = { settingsSection = it },
                    onOpenLog = { navigateTo(Screen.LOG, fromSettings = true) },
                    onOpenUpdates = { navigateTo(Screen.UPDATE, fromSettings = true) },
                    onOpenRecordings = { navigateTo(Screen.RECORDINGS, fromSettings = true) },
                )
                Screen.LOG -> LogScreen()
                Screen.UPDATE -> UpdateScreen()
            }
        }
    }
}

/** MRadio-style header: screen icon/title on the left, app logo on the right. */
@Composable
private fun VrecBrandHeader(title: String, screen: Screen) {
    val icon = when (screen) {
        Screen.HOME -> Icons.Rounded.Home
        Screen.RECORDINGS -> Icons.Rounded.Folder
        Screen.SETTINGS -> Icons.Rounded.Settings
        Screen.LOG -> Icons.Rounded.Article
        Screen.UPDATE -> Icons.Rounded.Info
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.weight(1f),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp))
                Text(title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text("VRec", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Animated indicator shown only while a recording is actually active. */
@Composable
private fun BlinkingRecordingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Recording indicator")
    val opacity by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Recording dot blink",
    )
    Box(
        modifier = modifier.size(10.dp).alpha(opacity)
            .background(Color(0xFFE53935), CircleShape),
    )
}

/** Recording equivalent of MRadio's persistent Now Playing mini-bar. */
@Composable
private fun VrecRecordingMiniBar(state: RecordingService.State, onOpen: () -> Unit) {
    val peer = when (state) {
        is RecordingService.State.Recording -> state.peer
        is RecordingService.State.Paused -> state.peer
        else -> null
    }
    val status = when (state) {
        is RecordingService.State.Paused -> "Recording paused"
        is RecordingService.State.Starting -> "Starting recording"
        is RecordingService.State.Recording -> "Recording now"
        is RecordingService.State.Stopping -> "Saving recording"
        else -> "Recording"
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Mic, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (state is RecordingService.State.Recording) BlinkingRecordingDot()
                    Text(status, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(peer?.takeIf { it.isNotBlank() } ?: "Tap to view recording controls",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = "View recording",
                tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val ready = rememberReadiness()
    val recState by RecordingService.state.collectAsState()
    val enhancedActive by AdbFallbackNotice.enhancedActive.collectAsState()
    val recording = !com.vrec.app.recording.RecordingMethodPolicy.canChange(
        recState is RecordingService.State.Idle, enhancedActive)
    var recordingEnabled by remember { mutableStateOf(Prefs.recordingEnabled(context)) }
    val scope = rememberCoroutineScope()

    // Observe connection changes while Home is visible. This does not connect,
    // enable TCP debugging, or start the privileged helper without user consent.
    LaunchedEffect(Unit) {
        while (true) {
            ExperimentalRecording.refreshConnectionStatus()
            AdbPrivilegedApiHelper.checkHealth(context)
            delay(8_000L)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Recording dashboard", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text("Automatic call capture and ADB helper status in one place.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        RecordingMasterCard(enabled = recordingEnabled, ready = ready, active = recording, onEnabledChange = { enabled ->
            CallMonitor.setRecordingEnabled(context, enabled)
            recordingEnabled = enabled
        })
        com.vrec.app.ui.PermissionDashboardCard()
        com.vrec.app.ui.LocalAdbHomeCard(
            recordingActive = recState is RecordingService.State.Recording || enhancedActive,
            standardRecording = recState is RecordingService.State.Recording && !enhancedActive,
        )
        if (recordingEnabled && !ready) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enhanced recording unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("The ADB shell helper is not ready. Calls will not be recorded until enhanced capture is restored. Reconnect ADB and check System Status.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (recordingEnabled) {
            (recState as? RecordingService.State.Recording)?.let { RecordingNowCard(it) }
        }
    }
}


/** Prominent, stable-compatible master toggle shown even before permissions are granted. */
@Composable
private fun RecordingMasterCard(enabled: Boolean, ready: Boolean, active: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val cardColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "Recording master card color",
    )
    val adbStatus by ExperimentalRecording.status.collectAsState()
    val badge = when {
        !enabled -> "OFF"
        active -> "RECORDING"
        ready -> "READY"
        adbStatus.busy -> "CONNECTING"
        else -> "SETUP NEEDED"
    }
    val badgeColor = when (badge) {
        "READY" -> Color(0xFF2E7D32)
        "CONNECTING" -> Color(0xFFF9A825)
        "RECORDING" -> Color(0xFF1565C0)
        "OFF" -> Color(0xFF616161)
        else -> Color(0xFFC62828)
    }
    val animatedBadgeColor by animateColorAsState(badgeColor, label = "Recording status color")
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cardColor,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onEnabledChange(!enabled) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recording", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled && !ready) "On — waiting for ADB recording helper. Calls cannot be captured yet."
                    else if (enabled) "On — auto-start once per call; use controls to pause, stop or record again."
                    else "Off — stops all recording and closes its controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(shape = RoundedCornerShape(50),
                    color = animatedBadgeColor) {
                    Text(badge, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White)
                }
                RadioButton(selected = enabled, onClick = { onEnabledChange(!enabled) })
            }
        }
    }
}

@Composable
private fun ManualRecordButton(state: RecordingService.State) {
    val context = LocalContext.current
    when (state) {
        is RecordingService.State.Stopping -> {
            FilledTonalButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Saving recording…") }
        }
        is RecordingService.State.Idle -> {
            FilledTonalButton(
                onClick = { CallMonitor.manualStart(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (CallMonitor.canRecordAgain()) "▶  Record again" else "●  Record now") }
        }
        else -> {
            Button(
                onClick = { CallMonitor.manualStop(context) },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("■  Stop recording") }
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                BlinkingRecordingDot()
                Text("Recording now", color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.titleMedium)
            }
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
                    "No sound detected. Check the enhanced shell source and diagnostics in ADB settings.",
                    color = MaterialTheme.colorScheme.onTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Readiness depends on the capture method; ADB shell capture does not need app microphone access. */
fun isReady(context: Context): Boolean {
    val phone = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    return phone && ExperimentalCaptureDaemon.ready.value &&
        AdbPrivilegedApiHelper.state.value == AdbPrivilegedApiHelper.State.RUNNING
}

/** Requested runtime grants are applied and checked after an authorized ADB connection. */
fun corePermissions(context: Context): Array<String> = buildList {
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()
