package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore-after-restart reuses an approved pairing; Home dashboard controls stay safe. */
class AdbSelfRestoreContractTest {
    private fun read(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun secureSettingsIsGrantedOnlyThroughVerifiedShellAndChecked() {
        val boot = read("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
        val restore = read("app/src/main/java/com/vrec/app/experimental/adb/AdbSelfRecovery.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(boot.indexOf("shellUid != 2000") < boot.indexOf("grantSecureSettings(app)"))
        assertTrue(restore.contains("return hasSecureSettings(context)"))
    }

    @Test fun restoreNeedsPriorPairingWifiAndDoesNotTouchUsbDebugging() {
        val restore = read("app/src/main/java/com/vrec/app/experimental/adb/AdbSelfRecovery.kt")
        assertTrue(restore.contains("ExperimentalRecording.wirelessWasPaired(context)"))
        assertTrue(restore.contains("!onWifi(app)"))
        assertFalse(restore.contains("ADB_ENABLED"))
        assertFalse(restore.contains("\"adb_enabled\""))
        val recovery = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("ExperimentalRecording.awaitingManualRetry ||"))
        assertTrue(recovery.contains("AdbSelfRecovery.restoreWireless(app)"))
        assertTrue(recovery.contains("wirelessBackoff.allowed(now)"))
        assertTrue(recovery.contains("tcpBackoff.allowed(now)"))
    }

    @Test fun restoreVerifiesSettingAndHelperInsteadOfAssuming() {
        val restore = read("app/src/main/java/com/vrec/app/experimental/adb/AdbSelfRecovery.kt")
        assertTrue(restore.contains("RestoreOutcome.SETTING_REJECTED"))
        assertTrue(restore.indexOf("putInt(app.contentResolver") <
            restore.lastIndexOf("if (!wirelessDebuggingOn(app))"))
        assertTrue(restore.contains("ExperimentalCaptureDaemon.probe(app)"))
        assertTrue(restore.contains("LocalWirelessAdb.stop(app)"))
    }

    @Test fun authorizationTimeoutIsNeverRetriedAutomatically() {
        val recovery = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("if (lastFailureWasAuthTimeout || reason.contains(\"stopped\""))
        assertTrue(recovery.contains("awaitingManualRetry && !lastFailureWasAuthTimeout"))
    }

    @Test fun dashboardStopIsRefusedMidCallAndRestartChecksAdbFirst() {
        val daemon = read("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val dash = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(daemon.contains("\"BUSY\" -> QuitResult.BUSY"))
        assertTrue(dash.indexOf("ExperimentalRecording.connect(context)") <
            dash.indexOf("describe(AdbPrivilegedApiHelper.stop(context))\n                            ?:"))
        assertTrue(read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt").contains("AdbDashboard("))
    }
}
