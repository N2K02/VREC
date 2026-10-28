package com.vrec.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Shared elevated/outlined panels for the Home dashboard. Material's default
 * low container merges into the nearly-black VRec background on dark devices.
 * Keep a visible but subdued panel edge in both themes.
 */
internal object HomePanelStyle {
    @Composable
    fun color(highlight: Boolean = false): Color {
        val scheme = MaterialTheme.colorScheme
        return if (scheme.surface.luminance() < 0.5f) {
            if (highlight) Color(0xFF303946) else Color(0xFF272C34)
        } else {
            if (highlight) scheme.secondaryContainer else scheme.surfaceContainerHigh
        }
    }

    @Composable
    fun border(highlight: Boolean = false): BorderStroke {
        val scheme = MaterialTheme.colorScheme
        val stroke = if (scheme.surface.luminance() < 0.5f) {
            Color(0xFF596372).copy(alpha = if (highlight) 0.94f else 0.64f)
        } else {
            scheme.outlineVariant.copy(alpha = if (highlight) 0.84f else 0.70f)
        }
        return BorderStroke(1.dp, stroke)
    }
}
