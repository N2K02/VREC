package com.vrec.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vrec.app.Log

/** AAYouTube-style activity viewer with optional capture and screen-sized pages. */
@Composable
fun LogScreen() {
    val context = LocalContext.current
    val lines by Log.lines.collectAsStateWithLifecycle()
    val enabled by Log.enabled.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var followNewest by rememberSaveable { mutableStateOf(true) }
    var pages by remember { mutableIntStateOf(1) }
    var current by remember { mutableIntStateOf(0) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Enable logging", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) "Recording today's redacted diagnostics."
                    else "Logging paused. Existing entries remain available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { Log.setEnabled(it) })
        }

        val lineStyle = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
        )
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
                val density = LocalDensity.current
                val rowDp = with(density) { 14.sp.toDp() }
                // Approximate character width of an 11sp monospace glyph.
                val charDp = with(density) { (11.sp * 0.6f).toDp() }
                val columns = ((maxWidth / charDp).toInt() - 1).coerceAtLeast(10)
                val rowsPerPage = (maxHeight / rowDp).toInt().coerceAtLeast(1)
                val rows = remember(lines, columns) { LogPagePolicy.wrapRows(lines, columns) }
                val total = LogPagePolicy.pageCount(rows.size, rowsPerPage)
                val shown = LogPagePolicy.selectedPage(page, followNewest, total)
                SideEffect {
                    pages = total
                    current = shown
                }

                Column(Modifier.fillMaxSize()) {
                    if (rows.isEmpty()) {
                        Text(
                            if (enabled) "Nothing yet. Activity appears here as events occur."
                            else "Enable logging to capture new activity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LogPagePolicy.pageRows(rows, rowsPerPage, shown).forEach { row ->
                            Text(row, style = lineStyle, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = {
                page = (current - 1).coerceAtLeast(0)
                followNewest = false
            }, enabled = current > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Text("Prev")
            }
            Text("${current + 1} / $pages", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = {
                page = (current + 1).coerceAtMost(pages - 1)
                followNewest = page == pages - 1
            }, enabled = current < pages - 1) {
                Text("Next")
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    copy(context, Log.snapshot())
                    Toast.makeText(context, "Activity log copied", Toast.LENGTH_SHORT).show()
                },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Text("Copy", modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    Log.clear()
                    page = 0
                    followNewest = true
                },
                enabled = lines.isNotEmpty(),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Text("Clear all", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("VRec activity log", text))
}
