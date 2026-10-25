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
        assertTrue("Settings group title is missing", menu.contains("Make recording your own"))
        assertTrue("Settings menu item is missing", menu.contains("headlineContent = { Text(\"Recording\") }"))
        assertTrue("Updates or diagnostic footer missing", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        val divider = menu.indexOf("HorizontalDivider()")
        val update = menu.indexOf("headlineContent = { Text(\"About & updates\") }")
        assertTrue("Divider must separate settings and diagnostic/update links", divider >= 0 && update > divider)
    }
}
