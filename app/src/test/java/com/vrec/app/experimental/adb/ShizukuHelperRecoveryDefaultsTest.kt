package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the ADB track: helper auto-start after reinstall, and detached launch. */
class ShizukuHelperRecoveryDefaultsTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun recoveryAllowedByDefaultButDisconnectStillRevokesIt() {
        val recording = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(recording.contains("allowed = prefs.getBoolean(AUTO_RECOVERY_KEY, true)"))
        assertFalse(recording.contains("getBoolean(AUTO_RECOVERY_KEY, false)"))
        val disconnect = recording.substringAfter("suspend fun disconnect(context: Context)")
        assertTrue(disconnect.contains(".putBoolean(AUTO_RECOVERY_KEY, false)"))
    }

    @Test fun shizukuRecoveryStillRequiresAuthorizationAndShellAccess() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("ExperimentalRecording.recoveryAuthorization(app).allowed &&"))
        assertTrue(recovery.contains("ShizukuHostAdapter.hasShellAccess()"))
    }

    @Test fun helperLaunchStaysTheProvenSetsidForm() {
        // Shell cannot leave adbd's cgroup on Android 17 (measured by JemRec).
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("setsid nohup app_process / "))
        assertFalse(daemon.contains("CGROUP_ESCAPE"))
    }

    @Test fun keepPairingOfferedOnShizukuSettings() {
        assertTrue(source("app/src/main/java/com/vrec/app/ui/ShizukuSettingsPage.kt").contains("KeepPairingCard()"))
    }
}
