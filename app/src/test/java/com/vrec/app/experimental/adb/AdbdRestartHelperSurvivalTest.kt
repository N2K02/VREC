package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: the ADB build restarted adbd (tcpip:5555) right after the
 * helper became ready, and adbd's restart cgroup-killed the helper.
 */
class AdbdRestartHelperSurvivalTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    private fun signals(
        quitting: Boolean = false, liveCall: Boolean = false,
        enhanced: Boolean = false, helperAlive: Boolean = false,
    ) = AdbdRestartGuard.Signals(quitting, liveCall, enhanced, helperAlive)

    @Test fun promotionOnlyWhenNoHelperCallOrCaptureIsAlive() {
        assertTrue(AdbdRestartGuard.mayPromoteToTcp(signals()))
        assertFalse(AdbdRestartGuard.mayPromoteToTcp(signals(helperAlive = true)))
        assertFalse(AdbdRestartGuard.mayPromoteToTcp(signals(liveCall = true)))
        assertFalse(AdbdRestartGuard.mayPromoteToTcp(signals(enhanced = true)))
        assertFalse(AdbdRestartGuard.mayPromoteToTcp(signals(quitting = true)))
    }

    @Test fun exhaustiveMatrixNeverPromotesUnderARunningHelper() {
        for (mask in 0 until 16) {
            val s = signals(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            assertEquals(mask == 0, AdbdRestartGuard.mayPromoteToTcp(s))
        }
    }

    @Test fun manualRetrySkipsOnlyAVerifiedSessionWithLiveHelper() {
        assertFalse(AdbdRestartGuard.mayRetryVerifiedSession(sessionVerified = true, helperAlive = true))
        assertTrue(AdbdRestartGuard.mayRetryVerifiedSession(sessionVerified = true, helperAlive = false))
        assertTrue(AdbdRestartGuard.mayRetryVerifiedSession(sessionVerified = false, helperAlive = true))
        assertTrue(AdbdRestartGuard.mayRetryVerifiedSession(sessionVerified = false, helperAlive = false))
    }

    @Test fun promotionIsGuardedSerializedAndReprobesTheLiveHelper() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val promote = wireless.substringAfter("fun promoteVerifiedShellToTcp(")
            .substringBefore("fun openSettings(context: Context)")
        assertTrue(promote.contains("AdbdRestartGuard.mayPromoteToTcp("))
        assertTrue(promote.contains("AdbLifecycle.coordinateSetup {"))
        assertTrue(promote.contains("ExperimentalCaptureDaemon.probe(app)"))
        assertTrue(promote.indexOf("ExperimentalCaptureDaemon.probe(app)") <
            promote.indexOf("retry(auto = true)"))
        val start = wireless.substringAfter("fun start(context: Context")
            .substringBefore("fun pair(")
        assertTrue(start.contains("AdbdRestartGuard.mayRetryVerifiedSession("))
    }

    @Test fun helperLaunchLeavesAdbdCgroupBeforeExec() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("setsid nohup sh -c '\" + CGROUP_ESCAPE"))
        assertTrue(daemon.contains("\"exec app_process / \" + DAEMON_CLASS"))
        assertFalse(daemon.contains("setsid nohup app_process"))
        val escape = daemon.substringAfter("internal const val CGROUP_ESCAPE =")
            .substringBefore("private ")
        for (root in listOf("/sys/fs/cgroup", "/acct", "/dev/cg2_bpf")) assertTrue(escape.contains(root))
        assertTrue(escape.contains("cgroup.procs"))
        assertTrue(escape.contains("VREC_CGROUP="))
        // Embedded in a single-quoted sh -c script.
        assertFalse(escape.contains("'"))
    }

    @Test fun recoveryAllowedByDefaultButDisconnectStillRevokesIt() {
        val recording = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(recording.contains("allowed = prefs.getBoolean(AUTO_RECOVERY_KEY, true)"))
        val disconnect = recording.substringAfter("suspend fun disconnect(context: Context)")
            .substringBefore("suspend fun disableTcp(")
        assertTrue(disconnect.contains(".putBoolean(AUTO_RECOVERY_KEY, false)"))
    }
}
