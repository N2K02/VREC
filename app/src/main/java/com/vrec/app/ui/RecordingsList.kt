package com.vrec.app.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.Recording
import com.vrec.app.recording.RecordingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsList() {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    var refresh by remember { mutableStateOf(0) }
    val recordings = remember(tick, refresh) { RecordingStore.list(context) }

    var playingPath by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }

    DisposableEffect(Unit) {
        player.setOnCompletionListener { playingPath = null }
        onDispose { runCatching { player.release() } }
    }

    fun togglePlay(rec: Recording) {
        if (playingPath == rec.file.absolutePath) {
            runCatching { player.stop(); player.reset() }
            playingPath = null
        } else {
            runCatching {
                player.reset()
                player.setDataSource(rec.file.absolutePath)
                player.prepare()
                player.start()
                playingPath = rec.file.absolutePath
            }
        }
    }

    fun share(rec: Recording) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", rec.file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Text(
        "Recordings",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )

    if (recordings.isEmpty()) {
        Text(
            "No recordings yet. When setup is complete, calls you make or receive will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recordings.forEach { rec ->
            RecordingRow(
                rec = rec,
                playing = playingPath == rec.file.absolutePath,
                onPlay = { togglePlay(rec) },
                onShare = { share(rec) },
                onDelete = { confirmDelete = rec },
            )
        }
    }

    confirmDelete?.let { rec ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = { Text("This permanently removes the recording from your phone.") },
            confirmButton = {
                TextButton(onClick = {
                    if (playingPath == rec.file.absolutePath) { runCatching { player.stop(); player.reset() }; playingPath = null }
                    RecordingStore.delete(rec)
                    confirmDelete = null
                    refresh++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable { onPlay() },
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay) {
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Stop" else "Play",
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    rec.peer ?: directionLabel(rec.direction),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    buildString {
                        append(dateFmt.format(Date(rec.startedAt)))
                        if (rec.durationMs > 0) append("  ·  ${formatDuration(rec.durationMs)}")
                        rec.sourceLabel?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (rec.silent) {
                    Text(
                        "Silent — this phone blocked call capture",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

private val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

private fun directionLabel(d: CallDirection): String = when (d) {
    CallDirection.INCOMING -> "Incoming call"
    CallDirection.OUTGOING -> "Outgoing call"
    CallDirection.VOIP -> "VoIP call"
    CallDirection.UNKNOWN -> "Call"
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
