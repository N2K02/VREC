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
        assertTrue("No duplicate ADB diagnostics submenu", !menu.contains("ADB diagnostics"))
        assertTrue("ADB preferences must be under their own submenu",
            source.contains("if (section == \"ADB connection\") {"))
        assertTrue("Automatic pairing toggle must be in ADB connection preferences",
            source.contains("AdbPairingRecoveryChoices()"))
        assertTrue("No secondary setup wizard in Settings",
            !source.contains("AdbSetupProgressCard("))
    }
}
