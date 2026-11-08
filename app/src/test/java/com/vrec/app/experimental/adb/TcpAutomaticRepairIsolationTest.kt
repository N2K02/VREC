package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: background wireless -> TCP/5555 previously used automatic=false
 * for the last reconnect, inadvertently granting manual-repair privileges.
 * The connection is already under the transport mutex, so reacquiring it
 * would deadlock. Both properties are checked here.
 */
class TcpAutomaticRepairIsolationTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun automaticDiscoveryRetainsAutomaticIntentThroughTcpReconnect() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val transport = source("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt")
        assertTrue(wireless.contains("SharedAdbHostAdapter.core(app)"))
        assertTrue(transport.contains("adb.retry(auto = true)"))
        assertTrue(transport.contains("AdbTransportMode.LOCAL_TCP_5555"))
        assertFalse(wireless.contains("reconnectTcp(context, automatic = false)"))
    }

    @Test fun opportunisticTcpPromotionUsesAutomaticNotManualRepair() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val promote = wireless.substringAfter("fun promoteVerifiedShellToTcp(")
            .substringBefore("fun openSettings(context: Context)")
        assertTrue(promote.contains("retry(auto = true)"))
        assertTrue(promote.contains("CallMonitor.hasLiveCall()"))
        assertFalse(promote.contains("retry(auto = false)"))
    }

    @Test fun explicitRetryRetainsManualIntentButStillSerializesTransport() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val retry = wireless.substringAfter("fun retry(context: Context)")
            .substringBefore("fun promoteVerifiedShellToTcp(")
        assertTrue(retry.contains("start(context, userInitiated = true)"))
        assertTrue(wireless.contains("if (!owning)"))
        assertTrue(wireless.contains("adb.acquireService()"))
    }

    @Test fun coordinatorDoesNotReacquireNonReentrantLockDuringTcpTransition() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        val within = recovery.substringAfter("internal suspend fun reconnectTcpWithinSetup(")
            .substringBefore("private suspend fun reconnectTcpUncoordinated(")
        assertTrue(within.contains("reconnectTcpUncoordinated(app, automatic)"))
        assertFalse(within.contains("AdbLifecycle.coordinateSetup {"))
        val outer = recovery.substringAfter("suspend fun reconnectTcp(app: Context, automatic: Boolean)")
            .substringBefore("internal suspend fun reconnectTcpWithinSetup(")
        assertTrue(outer.contains("if (automatic) AdbLifecycle.coordinateSetup {"))
    }

    @Test fun automaticTransportReconnectCannotTriggerManualHelperReplacement() {
        val connection = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val recover = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(connection.contains("startHelperTyped(context, manualRepair = !automatic)"))
        assertTrue(recover.contains("ExperimentalRecording.connect(app, automatic = automatic)"))
    }
}
