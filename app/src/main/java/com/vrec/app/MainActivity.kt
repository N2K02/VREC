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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.BorderStroke
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
import com.vrec.app.recording.RecordingFailureNotice
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.vrec.app.ui.LogScreen
import com.vrec.app.ui.RecordingsList
import com.vrec.app.ui.SettingsScreen
import com.vrec.app.ui.SetupScreen
import com.vrec.app.ui.rememberReadiness
import com.vrec.app.ui.theme.VrecTheme
import com.vrec.app.ui.HomePanelStyle
import com.vrec.app.update.AppUpdater
import com.vrec.app.update.UpdateScreen

private enum class Screen { HOME, RECORDINGS, SETTINGS, LOG, UPDATE }

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_ADB_SETUP = "com.vrec.app.action.OPEN_ADB_SETUP"
    }

    private var openAdbSetup by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        LiveUpdater.init(this)
        // Draw behind system bars; Scaffold/Material components apply safe system-bar insets.
        enableEdgeToEdge()
        openAdbSetup = intent?.action == ACTION_OPEN_ADB_SETUP
        // Opening VRec after Quit resumes background ADB recovery.
        com.vrec.app.experimental.adb.AdbUserChoices.setManuallyStopped(applicationContext, false)
        com.vrec.app.experimental.adb.AdbUserChoices.markUserOpenedThisBoot(applicationContext)
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
        // AdbLifecycle owns recovery; do not launch a competing 30-second loop.
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
    val quitScope = rememberCoroutineScope()
    var quitting by remember { mutableStateOf(false) }

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
        Screen.LOG -> "Logs"
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
            val shellActive by AdbFallbackNotice.enhancedActive.collectAsState()
            val updateStatus by LiveUpdater.controller.status.collectAsState()
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.navigationBarsPadding()) {
                    if (screen != Screen.HOME &&
                        (recordingState !is RecordingService.State.Idle || shellActive)) {
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
                                if (quitting) return@NavigationBarItem
                                // Quit cannot silently abandon an ongoing call recording or import.
                                // The shell rejects exit during active capture (BUSY); keep the
                                // activity open so the call can complete and its audio be saved.
                                if (!VrecQuitSafety.idle(
                                        RecordingService.state.value,
                                        AdbFallbackNotice.enhancedActive.value,
                                        CallMonitor.isRecording)) {
                                    RecordingFailureNotice.warn(quitContext,
                                        "Recording is active. Finish the call and save its audio before quitting.")
                                    return@NavigationBarItem
                                }
                                quitting = true
                                quitScope.launch {
                                    val app = quitContext.applicationContext
                                    try {
                                        // A failed probe is unknown, not evidence of a stopped recorder.
                                        val helperConnected = ExperimentalCaptureDaemon.probe(app)
                                        val stopped = if (!helperConnected) {
                                            VrecQuitSafety.canQuitWithoutHelper(
                                                com.vrec.app.experimental.adb.ExperimentalRecording
                                                    .recoveryAuthorization(app).allowed,
                                                ExperimentalCaptureDaemon.ready.value)
                                        } else {
                                            val previous = Prefs.recordingEnabled(app)
                                            Prefs.setRecordingEnabled(app, false)
                                            val confirmed = VrecQuitSafety.sequence(
                                                stopCapture = { ExperimentalCaptureDaemon.setMode(app, false) },
                                                importCompleted = { ExperimentalCaptureDaemon.collect(app).isSuccess },
                                                quitHelper = {
                                                    ExperimentalCaptureDaemon.quit(app) ==
                                                        ExperimentalCaptureDaemon.QuitResult.STOPPED
                                                },
                                            )
                                            if (!confirmed) Prefs.setRecordingEnabled(app, previous)
                                            confirmed
                                        }
                                        if (!stopped) {
                                            RecordingFailureNotice.warn(app,
                                                "Safe shutdown was not confirmed. VRec remains open so recordings can be recovered.")
                                            return@launch
                                        }
                                        LiveUpdater.controller.cancel()
                                        RecordingFailureNotice.dismiss(app)
                                        com.vrec.app.experimental.adb.AdbLifecycle.quit(app)
                                        (quitContext as? ComponentActivity)?.finishAndRemoveTask()
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Log.w("Safe Quit failed; recordings retained for recovery", error)
                                        RecordingFailureNotice.warn(app,
                                            "Could not finish saving and stopping the recorder. Check Logs before quitting.")
                                    } finally {
                                        quitting = false
                                    }
                                }
                            },
                            enabled = !quitting,
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
                Screen.HOME -> HomeScreen(onOpenRecordings = { navigateTo(Screen.RECORDINGS) })
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
                Screen.LOG -> VRECLogsPage()
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

/** Mini transport shared with the live Home card; no redundant "tap to view" text. */
@Composable
private fun VrecRecordingMiniBar(state: RecordingService.State, onOpen: () -> Unit) {
    val shellActive by AdbFallbackNotice.enhancedActive.collectAsState()
    val shellPaused by AdbFallbackNotice.enhancedPaused.collectAsState()
    val details = rememberLiveRecordingDisplay()
    val status = when {
        shellPaused || state is RecordingService.State.Paused -> "Recording paused"
        shellActive || state is RecordingService.State.Recording -> "Recording now"
        state is RecordingService.State.Starting -> "Starting recording"
        state is RecordingService.State.Stopping -> "Saving recording"
        else -> "Recording"
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = HomePanelStyle.color(highlight = shellActive),
        border = HomePanelStyle.border(highlight = shellActive),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if ((shellActive && !shellPaused) ||
                    state is RecordingService.State.Recording) BlinkingRecordingDot()
                else Icon(Icons.Rounded.Mic, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                Column(Modifier.weight(1f).clickable(onClick = onOpen),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(status, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        LiveRecordingSourceIcon(details.info)
                        Text("${details.info.caller} · ${details.info.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(details.duration, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            LiveRecordingActions(
                state = state,
                shellActive = shellActive,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HomeScreen(onOpenRecordings: () -> Unit) {
    val context = LocalContext.current
    val ready = rememberReadiness()
    val recState by RecordingService.state.collectAsState()
    val enhancedActive by AdbFallbackNotice.enhancedActive.collectAsState()
    val enhancedPaused by AdbFallbackNotice.enhancedPaused.collectAsState()
    val manualCallAvailable by CallMonitor.manualCallAvailable.collectAsState()
    val recordingWarning by RecordingFailureNotice.warning.collectAsState()
    val paused = recState is RecordingService.State.Paused || enhancedPaused
    val confirmedRecording = !paused &&
        (recState is RecordingService.State.Recording || enhancedActive)
    var recordingEnabled by remember { mutableStateOf(Prefs.recordingEnabled(context)) }

    // Helper recovery and connection checks belong to AdbLifecycle, not
    // a screen-scoped polling coroutine that restarts on every navigation.

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Recording dashboard", style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, maxLines = 2)
                Text("Capture and connection health",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onOpenRecordings) {
                Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(17.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                Text("Library")
            }
        }
        recordingWarning?.let { warning ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = HomePanelStyle.color(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                shadowElevation = 1.dp,
            ) {
                Column(Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recording warning", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error)
                    Text(warning, style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.TextButton(
                        onClick = { RecordingFailureNotice.dismiss(context) }
                    ) { Text("Dismiss warning") }
                }
            }
        }
        RecordingMasterCard(
            state = recState,
            shellActive = enhancedActive,
            enabled = recordingEnabled,
            ready = ready,
            active = confirmedRecording,
            paused = paused,
            starting = recState is RecordingService.State.Starting,
            saving = recState is RecordingService.State.Stopping,
            onEnabledChange = { enabled ->
                CallMonitor.setRecordingEnabled(context, enabled)
                recordingEnabled = enabled
            },
        )
        if (enhancedActive) {
            RecordingNowCard(recState)
        }
        ManualRecordingCard(
            state = recState,
            enabled = recordingEnabled,
            ready = ready,
            shellActive = enhancedActive,
            callAvailable = manualCallAvailable,
        )
        Text("Connection & access", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.vrec.app.ui.LocalAdbHomeCard(
            recordingActive = recState is RecordingService.State.Recording || enhancedActive,
            standardRecording = false,
        )
        com.vrec.app.ui.PermissionDashboardCard()
        if (recordingEnabled && !ready) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = HomePanelStyle.color(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enhanced recording unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error)
                    Text("The ADB shell helper is not ready. Calls will not be recorded until enhanced capture is restored. Reconnect ADB and check System Status.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


/** Primary Home control. Actual recording status and helper readiness remain separate. */
@Composable
private fun RecordingMasterCard(
    state: RecordingService.State,
    shellActive: Boolean,
    enabled: Boolean,
    ready: Boolean,
    active: Boolean,
    paused: Boolean,
    starting: Boolean,
    saving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val dashboardState = HomeDashboardPresentation.resolve(
        enabled, ready, active, paused, starting, saving)
    val autoRecord = Prefs.autoRecord(LocalContext.current)
    val cardColor by animateColorAsState(
        targetValue = HomePanelStyle.color(highlight = ready && enabled),
        label = "Recording master card color",
    )
    val adbStatus by ExperimentalRecording.status.collectAsState()
    val badge = when (dashboardState) {
        HomeDashboardPresentation.State.RECORDING -> "RECORDING"
        HomeDashboardPresentation.State.PAUSED -> "PAUSED"
        HomeDashboardPresentation.State.STARTING -> "STARTING"
        HomeDashboardPresentation.State.SAVING -> "SAVING"
        HomeDashboardPresentation.State.OFF -> "OFF"
        HomeDashboardPresentation.State.READY -> "READY"
        HomeDashboardPresentation.State.SETUP_NEEDED ->
            if (adbStatus.busy) "CONNECTING" else "SETUP NEEDED"
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val badgeColor = when (badge) {
        "READY" -> if (dark) Color(0xFF91E3AF) else Color(0xFF237642)
        "CONNECTING" -> if (dark) Color(0xFFFFD18A) else Color(0xFF91600D)
        "RECORDING" -> if (dark) Color(0xFFFFA39D) else Color(0xFFB63832)
        "PAUSED", "STARTING", "SAVING" ->
            if (dark) Color(0xFFFFD18A) else Color(0xFF91600D)
        "OFF" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> if (dark) Color(0xFFFFC58F) else Color(0xFF99621F)
    }
    val animatedBadgeColor by animateColorAsState(badgeColor, label = "Recording status color")
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
        border = HomePanelStyle.border(highlight = enabled),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(HomeDashboardPresentation.title(dashboardState),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(HomeDashboardPresentation.detail(dashboardState, autoRecord),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(Icons.Rounded.Mic, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Recording", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(if (enabled) "Recording enabled" else "Recording disabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = RecordingTransportPresentation.canChangeMasterToggle(state, shellActive),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = animatedBadgeColor.copy(alpha = 0.14f),
                    border = BorderStroke(0.75.dp, animatedBadgeColor.copy(alpha = 0.34f)),
                ) {
                    Text(badge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = animatedBadgeColor,
                        fontWeight = FontWeight.SemiBold)
                }
                Text(
                    when {
                        active -> "Capture is active."
                        paused -> "Capture is paused."
                        starting -> "Waiting for capture confirmation."
                        saving -> "Finishing recording."
                        enabled && !ready && adbStatus.busy -> "Checking ADB authorization…"
                        enabled && !ready -> "Waiting for the shell helper."
                        enabled -> if (autoRecord) "Automatically record supported calls."
                            else "Manual mode: start recording from the call card."
                        else -> "Recording disabled."
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!RecordingTransportPresentation.canChangeMasterToggle(state, shellActive)) {
                Text("Recording cannot be disabled until the current capture finishes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Visible, call-scoped manual capture entry point; shell STARTED owns live UI. */
@Composable
private fun ManualRecordingCard(
    state: RecordingService.State,
    enabled: Boolean,
    ready: Boolean,
    shellActive: Boolean,
    callAvailable: Boolean,
) {
    val context = LocalContext.current
    val canStart = enabled && ready && callAvailable && !shellActive &&
        state is RecordingService.State.Idle
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = HomePanelStyle.color(),
        border = HomePanelStyle.border(),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp))
                Text("Record manually", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(
                when {
                    shellActive -> "A shell recording is already active. Use its controls above."
                    !enabled -> "Enable Recording above to allow a manual call segment."
                    !ready -> "Start the ADB recording helper before recording."
                    !callAvailable -> "Make or answer a supported call to enable manual recording."
                    else -> "Start a new recording segment for the connected call."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { CallMonitor.manualStart(context) },
                enabled = canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                Text(if (CallMonitor.canRecordAgain()) "Record again" else "Record now")
            }
        }
    }
}

/** Live capture: caller/app/direction and authenticated shell controls replace the dead meter. */
@Composable
private fun RecordingNowCard(state: RecordingService.State) {
    val shellActive by AdbFallbackNotice.enhancedActive.collectAsState()
    val shellPaused by AdbFallbackNotice.enhancedPaused.collectAsState()
    val details = rememberLiveRecordingDisplay()
    val paused = state is RecordingService.State.Paused || shellPaused
    val status = if (paused) "Recording paused" else "Recording in progress"
    Card(
        colors = CardDefaults.cardColors(containerColor = HomePanelStyle.color(highlight = true)),
        border = HomePanelStyle.border(highlight = true),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (paused) Icon(Icons.Rounded.Mic, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp))
                else BlinkingRecordingDot()
                Text(status, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(details.duration, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(details.info.caller, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiveRecordingSourceIcon(details.info)
                Text(details.info.source, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val direction = when (details.info.direction) {
                    com.vrec.app.recording.CallDirection.INCOMING -> "↓ Incoming"
                    com.vrec.app.recording.CallDirection.OUTGOING -> "↑ Outgoing"
                    else -> ""
                }
                if (direction.isNotEmpty()) {
                    Text(direction, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            LiveRecordingActions(
                state = state,
                shellActive = shellActive,
                compact = false,
                modifier = Modifier.fillMaxWidth(),
            )
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
