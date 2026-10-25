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

    @Test fun homeUsesSimpleSetupOnlyInAdbMode() {
        val source = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val home = source.substringAfter("private fun HomeScreen(")
            .substringBefore("private fun RecordingMasterCard(")
        assertTrue(home.contains("ExperimentalRecording.method(context) == ExperimentalRecording.Method.BUILT_IN_ADB"))
        assertTrue(home.contains("if (adbSelected) {"))
        assertTrue(home.contains("LocalAdbHomeCard(onOpenDetailedSetup = onOpenExperimental)"))
        assertTrue(source.contains("onOpenExperimental = {"))
        assertTrue(source.contains("settingsSection = \"Experimental\""))
    }

    @Test fun independentHealthAndUsbWirelessGuidance() {
        val home = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(home.contains("AdbPrivilegedApiHelper.checkHealth(context)"))
        val quick = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(quick.contains("helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING"))
        assertTrue(quick.contains("adbState.shellUid == 2000"))
        assertTrue(quick.contains("Text(\"USB debugging\""))
        assertTrue(quick.contains("Text(\"Wireless debugging\""))
        assertTrue(quick.contains("LocalAdbMiddleware.setup(context)"))
        val experimental = read("app/src/main/java/com/vrec/app/ui/ExperimentalRecorderCard.kt")
        assertTrue(experimental.contains("Text(\"ADB connection\""))
        assertTrue(experimental.contains("Enable TCP"))
        assertTrue(experimental.contains("One-time USB setup"))
    }

    @Test fun masterSwitchAttemptsLocalSetupBeforeArmingShell() {
        val monitor = read("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(monitor.contains("LocalAdbMiddleware.setup(appContext)"))
        assertTrue(monitor.indexOf("LocalAdbMiddleware.setup(appContext)") <
            monitor.indexOf("applied = ExperimentalCaptureDaemon.setMode("))
    }

}
