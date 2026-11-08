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
        assertTrue(src.contains("val adb = SharedAdbHostAdapter.core(app)"))
        assertTrue(src.contains("if (!owning)"))
        assertTrue(src.contains("adb.acquireService()"))
        assertTrue(src.contains("adb.state.collect"))
        assertFalse(src.contains("ExperimentalRecording.connectWireless("))
        assertFalse(src.contains("NsdManager"))
    }

    @Test fun staleCallbacksAreRejectedAfterStop() {
        assertTrue(src.contains("observing?.cancel()"))
        assertTrue(src.contains("observedCore?.releaseService()"))
        assertTrue(src.contains("observedCore = null"))
        assertTrue(src.contains("if (AdbLifecycle.quitting || AdbUserChoices.manuallyStopped(app)) return"))
    }

    @Test fun failuresEndInRecoveryRequiredWhichBlocksAutoConnect() {
        assertTrue(src.contains("RECOVERY_REQUIRED"))
        assertTrue(src.contains("AdbPhase.NEEDS_SETUP -> Phase.RECOVERY_REQUIRED"))
        assertTrue(src.contains("s.phase == AdbPhase.PAIRING"))
        assertTrue(src.contains("s.connected"))
        assertFalse(src.contains("private val transition = Mutex()"))
    }
}
