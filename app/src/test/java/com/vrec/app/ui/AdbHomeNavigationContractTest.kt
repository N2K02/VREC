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

    @Test fun settingsKeepsAdbPreferencesWithoutDuplicateDiagnostics() {
        val settings = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertFalse(settings.contains("AdbSetupProgressCard("))
        assertFalse(settings.contains("ExperimentalRecorderCard()"))
        assertFalse(settings.contains("TelecomAdbInstructionCard("))
        assertTrue(settings.contains("ShizukuSettingsPage()"))
        assertFalse(settings.contains("AdbDiagnosticsPage(shared)"))
        assertFalse(settings.contains("onSectionChange(\"Experimental\")"))
        assertFalse(settings.contains("SettingCard(\"Enhanced SIM capture source\")"))
    }

    @Test fun homeRoutesToOneShizukuAccessPageWithoutLegacySetupWizard() {
        val source = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val home = source.substringAfter("private fun HomeScreen(")
            .substringBefore("private fun RecordingMasterCard(")
        assertTrue(home.contains("Recording dashboard"))
        assertTrue(home.contains("ShizukuHomeCard(onManageAdb = onOpenAdb)"))
        assertFalse(home.contains("LocalAdbHomeCard("))
        assertTrue(source.contains("settingsSection = \"ADB\""))
        assertTrue(source.contains("Screen.HOME -> HomeScreen("))
        val card = read("app/src/main/java/com/vrec/app/ui/ShizukuHomeCard.kt")
        assertTrue(card.contains("Shizuku.pingBinder()"))
        assertTrue(card.contains("Shizuku.checkSelfPermission()"))
        assertTrue(card.contains("Shizuku.getUid()"))
        assertFalse(card.contains("WirelessAdbSetupCard()"))
        assertFalse(card.contains("adb tcpip 5555"))
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
        assertTrue(monitor.contains("AdbLifecycle.requestRecovery(appContext, \"Recording enabled\")"))
        assertFalse(monitor.contains("LocalAdbMiddleware.setup(appContext)"))
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
        val helper = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val flow = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperStartFlow.kt")
        assertTrue(helper.contains("AdbPermissionBootstrap.grantEligible(app)"))
        // Transport UID verification precedes the helper's independent live UID
        // check, which happens before any optional permission grants.
        val connectBody = controller.substringAfter("suspend fun connect(context: Context")
        assertTrue(connectBody.indexOf("verifyShellUid()") in
            0 until connectBody.indexOf("startHelperTyped(context, manualRepair = !automatic)"))
        assertTrue(flow.indexOf("steps.verifyUid2000()") in
            0 until flow.indexOf("steps.configurePermissions()"))
        assertTrue(controller.contains("if (uid != 2000) throw AdbConnectionException(AdbFailure.PERMISSION_DENIED"))
    }
}
