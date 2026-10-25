package com.vrec.app.ui

import android.content.Intent
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import androidx.compose.material.icons.filled.Edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.style.TextAlign
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vrec.app.calls.CallHistoryResolver
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.Recording
import com.vrec.app.recording.RecordingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class RecordingFilter { ALL, INCOMING, OUTGOING, VOIP, FAVORITES, SILENT }
private enum class RecordingSort { NEWEST, OLDEST, LONGEST, LARGEST, NAME }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RecordingsList() {
    val context = LocalContext.current
    val tick = rememberOnResumeTick()
    var refresh by remember { mutableStateOf(0) }
    var contactsPermissionTick by remember { mutableStateOf(0) }
    val contactsAllowed = remember(tick, contactsPermissionTick) {
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
    val callLogAllowed = remember(tick, contactsPermissionTick) {
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        contactsPermissionTick++
        refresh++
    }
    val recordings = remember(tick, refresh) { RecordingStore.list(context) }
    // Revisit existing SIM files once the user's call-log grant is available.
    // The Android call log may be populated after an audio recording is finalized.
    LaunchedEffect(tick, refresh, callLogAllowed, contactsAllowed) {
        if (callLogAllowed) {
            val candidates = recordings.filter {
                (it.direction == CallDirection.INCOMING || it.direction == CallDirection.OUTGOING) &&
                    (it.phoneNumber.isNullOrBlank() || it.contactName.isNullOrBlank())
            }
            val changed = withContext(Dispatchers.IO) {
                var updates = 0
                candidates.forEach { rec ->
                    val identity = CallHistoryResolver.recover(context.applicationContext, rec)
                        ?: return@forEach
                    val previousNumber = rec.phoneNumber
                    // Never silently replace an explicitly captured or manually edited number.
                    if (!previousNumber.isNullOrBlank() &&
                        previousNumber.filter(Char::isDigit) != identity.number.orEmpty().filter(Char::isDigit)) {
                        return@forEach
                    }
                    val resolvedName = rec.contactName?.takeIf { it.isNotBlank() }
                        ?: identity.name?.takeIf { it.isNotBlank() }
                    val resolvedNumber = previousNumber?.takeIf { it.isNotBlank() }
                        ?: identity.number?.takeIf { it.isNotBlank() }
                    if (resolvedNumber != rec.phoneNumber || resolvedName != rec.contactName) {
                        RecordingStore.updateDetails(
                            rec, rec.notes, rec.favorite,
                            contactName = resolvedName,
                            phoneNumber = resolvedNumber,
                        )
                        updates++
                    }
                }
                updates
            }
            if (changed > 0) refresh++
        }
    }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var playbackPaused by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var playbackPosition by remember { mutableStateOf(0L) }
    var playbackDuration by remember { mutableStateOf(0L) }
    val player = remember { MediaPlayer() }
    var currentRecording by remember { mutableStateOf<Recording?>(null) }
    var confirmDelete by remember { mutableStateOf<Recording?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(RecordingFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(RecordingSort.NEWEST) }
    var sortSheet by remember { mutableStateOf(false) }
    var actionsRecording by remember { mutableStateOf<Recording?>(null) }
    var contactPickerRecording by remember { mutableStateOf<Recording?>(null) }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = contactPickerRecording
        contactPickerRecording = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null && uri != null) {
            val selection = runCatching {
                context.contentResolver.query(uri, arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
                }
            }.getOrNull()
            if (selection != null) {
                val (name, number) = selection
                if (!name.isNullOrBlank() || !number.isNullOrBlank()) {
                    RecordingStore.updateDetails(
                        target, target.notes, target.favorite,
                        contactName = name?.takeIf { it.isNotBlank() },
                        phoneNumber = number?.takeIf { it.isNotBlank() },
                    )
                    refresh++
                    Toast.makeText(context, "Recording contact updated", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Could not read that contact", Toast.LENGTH_SHORT).show()
            }
        }
    }
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

    LaunchedEffect(playingPath) {
        while (playingPath != null) {
            playbackPosition = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            playbackDuration = runCatching { player.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
            delay(250L)
        }
    }

    DisposableEffect(Unit) {
        player.setOnCompletionListener {
            playingPath = null
            playbackPaused = false
            playbackPosition = 0L
        }
        player.setOnErrorListener { _, _, _ ->
            playingPath = null
            playbackPaused = false
            playbackError = "Unable to play the recording. Check the file or format."
            true
        }
        onDispose { runCatching { player.release() } }
    }

    fun stopPlayback() {
        runCatching { player.reset() }
        playingPath = null
        playbackPaused = false
        playbackPosition = 0L
        playbackDuration = 0L
    }

    // Playback controls live only in the active recording card. If the recording
    // disappears from the filtered library, stop playback rather than leave audio
    // running with no visible controls.
    LaunchedEffect(playingPath, search, filter, refresh, tick) {
        val path = playingPath
        if (path != null && visibleRecordings.none { it.file.absolutePath == path }) {
            stopPlayback()
        }
    }

    /** Play / pause / resume, following ShizuCallRecorder's single active-player behaviour. */
    fun togglePlay(rec: Recording) {
        playbackError = null
        if (playingPath == rec.file.absolutePath) {
            runCatching {
                if (playbackPaused) player.start() else player.pause()
                playbackPaused = !playbackPaused
            }.onFailure {
                stopPlayback()
                playbackError = "Could not resume playback."
            }
        } else {
            runCatching {
                player.reset()
                player.setDataSource(rec.file.absolutePath)
                player.prepare()
                if (Build.VERSION.SDK_INT >= 23) player.playbackParams = player.playbackParams.setSpeed(speed)
                playbackDuration = player.duration.toLong().coerceAtLeast(0L)
                player.start()
                playingPath = rec.file.absolutePath
                playbackPaused = false
                playbackPosition = 0L
            }.onFailure {
                stopPlayback()
                playbackError = "Could not play this recording. The file may be unavailable or unsupported."
            }
        }
    }

    fun share(rec: Recording) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", rec.file)
        val intent = Intent(Intent.ACTION_SEND).setType(if (rec.file.extension.equals("ogg", true)) "audio/ogg" else "audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, "Share recording").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun shareDetails(rec: Recording) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "VREC recording details")
            .putExtra(Intent.EXTRA_TEXT, RecordingStore.exportDetails(rec))
        runCatching { context.startActivity(Intent.createChooser(intent, "Export recording details").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recording library", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Saved recordings", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text("${recordings.size} saved · ${formatSize(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = { refresh++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh recordings")
                }
            }
        }
        if ((!contactsAllowed || !callLogAllowed) && recordings.any {
                it.direction == CallDirection.INCOMING || it.direction == CallDirection.OUTGOING
            }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (!callLogAllowed)
                            "Call history access helps recover missing SIM caller numbers."
                        else
                            "Allow Contacts to show saved names for recovered numbers.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        contactsLauncher.launch(
                            if (!callLogAllowed) Manifest.permission.READ_CALL_LOG
                            else Manifest.permission.READ_CONTACTS,
                        )
                    }) {
                        Text("Allow")
                    }
                }
            }
        }
        playbackError?.let { message ->
            Card(shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { playbackError = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss playback error")
                    }
                }
            }
        }

    if (recordings.isNotEmpty()) {
        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(24.dp),
            label = { Text("Search caller, number, note…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            })
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecordingFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(filterLabel(option)) },
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { sortSheet = true }, shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Filled.Sort, contentDescription = null)
                Text("  ${sortLabel(sort)}")
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (!selectionMode) stopPlayback()
                selectionMode = !selectionMode
                selectedPaths = emptySet()
            }) { Text(if (selectionMode) "Done" else "Select") }
        }
        if (selectionMode) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    selectedPaths = if (selectedPaths.size == visibleRecordings.size) emptySet()
                        else visibleRecordings.map { it.file.absolutePath }.toSet()
                }) { Text(if (selectedPaths.size == visibleRecordings.size) "Deselect all" else "Select all") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { if (selectedPaths.isNotEmpty()) confirmDeleteSelected = true },
                    enabled = selectedPaths.isNotEmpty()) {
                    Text("Delete selected (${selectedPaths.size})", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = { confirmDeleteAll = true }) {
                Text("Delete all recordings", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    when {
        recordings.isEmpty() -> {
            // Keep the count and Refresh at the top, but center the empty state
            // within the remaining available height rather than under the header.
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Card(shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Text("No recordings yet", style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center)
                        Text("New recordings will appear here.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        visibleRecordings.isEmpty() -> Text("No recordings match this search or filter.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> LazyColumn(modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleRecordings, key = { it.file.absolutePath }) { rec ->
                val active = playingPath == rec.file.absolutePath
                RecordingRow(rec = rec, active = active, playing = active && !playbackPaused,
                    progress = if (active && playbackDuration > 0L) (playbackPosition.toFloat() / playbackDuration).coerceIn(0f, 1f) else 0f,
                    positionMs = if (active) playbackPosition else 0L,
                    durationMs = if (active && playbackDuration > 0L) playbackDuration else rec.durationMs,
                    selected = rec.file.absolutePath in selectedPaths, selectionMode = selectionMode,
                    onSelect = {
                        selectedPaths = if (rec.file.absolutePath in selectedPaths) selectedPaths - rec.file.absolutePath
                            else selectedPaths + rec.file.absolutePath
                    }, onOpen = { currentRecording = rec }, onPlay = { togglePlay(rec) },
                    onSeek = { value -> if (active) runCatching {
                        player.seekTo((value * playbackDuration).toInt().coerceAtLeast(0))
                        playbackPosition = (value * playbackDuration).toLong()
                    } },
                    onStop = { if (active) stopPlayback() },
                    onShare = { share(rec) }, onActions = { actionsRecording = rec })
            }
        }
    }

    if (sortSheet) {
        ModalBottomSheet(onDismissRequest = { sortSheet = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Sort recordings", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                RecordingSort.entries.forEach { item ->
                    ListItem(headlineContent = { Text(sortLabel(item)) },
                        trailingContent = { RadioButton(selected = sort == item, onClick = null) },
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                            sort = item; sortSheet = false
                        })
                }
            }
        }
    }
    actionsRecording?.let { rec ->
        ModalBottomSheet(onDismissRequest = { actionsRecording = null },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Recording actions", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text(RecordingListLabels.contact(
                    rec.contactName, null, rec.peer, rec.phoneNumber, rec.file.name,
                ), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ListItem(headlineContent = { Text("Choose contact") },
                    supportingContent = { Text("Assign a saved name and number to this recording") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        actionsRecording = null
                        contactPickerRecording = rec
                        runCatching {
                            contactPicker.launch(Intent(Intent.ACTION_PICK).apply {
                                type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                            })
                        }.onFailure {
                            contactPickerRecording = null
                            Toast.makeText(context, "Contacts picker unavailable", Toast.LENGTH_SHORT).show()
                        }
                    })
                ListItem(headlineContent = { Text("Recording details") },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        actionsRecording = null; currentRecording = rec
                    })
                ListItem(headlineContent = { Text(if (rec.favorite) "Remove favorite" else "Add to favorites") },
                    leadingContent = { Icon(if (rec.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        RecordingStore.updateDetails(rec, rec.notes, !rec.favorite)
                        actionsRecording = null; refresh++
                    })
                ListItem(headlineContent = { Text("Share audio") },
                    leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        actionsRecording = null; share(rec)
                    })
                ListItem(headlineContent = { Text("Delete recording") },
                    leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable {
                        actionsRecording = null; confirmDelete = rec
                    })
            }
        }
    }

    currentRecording?.let { rec ->
        RecordingDetailsDialog(rec = recordings.firstOrNull { it.file == rec.file } ?: rec,
            playing = playingPath == rec.file.absolutePath && !playbackPaused,
            active = playingPath == rec.file.absolutePath, speed = speed,
            positionMs = if (playingPath == rec.file.absolutePath) playbackPosition else 0L,
            playbackDurationMs = if (playingPath == rec.file.absolutePath) playbackDuration else rec.durationMs,
            onSeek = { ms -> if (playingPath == rec.file.absolutePath) runCatching { player.seekTo(ms.toInt()); playbackPosition = ms } },
            onStop = { stopPlayback() },
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
                        stopPlayback()
                    }
                    targets.forEach { RecordingStore.delete(it) }
                    confirmDeleteAll = false; confirmDeleteSelected = false; confirmDelete = null
                    selectedPaths = emptySet(); selectionMode = false; currentRecording = null; refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false; confirmDeleteSelected = false; confirmDelete = null }) { Text("Cancel") } })
    }
    } // end screen layout
}

