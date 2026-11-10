package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Default-collapsed permissions retain live grant status and accessible details. */
class PermissionDashboardCollapsedContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun initiallyCollapsedHeaderRetainsLivePermissionCount() {
        val ui = read("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        assertTrue(ui.contains("var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }"))
        assertTrue(ui.contains("Modifier.fillMaxWidth().clickable { expanded = !expanded }"))
        assertTrue(ui.contains("val count = PermissionDashboardPolicy.grantedCount"))
        assertTrue(ui.contains("if (expanded) {"))
        assertTrue(ui.contains("Expand Android permissions"))
        assertTrue(ui.contains("Collapse Android permissions"))
    }

    @Test fun permissionsAndRepairControlsRemainInExpandedContent() {
        val ui = read("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        val details = ui.substringAfter("if (expanded) {")
        assertTrue(details.contains("LinearProgressIndicator("))
        assertTrue(details.contains("permissions.chunked(2)"))
        assertTrue(details.contains("tryGrant(label)"))
        assertTrue(details.contains("ADB shell helper"))
    }
}
