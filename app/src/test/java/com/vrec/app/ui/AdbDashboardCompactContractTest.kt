package com.vrec.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression check for compact connection status without losing accessibility. */
class AdbDashboardCompactContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun statusBadgesWrapInsteadOfUsingFourFixedTiles() {
        val src = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(src.contains("FlowRow("))
        assertTrue(src.contains("horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)"))
        assertTrue(src.contains("Stat(\"Connection\""))
        assertTrue(src.contains("Stat(\"Recording\""))
        assertTrue(src.contains("Stat(\"Recovery\""))
        assertTrue(src.contains("Stat(\"Shell UID\""))
        assertFalse(src.contains("Modifier.weight(1f))\n                    Stat("))
    }

    @Test fun compactBadgesKeepLabelsValuesAndAccessibleDescription() {
        val src = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(src.contains("contentDescription = \"\$label: \$value\""))
        assertTrue(src.contains("\"\$label: \$value\""))
        assertTrue(src.contains("MaterialTheme.typography.labelSmall"))
        assertTrue(src.contains("verticalArrangement = Arrangement.spacedBy(6.dp)"))
    }

    @Test fun startStopRestartControlsRemainAvailableAndCallSafe() {
        val src = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(src.contains("Text(\"Start\")"))
        assertTrue(src.contains("Text(\"Stop\")"))
        assertTrue(src.contains("Text(\"Restart\")"))
        assertTrue(src.contains("!recordingActive"))
    }
}
