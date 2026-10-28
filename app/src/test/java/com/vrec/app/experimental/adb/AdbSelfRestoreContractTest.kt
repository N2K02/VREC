package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reboot restore is opt-in; verified TCP/5555 is automatic but can be disabled. */
class AdbSelfRestoreContractTest {
    private fun read(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
    private val restore = read("app/src/main/java/com/vrec/app/experimental/adb/AdbSelfRecovery.kt")
    private val boot = read("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
    private val choices = read("app/src/main/java/com/vrec/app/experimental/adb/AdbUserChoices.kt")
    private val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
    private val recovery = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")

    @Test fun rebootRestoreDefaultsOffButTcpDefaultsOn() {
        assertTrue(choices.contains("getBoolean(RESTORE_AFTER_RESTART, false)"))
        assertTrue(choices.contains("getBoolean(TCP_MODE, true)"))
    }

    @Test fun secureSettingsOnlyRequestedForOptedInRestoreAfterVerifiedUid() {
        assertTrue(boot.contains("AdbUserChoices.restoreAfterRestart(app)"))
        assertTrue(boot.indexOf("shellUid != 2000") < boot.indexOf("grantSecureSettings(app)"))
        assertTrue(restore.contains("if (!AdbUserChoices.restoreAfterRestart(context)) return false"))
        assertTrue(restore.contains("return hasSecureSettings(context).also"))
        assertTrue(restore.contains("pm revoke"))
        assertTrue(read("app/src/main/AndroidManifest.xml").contains("android.permission.WRITE_SECURE_SETTINGS"))
    }

    @Test fun restoreVerifiesSettingUidAndHelperAndNeverTouchesUsbDebugging() {
        assertTrue(restore.contains("RestoreDecision.decide(inputs(app))"))
        assertTrue(restore.contains("RestoreOutcome.SETTING_REJECTED"))
        assertTrue(restore.indexOf("putInt(app.contentResolver") <
            restore.lastIndexOf("if (!wirelessDebuggingOn(app))"))
        assertTrue(restore.contains("link.connected && link.shellUid == 2000 && ExperimentalCaptureDaemon.probe(app)"))
        assertTrue(restore.contains("AdbFailurePolicy.requiresManualRetry(link.lastFailure)) break"))
        assertTrue(restore.contains("LocalWirelessAdb.stop(app)"))
        assertFalse(restore.contains("ADB_ENABLED"))
        assertFalse(restore.contains("\"adb_enabled\""))
    }

    @Test fun tcpSwitchFollowsUserPreferenceAndReadyNeedsHelper() {
        val connect = wireless.substringAfter("private suspend fun connectAndSwitch(")
            .substringBefore("fun shutdown(")
        assertTrue(connect.contains("val switchToTcp = AdbUserChoices.tcpMode(app)"))
        assertTrue(connect.contains("deferHelperUntilTcp = switchToTcp"))
        assertTrue(connect.contains("if (switchToTcp) return switchToLocalTcp(app, automatic = automatic)"))
        assertTrue(connect.contains("ExperimentalCaptureDaemon.probe(app)"))
        assertTrue(connect.indexOf("connectWireless(") < connect.indexOf("switchToLocalTcp(app)"))
        assertTrue(wireless.contains("turn USB debugging off in Developer options"))
    }

    @Test fun recoveryUsesTypedFailuresAndNeverClearsTheManualRetryLatch() {
        assertFalse(recovery.contains("allowManualRetry"))
        assertFalse(recovery.contains("ignoreCase"))
        assertFalse(recovery.contains(".message"))
        assertTrue(recovery.contains("if (!AdbFailurePolicy.retryable(failure)) return failure"))
        assertTrue(recovery.contains("if (ExperimentalRecording.awaitingManualRetry) return Outcome.MANUAL_RETRY_REQUIRED"))
        // Disconnect and recording OFF stop automatic reconnection.
        assertTrue(recovery.contains("!Prefs.recordingEnabled(app) || !saved.allowed"))
        assertTrue(recovery.contains("if (AdbLifecycle.quitting) return Outcome.SKIPPED"))
        assertTrue(recovery.contains("wirelessBackoff.allowed(now)"))
        assertTrue(recovery.contains("tcpBackoff.allowed(now)"))
    }

    @Test fun dashboardStopIsRefusedMidCallAndRestartChecksAdbFirst() {
        val daemon = read("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val dash = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(daemon.contains("\"BUSY\" -> QuitResult.BUSY"))
        assertTrue(dash.indexOf("ExperimentalRecording.connect(context)") <
            dash.lastIndexOf("AdbPrivilegedApiHelper.stop(context)"))
        assertTrue(read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt").contains("AdbDashboard("))
    }
}