@Composable
private fun RecordingRow(
    rec: Recording, active: Boolean, playing: Boolean, progress: Float, positionMs: Long,
    durationMs: Long, selected: Boolean, selectionMode: Boolean,
    onSelect: () -> Unit, onOpen: () -> Unit, onPlay: () -> Unit,
    onSeek: (Float) -> Unit, onStop: () -> Unit, onShare: () -> Unit, onActions: () -> Unit,
) {
    val context = LocalContext.current
    val callApp = remember(rec.sourcePackage) {
        resolveCallAppName(context, rec.sourcePackage)
    }
    // Older recordings may have a phone number but no saved contact name.
    // Resolve in the background so scrolling never blocks on ContactsProvider.
    val contactFromPhone by produceState<String?>(
        initialValue = null, rec.phoneNumber, rec.contactName,
    ) {
        val number = rec.phoneNumber
        if (rec.contactName.isNullOrBlank() && !number.isNullOrBlank()) {
            value = withContext(Dispatchers.IO) {
                com.vrec.app.calls.CallInfo.describe(context.applicationContext, number)
            }
        }
    }
    val displayedContact = RecordingListLabels.contact(
        rec.contactName, contactFromPhone, rec.peer, rec.phoneNumber, rec.file.name,
    )
    val callDirection = RecordingListLabels.direction(rec.direction, rec.callFlowDirection)
    val callSource = RecordingListLabels.source(
        rec.direction, callApp, rec.simSlot, rec.simLabel, rec.carrier,
    )

    Card(shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor =
            if (active) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectionMode) {
                    androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onSelect() })
                } else {
                    FilledTonalIconButton(onClick = onPlay) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Pause recording"
                                else if (active) "Resume recording" else "Play recording")
                    }
                }
                Column(Modifier.weight(1f).clickable { if (selectionMode) onSelect() else onOpen() },
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(dateFmt.format(Date(rec.startedAt)), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayedContact, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("$callDirection · $callSource",
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatSize(rec.sizeBytes),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!selectionMode) {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Filled.Share, contentDescription = "Share recording")
                    }
                    IconButton(onClick = onActions) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Recording actions")
                    }
                }
            }
            if (active && !selectionMode) {
                // One in-card player: the row's left button handles Play/Pause/Resume,
                // while the expanded controls handle seeking and stopping.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Slider(
                        value = progress,
                        onValueChange = onSeek,
                        modifier = Modifier.weight(1f),
                        enabled = durationMs > 0L,
                    )
                    IconButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop playback")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        formatDuration(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        formatDuration(durationMs.coerceAtLeast(positionMs)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (rec.silent) Text("Silent audio — check capture compatibility",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (rec.favorite) Text("★ Favorite", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
            if (rec.notes.isNotBlank()) Text(rec.notes, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDetailsDialog(
    rec: Recording, playing: Boolean, active: Boolean, speed: Float, positionMs: Long, playbackDurationMs: Long,
    onSeek: (Long) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit,
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
    var editing by remember(rec.file.absolutePath) { mutableStateOf(false) }
    var moreOpen by remember(rec.file.absolutePath) { mutableStateOf(false) }
    var measuredDurationMs by remember(rec.file.absolutePath) { mutableStateOf(0L) }
    val context = LocalContext.current
    val callApp = remember(rec.sourcePackage) {
        resolveCallAppName(context, rec.sourcePackage)
    }
    val number = rec.phoneNumber?.takeIf { it.isNotBlank() }
        ?: rec.peer?.takeIf { com.vrec.app.calls.CallMetadataResolver.directNumber(it) != null }
    val totalDurationMs = when {
        active && playbackDurationMs > 0L -> playbackDurationMs
        rec.durationMs > 0L -> rec.durationMs
        else -> measuredDurationMs
    }
    val callType = rec.callType?.takeUnless { it.equals("UNKNOWN", true) }
        ?.let { when (it.uppercase(Locale.ROOT)) {
            "INCOMING" -> "Incoming call"
            "OUTGOING" -> "Outgoing call"
            "VOIP" -> "VoIP call"
            else -> it
        } } ?: rec.direction.takeIf { it != CallDirection.UNKNOWN }?.let(::directionLabel)

    // Older recordings may have no duration sidecar. Read the media duration
    // off the UI thread; never infer it from size or a timestamp.
    LaunchedEffect(rec.file.absolutePath, rec.file.lastModified(), rec.durationMs) {
        measuredDurationMs = 0L
        if (rec.durationMs <= 0L) measuredDurationMs = withContext(Dispatchers.IO) {
            runCatching {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(rec.file.absolutePath)
                    metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
                } finally {
                    metadata.release()
                }
            }.getOrDefault(0L)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rec.displayName, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(callType ?: "Recording details", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Slider(
                        value = if (totalDurationMs > 0L)
                            (positionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                            else 0f,
                        onValueChange = { onSeek((it * totalDurationMs).toLong()) },
                        enabled = active && totalDurationMs > 0L,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (totalDurationMs > 0L) formatDuration(positionMs) else "-",
                            style = MaterialTheme.typography.labelMedium)
                        Text(if (totalDurationMs > 0L) formatDuration(totalDurationMs) else "-",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    // Six aligned icons; overflow holds secondary actions on small phones.
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RecordingDetailAction(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause" else "Play", onClick = onPlay,
                            modifier = Modifier.weight(1f))
                        RecordingDetailAction(Icons.Filled.Stop, "Stop", onClick = onStop,
                            enabled = active, modifier = Modifier.weight(1f))
                        RecordingDetailAction(Icons.Filled.Speed, "${speed}x", onClick = onSpeed,
                            modifier = Modifier.weight(1f))
                        RecordingDetailAction(Icons.Filled.BookmarkAdd, "Mark", onClick = onBookmark,
                            enabled = active && totalDurationMs > 0L, modifier = Modifier.weight(1f))
                        RecordingDetailAction(Icons.Filled.Share, "Share", onClick = onShareAudio,
                            modifier = Modifier.weight(1f))
                        Box(Modifier.weight(1f)) {
                            RecordingDetailAction(Icons.Filled.MoreVert, "More",
                                onClick = { moreOpen = true }, modifier = Modifier.fillMaxWidth())
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (editing) "Cancel editing" else "Edit details") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    onClick = { moreOpen = false; editing = !editing },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export details") },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = { moreOpen = false; onExportDetails() },
                                )
                                if (number != null) {
                                    DropdownMenuItem(
                                        text = { Text("Call back") },
                                        leadingIcon = { Icon(Icons.Filled.Call, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                                                    data = android.net.Uri.parse("tel:" + android.net.Uri.encode(number))
                                                })
                                            }.onFailure {
                                                Toast.makeText(context, "Unable to open phone dialer", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy number") },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            moreOpen = false
                                            val clipboard = context.getSystemService(
                                                android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", number))
                                            Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordingDetailSection("Call details") {
                    DetailLine("Call type", callType)
                    DetailLine("Phone number", number)
                    DetailLine("Contact", rec.contactName)
                    DetailLine("Country", rec.countryCode)
                    DetailLine("SIM", rec.simLabel)
                    DetailLine("SIM slot", rec.simSlot?.let { "SIM $it" })
                    DetailLine("Carrier", rec.carrier)
                    DetailLine("Call app", callApp)
                }
                RecordingDetailSection("Timing") {
                    DetailLine("Recorded", rec.startedAt.takeIf { it > 0 }?.let { dateFmt.format(Date(it)) })
                    DetailLine("Recording length", totalDurationMs.takeIf { it > 0 }?.let(::formatDuration))
                    DetailLine("Call connected¹", rec.callConnectedAt?.let { dateFmt.format(Date(it)) })
                    DetailLine("Call ended¹", rec.callEndedAt?.let { dateFmt.format(Date(it)) })
                    DetailLine("Call length¹",
                        if (rec.callConnectedAt != null && rec.callEndedAt != null &&
                            rec.callEndedAt >= rec.callConnectedAt)
                            formatDuration(rec.callEndedAt - rec.callConnectedAt) else null)
                }
                RecordingDetailSection("Audio & file") {
                    DetailLine("Recording type", rec.recordingType?.name)
                    DetailLine("Source", rec.sourceLabel)
                    DetailLine("Capture diagnostics", rec.audioHealth)
                    if (rec.audioHealthWarning) {
                        Text(
                            "Audio warning: one or both channels were quiet, or clipping was detected. " +
                                "Channel activity does not prove both participants were recorded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (rec.audioHealth != null) {
                        Text(
                            "PCM diagnostics describe sound levels only; verify both voices by listening.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DetailLine("Filename", rec.file.name)
                    DetailLine("Size", rec.sizeBytes.takeIf { it > 0 }?.let(::formatSize))
                    DetailLine("Audio", when {
                        !rec.metadataAvailable -> null
                        rec.silent -> "No sound detected"
                        else -> "Not flagged silent"
                    })
                    DetailLine("Bookmarks", rec.bookmarksMs.takeIf { it.isNotEmpty() }
                        ?.joinToString { formatDuration(it) })
                    if (rec.notes.isNotBlank() && !editing) DetailLine("Notes", rec.notes)
                }
                if (rec.callConnectedAt != null || rec.callEndedAt != null) {
                    Text("¹ Call times are estimates from Android events, not audio duration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!rec.metadataAvailable) {
                    Text("Legacy recording: details not saved originally are shown as '-'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (editing) {
                    RecordingDetailSection("Edit details") {
                        OutlinedTextField(value = contactName, onValueChange = { contactName = it },
                            label = { Text("Contact name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it },
                            label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = countryCode, onValueChange = { countryCode = it },
                            label = { Text("Country / calling code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = simLabel, onValueChange = { simLabel = it },
                            label = { Text("SIM label") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = carrier, onValueChange = { carrier = it },
                            label = { Text("Carrier") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = notes, onValueChange = { notes = it.take(2000) },
                            label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(),
                            minLines = 2, maxLines = 5)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = favorite,
                                onCheckedChange = { favorite = it })
                            Text("Favorite")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
                if (editing) Button(onClick = {
                    onSave(notes, favorite, contactName, phoneNumber, countryCode, simLabel, carrier)
                }) { Text("Save changes") }
            }
        }
    }
}

@Composable
private fun RecordingDetailAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp)) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(23.dp))
        }
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun RecordingDetailSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.43f))
        Text(value?.trim()?.takeUnless {
            it.isBlank() || it.equals("unknown", true) ||
                it.equals("unavailable", true) || it.equals("null", true)
        } ?: "-", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.57f))
    }
}

private fun resolveCallAppName(context: Context, packageName: String?): String? {
    val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "org.telegram.messenger" -> "Telegram"
        "org.thoughtcrime.securesms" -> "Signal"
        "com.facebook.orca" -> "Messenger"
        "com.viber.voip" -> "Viber"
        "com.skype.raider" -> "Skype"
        "com.google.android.apps.tachyon" -> "Google Meet"
        else -> pkg
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
