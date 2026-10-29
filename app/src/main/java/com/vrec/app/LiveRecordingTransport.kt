package com.vrec.app

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vrec.app.recording.AdbFallbackNotice
import com.vrec.app.recording.EnhancedCaptureControlReceiver
import com.vrec.app.recording.RecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal data class LiveRecordingDisplay(
    val info: LiveRecordingInfo,
    val duration: String,
    val paused: Boolean,
)

@Composable
internal fun rememberLiveRecordingDisplay(): LiveRecordingDisplay {
    val context = LocalContext.current
    val file by AdbFallbackNotice.recordingFile.collectAsState()
    val startedAt by AdbFallbackNotice.segmentStartedAt.collectAsState()
    val pausedMs by AdbFallbackNotice.pausedElapsedMs.collectAsState()
    val paused by AdbFallbackNotice.enhancedPaused.collectAsState()
    val active by AdbFallbackNotice.enhancedActive.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var lookupTick by remember(file) { mutableStateOf(0) }

    LaunchedEffect(file, active, paused) {
        while (isActive && active) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
            lookupTick++
        }
    }
    // Call details can arrive after the shell STARTED wake. Revisit only once
    // every five seconds, never query contact providers on the UI thread.
    val info by produceState(
        initialValue = LiveRecordingInfo("Unknown caller", "ADB shell", null),
        file, lookupTick / 5,
    ) {
        value = withContext(Dispatchers.IO) {
            LiveRecordingInfoResolver.fromShellFile(context.applicationContext, file)
        }
    }
    val elapsed = RecordingTransportPresentation.elapsedSegment(
        startedAt, nowMs, if (paused) pausedMs else null)
    return LiveRecordingDisplay(info,
        RecordingTransportPresentation.durationLabel(elapsed), paused)
}

/** Controls send the same authenticated shell broadcast as notification actions. */
@Composable
internal fun LiveRecordingActions(
    state: RecordingService.State,
    shellActive: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pending by EnhancedCaptureControlReceiver.pendingAction.collectAsState()
    val shellPaused by AdbFallbackNotice.enhancedPaused.collectAsState()
    val paused = shellPaused || state is RecordingService.State.Paused
    val enabled = RecordingTransportPresentation.canPauseOrResume(state, shellActive) &&
        pending == null
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        OutlinedButton(
            onClick = {
                EnhancedCaptureControlReceiver.request(context,
                    if (paused) 'R' else 'P')
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(43.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        ) {
            Icon(
                if (paused)
                    Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 17.dp else 19.dp),
            )
            Text(if (paused) "Resume" else "Pause")
        }
        Button(
            onClick = { EnhancedCaptureControlReceiver.request(context, 'S') },
            enabled = RecordingTransportPresentation.canStop(state, shellActive) &&
                pending == null,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.weight(1f).height(43.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null,
                modifier = Modifier.size(if (compact) 17.dp else 19.dp))
            Text(if (compact) "Stop" else "Stop & Save")
        }
    }
}

/** Show the actual installed VoIP app icon, falling back to the phone/app symbol. */
@Composable
internal fun LiveRecordingSourceIcon(info: LiveRecordingInfo) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, info.packageName) {
        value = info.packageName?.let { packageName ->
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            }
        }
    }
    if (drawable != null) {
        AndroidView(
            factory = { ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } },
            update = { it.setImageDrawable(drawable) },
            modifier = Modifier.size(20.dp),
        )
    } else {
        Icon(
            if (info.packageName == null) Icons.Rounded.Phone else Icons.Rounded.Apps,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(19.dp),
        )
    }
}
