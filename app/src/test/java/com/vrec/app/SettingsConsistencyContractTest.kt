package com.vrec.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the mRadio-inspired Settings grouping while retaining app-specific pages. */
class SettingsConsistencyContractTest {
    @Test fun settingsRowsAndUpdatesFollowConsistentOrder() {
        val relative = "app/src/main/java/com/vrec/app/ui/SettingsScreen.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("if (section == null) {", "")
        assertTrue("Settings section missing", menu.isNotBlank())
        assertTrue("Settings group title is missing", menu.contains("Manage ADB recording"))
        assertTrue("Settings menu item is missing", menu.contains("Triple(\"Recording\", \"Automatic recording and silent files\""))
        assertTrue(!menu.contains("RecordingMethodOption("))
        assertTrue(!menu.contains("Permissions & setup"))
        assertTrue("ADB connection submenu missing", menu.contains(
            "Triple(\"ADB connection\", \"Automatic pairing, reboot recovery and network access\""))
        assertTrue("Updates footer missing", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        val divider = menu.indexOf("HorizontalDivider()")
        val update = menu.indexOf("headlineContent = { Text(\"About & updates\") }")
        assertTrue("Divider must separate settings and updates", divider >= 0 && update > divider)
        assertTrue("ADB connection belongs before the update footer",
            menu.indexOf("Triple(\"ADB connection\"") in 0 until divider)
        assertTrue("Redundant ADB diagnostics submenu must stay removed",
            !menu.contains("Triple(\"ADB diagnostics\""))
        assertTrue("ADB diagnostics page must not be duplicated in Settings",
            !source.contains("AdbDiagnosticsPage(shared)"))
        assertTrue("ADB preferences must be under their own submenu",
            source.contains("if (section == \"ADB connection\") {"))
        assertTrue("ADB Settings must use the shared core",
            source.contains("dev.n2k02.adb.core.AdbConnectionSettingsPage("))
        assertTrue("Old ADB setting cards must be removed",
            !source.contains("private fun AdbPairingRecoveryChoices()") &&
            !source.contains("private fun AdbSecurityChoices()"))
        assertTrue("No secondary setup wizard in Settings",
            !source.contains("AdbSetupProgressCard("))
    }
}
