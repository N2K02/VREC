package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One wireless transition at a time; stale discovery callbacks can't act. */
class WirelessTransitionContractTest {
    private val src = listOf(File("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt"),
        File("../app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt"))
        .first { it.isFile }.readText()

    @Test fun everyConnectGoesThroughTheSingleFlightLock() {
        // connectWireless is only called from connectAndSwitch...
        assertEquals(1, Regex("ExperimentalRecording\\.connectWireless\\(").findAll(src).count())
        // ...which runs under the transition lock from discovery, pairing and Retry.
        assertTrue(src.contains("if (!transition.tryLock()) return@launch"))
        assertTrue(src.contains("scope.launch { transition.withLock {"))
        assertTrue(src.contains("AdbLifecycle.coordinateSetup {"))
        assertTrue(src.contains("transition.withLock {\n                AdbLifecycle.coordinateSetup {"))
        // Cached-port reconnect and fresh-port fallback also run under the single-flight lock.
        assertEquals(5, Regex("connectAndSwitch\\((app|context)[,.]").findAll(src).count())
    }

    @Test fun staleCallbacksAreRejectedAfterStop() {
        assertTrue(src.contains("fun stale() = session != searchSession || !attached"))
        assertEquals(4, Regex("if \\(stale\\(\\)\\) return\\n").findAll(src).count())
        assertTrue(src.substringAfter("fun stop(").contains("searchSession++"))
    }

    @Test fun failuresEndInRecoveryRequiredWhichBlocksAutoConnect() {
        assertTrue(src.contains("enum class Phase { IDLE, SEARCHING, PAIRING, CONNECTING, SWITCHING, VERIFYING_TCP, READY, RECOVERY_REQUIRED }"))
        assertTrue(src.contains("status.value.phase != Phase.RECOVERY_REQUIRED"))
        assertTrue(src.contains("phase = Phase.READY, message"))
    }
}
