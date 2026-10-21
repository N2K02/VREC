package com.vrec.app.ui

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class RecordingFilter { ALL, INCOMING, OUTGOING, VOIP, FAVORITES, SILENT }
private enum class RecordingSort { NEWEST, OLDEST, LONGEST, LARGEST, NAME }

@Composable
fun RecordingsList() {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    var refresh by remember { mutableStateOf(0) }
    val recordings = remember(tick, refresh) { RecordingStore.list(context) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    var currentRecording by remember { mutableStateOf<Recording?>(null) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RecordingFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(RecordingSort.NEWEST) }
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var speed by rememberSaveable { mutableStateOf(1f) }
    val totalBytes = recordings.sumOf { it.sizeBytes }

    val visibleRecordings = recordings.filter { rec ->
        val q = search.trim()
        val textMatch = q.isEmpty() || listOfNotNull(
            rec.peer, rec.phoneNumber, rec.contactName, rec.countryCode, rec.simLabel, rec.carrier,
            rec.notes, rec.sourceLabel, directionLabel(rec.direction), rec.file.name,
            dateFmt.format(Date(rec.startedAt))
        ).any { it.contains(q, ignoreCase = true) }
        textMatch && when (filter) {
            RecordingFilter.ALL -> true
            RecordingFilter.INCOMING -> rec.direction == CallDirection.INCOMING
            RecordingFilter.OUTGOING -> rec.direction == CallDirection.OUTGOING
            RecordingFilter.VOIP -> rec.direction == CallDirection.VOIP
            RecordingFilter.FAVORITES -> rec.favorite
            RecordingFilter.SILENT -> rec.silent
        }
    }.let { list ->
        when (sort) {
            RecordingSort.NEWEST -> list.sortedByDescending { it.startedAt }
            RecordingSort.OLDEST -> list.sortedBy { it.startedAt }
            RecordingSort.LONGEST -> list.sortedByDescending { it.durationMs }
            RecordingSort.LARGEST -> list.sortedByDescending { it.sizeBytes }
            RecordingSort.NAME -> list.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
        }
    }

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
                if (Build.VERSION.SDK_INT >= 23) player.playbackParams = player.playbackParams.setSpeed(speed)
                player.start()
                playingPath = rec.file.absolutePath
            }
        }
    }

    fun share(rec: Recording) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", rec.file)
        val intent = Intent(Intent.ACTION_SEND).setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun shareDetails(rec: Recording) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "VREC recording details")
            .putExtra(Intent.EXTRA_TEXT, RecordingStore.exportDetails(rec))
        runCatching { context.startActivity(Intent.createChooser(intent, "Export recording details").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Text("Recordings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    Text("${recordings.size} recording${if (recordings.size == 1) "" else "s"} · ${formatSize(totalBytes)}",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (recordings.isNotEmpty()) {
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Search caller, number, note…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { filterMenu = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.FilterList, contentDescription = null)
                Text("  ${filterLabel(filter)}")
            }
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                RecordingFilter.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(filterLabel(item)) }, onClick = { filter = item; filterMenu = false })
                }
            }
            TextButton(onClick = { sortMenu = true }) { Text("Sort") }
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                RecordingSort.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(sortLabel(item)) }, onClick = { sort = item; sortMenu = false })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                selectionMode = !selectionMode
                selectedPaths = emptySet()
            }) { Text(if (selectionMode) "Cancel selection" else "Select multiple") }
            if (selectionMode) {
                TextButton(onClick = { selectedPaths = if (selectedPaths.size == visibleRecordings.size) emptySet() else visibleRecordings.map { it.file.absolutePath }.toSet() }) {
                    Text(if (selectedPaths.size == visibleRecordings.size) "Select none" else "Select all")
                }
                TextButton(onClick = { if (selectedPaths.isNotEmpty()) confirmDeleteSelected = true }) {
                    Text("Delete (${selectedPaths.size})", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Button(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Delete, contentDescription = null)
            Text("  Delete all recordings")
        }
    }

    if (recordings.isEmpty()) {
        Text("No recordings yet. When setup is complete, calls you make or receive will show up here.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (visibleRecordings.isEmpty()) {
        Text("No recordings match these search and filter settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleRecordings.forEach { rec ->
                RecordingRow(rec = rec, playing = playingPath == rec.file.absolutePath,
                    selected = rec.file.absolutePath in selectedPaths, selectionMode = selectionMode,
                    onSelect = {
                        selectedPaths = if (rec.file.absolutePath in selectedPaths) selectedPaths - rec.file.absolutePath
                            else selectedPaths + rec.file.absolutePath
                    }, onOpen = { currentRecording = rec }, onPlay = { togglePlay(rec) },
                    onShare = { share(rec) }, onDelete = { confirmDelete = rec },
                    onFavorite = { RecordingStore.updateDetails(rec, rec.notes, !rec.favorite); refresh++ })
            }
        }
    }

    currentRecording?.let { rec ->
        RecordingDetailsDialog(rec = recordings.firstOrNull { it.file == rec.file } ?: rec,
            playing = playingPath == rec.file.absolutePath, speed = speed,
            onDismiss = { currentRecording = null },
            onSave = { notes, favorite, name, number, country, sim, carrier ->
                RecordingStore.updateDetails(rec, notes, favorite, name, number, country, sim, carrier)
                refresh++
                currentRecording = null
            },
            onPlay = { togglePlay(rec) },
            onSpeed = {
                speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }
                if (playingPath == rec.file.absolutePath && Build.VERSION.SDK_INT >= 23) {
                    runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
                }
            },
            onBookmark = {
                val position = if (playingPath == rec.file.absolutePath) runCatching { player.currentPosition.toLong() }.getOrDefault(0L) else 0L
                RecordingStore.addBookmark(rec, position)
                refresh++
            },
            onShareAudio = { share(rec) }, onExportDetails = { shareDetails(rec) },
        )
    }

    if (confirmDeleteAll || confirmDeleteSelected || confirmDelete != null) {
        val deletingAll = confirmDeleteAll
        val deletingSelected = confirmDeleteSelected
        val one = confirmDelete
        AlertDialog(onDismissRequest = { confirmDeleteAll = false; confirmDeleteSelected = false; confirmDelete = null },
            title = { Text(if (deletingAll) "Delete all recordings?" else if (deletingSelected) "Delete selected recordings?" else "Delete recording?") },
            text = { Text(if (deletingAll) "Permanently delete all ${recordings.size} audio files and their details from this phone? This cannot be undone."
                else if (deletingSelected) "Permanently delete ${selectedPaths.size} selected recordings? This cannot be undone."
                else "Permanently remove this recording from your phone?") },
            confirmButton = {
                TextButton(onClick = {
                    val targets = when {
                        deletingAll -> recordings
                        deletingSelected -> recordings.filter { it.file.absolutePath in selectedPaths }
                        else -> listOfNotNull(one)
                    }
                    if (targets.any { it.file.absolutePath == playingPath }) {
                        runCatching { player.stop(); player.reset() }; playingPath = null
                    }
                    targets.forEach { RecordingStore.delete(it) }
                    confirmDeleteAll = false; confirmDeleteSelected = false; confirmDelete = null
                    selectedPaths = emptySet(); selectionMode = false; currentRecording = null; refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false; confirmDeleteSelected = false; confirmDelete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun RecordingRow(
    rec: Recording, playing: Boolean, selected: Boolean, selectionMode: Boolean,
    onSelect: () -> Unit, onOpen: () -> Unit, onPlay: () -> Unit, onShare: () -> Unit,
    onDelete: () -> Unit, onFavorite: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().clickable { if (selectionMode) onSelect() else onOpen() }) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onSelect() })
            } else {
                IconButton(onClick = onPlay) { Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = if (playing) "Stop" else "Play") }
            }
            Column(Modifier.weight(1f)) {
                Text(rec.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(buildString {
                    append(directionLabel(rec.direction)); append(" · "); append(dateFmt.format(Date(rec.startedAt)))
                    if (rec.durationMs > 0) append(" · Recording ${formatDuration(rec.durationMs)}")
                    if (rec.callConnectedAt != null && rec.callEndedAt != null && rec.callEndedAt >= rec.callConnectedAt) append(" · Call ~${formatDuration(rec.callEndedAt - rec.callConnectedAt)}")
                    append(" · "); append(formatSize(rec.sizeBytes))
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rec.phoneNumber != null && rec.contactName != null) Text(rec.phoneNumber, style = MaterialTheme.typography.bodySmall)
                if (rec.simLabel != null) Text(rec.simLabel, style = MaterialTheme.typography.bodySmall)
                if (rec.silent) Text("Silent recording — check audio source/device compatibility", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (rec.notes.isNotBlank()) Text(rec.notes, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) { Icon(if (rec.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = if (rec.favorite) "Remove favorite" else "Favorite") }
            if (!selectionMode) {
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share audio") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }
        }
    }
}

@Composable
private fun RecordingDetailsDialog(
    rec: Recording, playing: Boolean, speed: Float, onDismiss: () -> Unit,
    onSave: (String, Boolean, String?, String?, String?, String?, String?) -> Unit, onPlay: () -> Unit, onSpeed: () -> Unit,
    onBookmark: () -> Unit, onShareAudio: () -> Unit, onExportDetails: () -> Unit,
) {
    var notes by remember(rec.file.absolutePath) { mutableStateOf(rec.notes) }
    var favorite by remember(rec.file.absolutePath) { mutableStateOf(rec.favorite) }
    var contactName by remember(rec.file.absolutePath) { mutableStateOf(rec.contactName.orEmpty()) }
    var phoneNumber by remember(rec.file.absolutePath) { mutableStateOf(rec.phoneNumber.orEmpty()) }
    var countryCode by remember(rec.file.absolutePath) { mutableStateOf(rec.countryCode.orEmpty()) }
    var simLabel by remember(rec.file.absolutePath) { mutableStateOf(rec.simLabel.orEmpty()) }
    var carrier by remember(rec.file.absolutePath) { mutableStateOf(rec.carrier.orEmpty()) }
    val context = LocalContext.current
    val number = rec.phoneNumber ?: rec.peer?.takeIf { it.any(Char::isDigit) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(rec.displayName) },
        text = {
            Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                DetailLine("Call type", directionLabel(rec.direction))
                DetailLine("Phone number", number ?: "Unavailable")
                DetailLine("Country", rec.countryCode ?: "Unknown — no international prefix")
                DetailLine("SIM", rec.simLabel ?: "Unavailable from Android call event")
                DetailLine("SIM slot", rec.simSlot?.let { "SIM $it" } ?: "Unknown")
                DetailLine("Carrier", rec.carrier ?: "Unavailable")
                DetailLine("Date", dateFmt.format(Date(rec.startedAt)))
                DetailLine("Recording duration", if (rec.durationMs > 0) formatDuration(rec.durationMs) else "Unknown")
                DetailLine("Call connected (estimated)", rec.callConnectedAt?.let { dateFmt.format(Date(it)) } ?: "Unknown")
                DetailLine("Call ended (estimated)", rec.callEndedAt?.let { dateFmt.format(Date(it)) } ?: "Unknown")
                DetailLine("Call duration (estimated)", if (rec.callConnectedAt != null && rec.callEndedAt != null && rec.callEndedAt >= rec.callConnectedAt) formatDuration(rec.callEndedAt - rec.callConnectedAt) else "Unknown")
                DetailLine("Audio source", rec.sourceLabel ?: "Unknown")
                DetailLine("File", rec.file.name)
                DetailLine("Size", formatSize(rec.sizeBytes))
                DetailLine("Status", if (rec.silent) "No sound detected" else "Audio detected / not flagged silent")
                DetailLine("Bookmarks", if (rec.bookmarksMs.isEmpty()) "None" else rec.bookmarksMs.joinToString { formatDuration(it) })
                if (!rec.metadataAvailable) Text("This is an older recording. Some details were not saved at recording time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = contactName, onValueChange = { contactName = it }, label = { Text("Contact name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = countryCode, onValueChange = { countryCode = it }, label = { Text("Country / calling code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = simLabel, onValueChange = { simLabel = it }, label = { Text("SIM label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = carrier, onValueChange = { carrier = it }, label = { Text("Carrier (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(2000) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                    Text("Favorite")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onPlay) { Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null); Text(if (playing) "Stop" else "Play") }
                    TextButton(onClick = onSpeed) { Icon(Icons.Filled.Speed, contentDescription = null); Text("${speed}x") }
                    TextButton(onClick = onBookmark) { Icon(Icons.Filled.BookmarkAdd, contentDescription = null); Text("Bookmark") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onShareAudio) { Icon(Icons.Filled.Share, contentDescription = null); Text("Audio") }
                    TextButton(onClick = onExportDetails) { Icon(Icons.Filled.Info, contentDescription = null); Text("Export details") }
                    if (number != null) TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                                data = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
                            })
                        }.onFailure { Toast.makeText(context, "Unable to open phone dialer", Toast.LENGTH_SHORT).show() }
                    }) { Icon(Icons.Filled.Call, contentDescription = null); Text("Call back") }
                }
                if (number != null) TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", number))
                    Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.ContentCopy, contentDescription = null); Text("Copy number") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(notes, favorite, contactName, phoneNumber, countryCode, simLabel, carrier) }) { Text("Save details") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 94.dp))
        Text(value, modifier = Modifier.weight(1f))
    }
}

private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
private fun directionLabel(d: CallDirection): String = when (d) {
    CallDirection.INCOMING -> "Incoming call"
    CallDirection.OUTGOING -> "Outgoing call"
    CallDirection.VOIP -> "VoIP call"
    CallDirection.UNKNOWN -> "Call direction unknown"
}
private fun filterLabel(f: RecordingFilter) = when (f) {
    RecordingFilter.ALL -> "All recordings"; RecordingFilter.INCOMING -> "Incoming"; RecordingFilter.OUTGOING -> "Outgoing"
    RecordingFilter.VOIP -> "VoIP"; RecordingFilter.FAVORITES -> "Favorites"; RecordingFilter.SILENT -> "Silent"
}
private fun sortLabel(s: RecordingSort) = when (s) {
    RecordingSort.NEWEST -> "Newest first"; RecordingSort.OLDEST -> "Oldest first"; RecordingSort.LONGEST -> "Longest first"
    RecordingSort.LARGEST -> "Largest first"; RecordingSort.NAME -> "Caller name"
}
private fun formatDuration(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(Locale.ROOT, kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(Locale.ROOT, mb)
    return "%.2f GB".format(Locale.ROOT, mb / 1024.0)
}
