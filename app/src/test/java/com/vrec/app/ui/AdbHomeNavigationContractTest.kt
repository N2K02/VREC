package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Prevent duplicate ADB setup UI and require verified helper health on ADB-mode Home. */
class AdbHomeNavigationContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun fullAdbChecklistAppearsOnlyInsideExperimentalSubmenu() {
        val settings = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        val overview = settings.substringAfter("if (section == null) {")
            .substringBefore("} else {\n        if (section == \"Recording\")")
        assertFalse("Settings overview must not duplicate the ADB setup card",
            overview.contains("AdbSetupProgressCard("))
        val experimental = settings.substringAfter("if (section == \"Experimental\") {")
            .substringBefore("if (section == \"VoIP\")")
        assertTrue(experimental.contains("AdbSetupProgressCard("))
        assertTrue(experimental.contains("ExperimentalRecorderCard()"))
        assertTrue(experimental.contains("TelecomAdbInstructionCard("))
        assertEquals(2, Regex("AdbSetupProgressCard\\(").findAll(settings).count())
    }

    @Test fun homeConnectsToTcpOnlyWhenAdbMethodSelected() {
        val source = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val home = source.substringAfter("private fun HomeScreen(")
            .substringBefore("private fun RecordingMasterCard(")
        assertTrue(home.contains("ExperimentalRecording.method(context) == ExperimentalRecording.Method.BUILT_IN_ADB"))
        assertTrue(home.contains("if (adbSelected) {"))
        assertTrue(home.contains("ExperimentalRecording.connect(context, 5555, wirelessOnly = false)"))
        assertTrue(home.contains("enabled = !adbConnection.busy && !verifiedTcp"))
        assertTrue(home.contains("settingsSection = \"Experimental\"").not()) // Navigation is owned by App.
        assertTrue(source.contains("onOpenExperimental = {"))
        assertTrue(source.contains("settingsSection = \"Experimental\""))
    }

    @Test fun helperHealthIsIndependentOfAdbAuthorization() {
        val home = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(home.contains("AdbPrivilegedApiHelper.checkHealth(context)"))
        assertTrue(home.contains("helperReady &&"))
        assertTrue(home.contains("helperState == AdbPrivilegedApiHelper.State.RUNNING"))
        assertTrue(home.contains("adbConnection.shellUid == 2000"))
        assertTrue(home.contains("TCP/5555 listening; ADB authorization not verified"))
        val experimental = read("app/src/main/java/com/vrec/app/ui/ExperimentalRecorderCard.kt")
        assertFalse(experimental.contains("Text(\"Enhanced recording helper\""))
        assertTrue(experimental.contains("Text(\"ADB connection\""))
        assertTrue(experimental.contains("Enable TCP"))
        assertTrue(experimental.contains("One-time USB setup"))
    }
}
