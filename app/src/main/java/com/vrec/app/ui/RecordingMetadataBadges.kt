package com.vrec.app.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compact source and direction badges below the caller. Playback remains a
 * separate button: the app icon is informational, never a replacement for Play.
 */
@Composable
internal fun RecordingMetadataBadges(
    kind: RecordingListLabels.SourceKind,
    sourcePackage: String?,
    sourceLabel: String,
    directionLabel: String,
    fileSize: String,
    modifier: Modifier = Modifier,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sourceTint = when (kind) {
        RecordingListLabels.SourceKind.APP -> if (dark) Color(0xFFCEB5FF) else Color(0xFF6843A6)
        RecordingListLabels.SourceKind.PHONE -> if (dark) Color(0xFF9DCBFF) else Color(0xFF225E9F)
        RecordingListLabels.SourceKind.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            SourceAppBadge(kind, sourcePackage, sourceLabel, sourceTint, dark)
        }
        DirectionBadge(directionLabel, dark)
        Text(
            fileSize,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun SourceAppBadge(
    kind: RecordingListLabels.SourceKind,
    packageName: String?,
    label: String,
    tint: Color,
    dark: Boolean,
) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, context, packageName, kind) {
        value = if (kind == RecordingListLabels.SourceKind.APP && !packageName.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
            }
        } else null
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .widthIn(max = 180.dp)
            .background(tint.copy(alpha = if (dark) 0.21f else 0.11f), shape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (drawable != null) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                },
                update = { it.setImageDrawable(drawable) },
                modifier = Modifier.size(17.dp),
            )
        } else {
            Icon(
                imageVector = when (kind) {
                    RecordingListLabels.SourceKind.APP -> Icons.Filled.Apps
                    RecordingListLabels.SourceKind.PHONE -> Icons.Filled.Phone
                    RecordingListLabels.SourceKind.UNKNOWN -> Icons.Filled.Call
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DirectionBadge(direction: String, dark: Boolean) {
    val (title, tint, icon) = when (direction) {
        "Incoming" -> Triple("Incoming", if (dark) Color(0xFF97D8A4) else Color(0xFF237642),
            Icons.Filled.CallReceived)
        "Outgoing" -> Triple("Outgoing", if (dark) Color(0xFF92C7FF) else Color(0xFF1767A7),
            Icons.Filled.CallMade)
        "VoIP" -> Triple("Direction ?", MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Call)
        else -> Triple("Direction ?", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Call)
    }
    Row(
        modifier = Modifier
            .background(tint.copy(alpha = if (dark) 0.21f else 0.11f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(title, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
