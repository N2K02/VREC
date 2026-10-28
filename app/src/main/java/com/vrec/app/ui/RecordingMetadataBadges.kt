package com.vrec.app.ui

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Two compact informational chips, independent of recording playback:
 * the originating app/phone source and the observed call direction.
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
    val accent = RecordingListLabels.sourceAccent(kind, sourcePackage)
    val sourceTint = when (accent) {
        RecordingListLabels.SourceAccent.GREEN ->
            if (dark) Color(0xFF91E3AF) else Color(0xFF247344)
        RecordingListLabels.SourceAccent.BLUE ->
            if (dark) Color(0xFFA3D0FF) else Color(0xFF22649F)
        RecordingListLabels.SourceAccent.VIOLET ->
            if (dark) Color(0xFFD3BDF6) else Color(0xFF7351AA)
        RecordingListLabels.SourceAccent.NEUTRAL ->
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            SourceAppBadge(kind, sourcePackage, sourceLabel, sourceTint, dark)
        }
        DirectionBadge(directionLabel, dark)
        Text(
            fileSize,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Subtle tinted surface + fine outline avoids bulky solid-color pills. */
private fun Modifier.metadataPill(tint: Color, dark: Boolean): Modifier {
    val shape = RoundedCornerShape(50)
    return background(tint.copy(alpha = if (dark) 0.15f else 0.09f), shape)
        .border(0.75.dp, tint.copy(alpha = if (dark) 0.32f else 0.22f), shape)
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
    Row(
        modifier = Modifier
            .widthIn(max = 166.dp)
            .metadataPill(tint, dark)
            .padding(horizontal = 9.dp, vertical = 5.dp),
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
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DirectionBadge(direction: String, dark: Boolean) {
    val (title, tint, icon) = when (direction) {
        "Incoming" -> Triple(
            "Incoming",
            if (dark) Color(0xFF91E3AF) else Color(0xFF247344),
            Icons.Filled.CallReceived,
        )
        "Outgoing" -> Triple(
            "Outgoing",
            if (dark) Color(0xFFA3D0FF) else Color(0xFF22649F),
            Icons.Filled.CallMade,
        )
        else -> Triple(
            "Direction ?",
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.Call,
        )
    }
    Row(
        modifier = Modifier
            .metadataPill(tint, dark)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
        )
    }
}
