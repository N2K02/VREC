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
        assertTrue(settings.contains("SettingCard(\"ADB connection\")"))
        assertTrue(settings.contains("SettingCard(\"Enhanced SIM capture source\")"))
    }

    @Test fun homeHoldsSoleSetupButtonAndExpandableDirections() {
        val source = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val home = source.substringAfter("private fun HomeScreen(")
            .substringBefore("private fun RecordingMasterCard(")
        assertTrue(home.contains("Recording dashboard"))
        assertTrue(source.contains("com.vrec.app.ui.LocalAdbHomeCard("))
        assertFalse(home.contains("onOpenExperimental"))
        assertTrue(source.contains("Screen.HOME -> HomeScreen()"))
        val card = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(card.contains("LocalAdbMiddleware.setup(context, userInitiated = true)"))
        assertFalse(card.contains("LocalWirelessAdb.start(context)"))
        assertTrue(card.contains("ExperimentalRecording.tcpPortListening()"))
        assertTrue(card.contains("ExperimentalRecording.tcpPortListening()"))
        assertTrue(card.contains("WirelessAdbSetupCard()"))
        assertTrue(card.contains("instructionsExpanded = !instructionsExpanded"))
        assertTrue(card.contains("USB host instructions (when Wireless debugging is unavailable)"))
        assertTrue(card.contains("Text(\"USB debugging\""))
        assertTrue(card.contains("needsUsbBootstrap"))
        assertTrue(card.contains("First complete the external USB bootstrap steps"))
        assertFalse(card.contains("One-time USB setup"))
        assertTrue(card.contains("Wireless debugging"))
    }

    @Test fun verifiedUidAndHelperAreDistinctSignals() {
        val home = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertFalse(home.contains("AdbPrivilegedApiHelper.checkHealth(context)"))
        val quick = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(quick.contains("helperReady && helperState == AdbPrivilegedApiHelper.State.RUNNING"))
        assertTrue(quick.contains("adbState.shellUid == 2000"))
        assertTrue(quick.contains("AdbUidBadge(badge, needsUsbBootstrap)"))
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
        assertFalse(controller.contains("Settings.Global.ADB_ENABLED"))
        assertFalse(controller.contains("usbDebuggingFlag("))
        assertTrue(middleware.contains("Log.i(\"ADB setup: localhost TCP/5555 missing"))
        assertFalse(middleware.contains("ENABLE_USB_DEBUGGING"))
        val recovery = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertFalse(recovery.contains("usbDebuggingFlag("))
        assertTrue(controller.contains("awaitingManualRetry = true"))
        assertTrue(controller.contains("AdbPermissionBootstrap.grantEligible(context)"))
        // UID 2000 is verified (verifyShellUid) before the permission grants run.
        val connectBody = controller.substringAfter("suspend fun connect(context: Context")
        assertTrue(connectBody.indexOf("verifyShellUid()") < connectBody.indexOf("grantSafely(context)"))
        assertTrue(controller.contains("if (uid != 2000) throw AdbConnectionException(AdbFailure.PERMISSION_DENIED"))
    }
}
