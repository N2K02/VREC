package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-time ADB setup action lives on Home, not in a diagnostics wizard. */
class AdbHomeNavigationContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun settingsContainsDiagnosticsNotDuplicateAuthorization() {
        val settings = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertFalse(settings.contains("AdbSetupProgressCard("))
        assertFalse(settings.contains("ExperimentalRecorderCard()"))
        assertFalse(settings.contains("TelecomAdbInstructionCard("))
        assertTrue(settings.contains("SettingCard(\"ADB status\")"))
        assertTrue(settings.contains("SettingCard(\"Enhanced SIM capture source\")"))
    }

    @Test fun homeHoldsSoleSetupButtonAndExpandableDirections() {
        val source = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val home = source.substringAfter("private fun HomeScreen(")
            .substringBefore("private fun RecordingMasterCard(")
        assertTrue(home.contains("Recording dashboard"))
        assertTrue(home.contains("LocalAdbHomeCard()"))
        assertFalse(home.contains("onOpenExperimental"))
        assertTrue(source.contains("Screen.HOME -> HomeScreen()"))
        val card = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(card.contains("LocalAdbMiddleware.setup(context, userInitiated = true)"))
        assertTrue(card.contains("instructionsExpanded = !instructionsExpanded"))
        assertTrue(card.contains("First-time USB instructions"))
        assertTrue(card.contains("Text(\"USB debugging\""))
        assertFalse(card.contains("One-time USB setup"))
        assertFalse(card.contains("Wireless debugging"))
    }

    @Test fun verifiedUidAndHelperAreDistinctSignals() {
        val home = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(home.contains("AdbPrivilegedApiHelper.checkHealth(context)"))
        val quick = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(quick.contains("helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING"))
        assertTrue(quick.contains("adbState.shellUid == 2000"))
        assertTrue(quick.contains("AdbUidBadge(badge)"))
        assertTrue(quick.contains("Vrec call recording helper"))
    }

    @Test fun monitoringCannotReopenManualRetryPrompt() {
        val monitor = read("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(monitor.contains("LocalAdbMiddleware.setup(appContext)"))
        val middleware = read("app/src/main/java/com/vrec/app/experimental/adb/LocalAdbMiddleware.kt")
        assertTrue(middleware.contains("userInitiated: Boolean = false"))
        assertTrue(middleware.contains("ExperimentalRecording.awaitingManualRetry"))
        assertTrue(middleware.contains("ExperimentalRecording.connect(app, automatic = !userInitiated)"))
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(controller.contains("withTimeout(15_000L)"))
        assertTrue(controller.contains("awaitingManualRetry = true"))
        assertTrue(controller.contains("AdbPermissionBootstrap.grantEligible(context)"))
        assertTrue(controller.indexOf("check(uid == 2000)") <
            controller.indexOf("AdbPermissionBootstrap.grantEligible(context)"))
    }
}
