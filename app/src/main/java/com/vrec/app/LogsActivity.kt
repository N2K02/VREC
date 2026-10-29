package com.vrec.app
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** One Settings-owned page for daily recording activity and private crash diagnostics. */
@Composable
internal fun VRECLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val recordingHistory by Log.lines.collectAsState()
    N2K02UnifiedLogsPage(
        modifier = modifier, appName = "VRec",
        extraLabel = "Recording activity",
        extraLines = recordingHistory,
        clearExtra = Log::clear,
    )
}
