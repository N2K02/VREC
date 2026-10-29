package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-layer guards for ADB Start/Repair. JVM protocol simulations test
 * decisions; these contracts ensure Android and shell code still use them.
 * Hardware recording checks remain necessary before a stable release.
 */
class HelperRepairEndToEndContractTest {
    private fun code(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing source: $path")

    @Test fun bothVisibleRepairButtonsUseSingleApplicationOwnedCoordinator() {
        val home = code("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val permissions = code("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        val start = code("app/src/main/java/com/vrec/app/experimental/adb/AdbForegroundSetup.kt")
        val lifecycle = code("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(home.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(permissions.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(start.contains("starting.compareAndSet(false, true)"))
        assertTrue(start.contains("AdbLifecycle.coordinateSetup {"))
        assertTrue(start.contains("LocalAdbMiddleware.setup(app, userInitiated = true)"))
        assertTrue(lifecycle.contains("AdbForegroundSetup.shutdown()"))
        assertFalse(home.contains("LocalAdbMiddleware.setup(context, userInitiated = false)"))
    }

    @Test fun manualRepairFlagReachesExistingHelperConfigurationAndGuardedRestart() {
        val middleware = code("app/src/main/java/com/vrec/app/experimental/adb/LocalAdbMiddleware.kt")
        val link = code("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val manager = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val daemon = code("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(middleware.contains("startWithAdb(app, manualRepair = userInitiated)"))
        assertTrue(link.contains("startHelperTyped(context, manualRepair = !automatic)"))
        assertTrue(manager.contains("ensureStarted(app, manualRepair = manualRepair)"))
        assertTrue(daemon.contains("ShellHelperRepairPolicy.shouldRequestReplacement("))
        assertTrue(daemon.contains("check(exit == \"BYE\")"))
        assertTrue(daemon.contains("Existing shell helper is answering, but configuration could not be confirmed"))
    }

    @Test fun oldDaemonIsDetectedBeforeIssuingItsUnknownSimCommand() {
        val daemon = code("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val manager = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val shell = code("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(shell.contains("\"PONG \" + PROTOCOL_VERSION + \" \" + BUILD"))
        assertTrue(daemon.contains("ShellHelperBuildPolicy.decide(runningBanner, bundledBuild(context))"))
        assertTrue(daemon.indexOf("val buildDecision = ShellHelperBuildPolicy.decide") <
            daemon.indexOf("if (confirmSetup(context))"))
        assertTrue(manager.contains("runningBuildStatus(context.applicationContext)"))
        assertTrue(manager.contains("ShellHelperBuildPolicy.Decision.STALE"))
    }

    @Test fun shellRefusesReplacementDuringActiveCallOrAudioImport() {
        val shell = code("shellserver/src/com/vrec/app/shell/Main.java")
        val quit = shell.substringAfter("if (command == COMMAND_QUIT)")
            .substringBefore("if (command == COMMAND_RECORD)")
        assertTrue(quit.contains("activeRecording != null || callActive || fetches.get() > 0"))
        assertTrue(quit.contains("\"BUSY\\n\""))
        assertTrue(quit.contains("\"BYE\\n\""))
        assertTrue(quit.indexOf("if (busy)") < quit.indexOf("System.exit(0)"))
    }

    @Test fun permissionDenialsCannotPreventVerifiedHelperStartup() {
        val flow = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperStartFlow.kt")
        val grants = code("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
        val panel = code("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        assertTrue(flow.contains("steps.configurePermissions()"))
        assertTrue(flow.contains("steps.ensureHelperStarted()"))
        assertTrue(flow.contains("Permission setup incomplete"))
        assertTrue(grants.contains("catch (cancelled: CancellationException)"))
        assertTrue(grants.contains("Android permission setup incomplete"))
        assertTrue(grants.contains("restricted call-log access"))
        assertTrue(panel.contains("All listed Android permissions are granted."))
        assertTrue(panel.contains("helper is a separate service"))
        assertTrue(panel.contains("launcher.launch(runtime!!)"))
    }

    @Test fun authenticatedUidAndModeAndSourceMustBeVerifiedBeforeReady() {
        val manager = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val daemon = code("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(manager.contains("AdbTransport.exec(\"id -u\", timeoutMs = 3_000)"))
        assertTrue(daemon.contains("CaptureSetupProtocol.evaluate("))
        assertTrue(daemon.contains("ready.value = verified"))
        assertTrue(daemon.contains("CaptureSourceHandshake.request("))
        assertTrue(daemon.contains("onTransportError = { sourceTransportError = it.javaClass.simpleName }"))
        assertFalse(manager.contains("state.value = State.RUNNING\n        true"))
    }

    @Test fun recoveredHelperDismissesOnlyItsOwnRedWarning() {
        val manager = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val warnings = code("app/src/main/java/com/vrec/app/recording/RecordingFailureNotice.kt")
        val policy = code("app/src/main/java/com/vrec/app/recording/RecordingWarningPolicy.kt")
        assertTrue(manager.contains("RecordingFailureNotice.clearAfterHelperRecovered"))
        assertTrue(warnings.contains("RecordingWarningPolicy.clearAfterHelperRecovered(warning.value)"))
        assertTrue(policy.contains("currentWarning == HELPER_START_FAILURE"))
        assertTrue(policy.contains("currentWarning != null && !persistent"))
        assertFalse(warnings.contains("fun clearAfterHelperRecovered(context: Context) {\n        dismiss(context)"))
    }

    @Test fun consentedTcpModeAndRecoveryMustReverifyLiveShellUid() {
        val wireless = code("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val link = code("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val recovery = code("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(wireless.contains("AdbUserChoices.tcpMode("))
        assertTrue(wireless.contains("switchToLocalTcp("))
        assertTrue(link.contains("verifyShellUid()"))
        assertTrue(recovery.contains("reconnectTcp(app, automatic = true)"))
        assertTrue(recovery.contains("if (ExperimentalRecording.awaitingManualRetry)"))
    }

    @Test fun quitAndNavigationCannotLeaveCompetingBackgroundPairingJobs() {
        val lifecycle = code("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val foreground = code("app/src/main/java/com/vrec/app/experimental/adb/AdbForegroundSetup.kt")
        val home = code("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(lifecycle.contains("LocalWirelessAdb.shutdown(app)"))
        assertTrue(lifecycle.contains("AdbForegroundSetup.shutdown()"))
        assertTrue(foreground.contains("scope.coroutineContext.cancelChildren()"))
        assertFalse(home.contains("LaunchedEffect(Unit) {\n        LocalAdbMiddleware.setup("))
        assertTrue(home.contains("finishAndRemoveTask()"))
    }
}
