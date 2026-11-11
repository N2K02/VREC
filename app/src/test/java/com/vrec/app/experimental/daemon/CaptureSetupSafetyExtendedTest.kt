package com.vrec.app.experimental.daemon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect recording setup against retries, stale acknowledgements and mode transitions. */
class CaptureSetupSafetyExtendedTest {
    @Test fun masterOffOverridesAutomaticAndManualRecording() {
        assertEquals(0, CaptureSetupProtocol.selectedMode(false, true))
        assertEquals(0, CaptureSetupProtocol.selectedMode(false, false))
        assertEquals(1, CaptureSetupProtocol.selectedMode(true, true))
        assertEquals(2, CaptureSetupProtocol.selectedMode(true, false))
    }

    @Test fun staleModeAndWrongSourceAreNeverConsideredReady() {
        val wrongMode = CaptureSetupProtocol.evaluateMode("0 WAIT 0", "SOURCE 0", 1, false, 0)
        assertFalse(wrongMode.ready)
        assertTrue(wrongMode.incompatible)
        val wrongSource = CaptureSetupProtocol.evaluateMode("1 WAIT 0", "SOURCE 2", 1, false, 0)
        assertFalse(wrongSource.ready)
        assertTrue(wrongSource.incompatible)
        assertEquals(CaptureSetupProtocol.Source.UNCONFIRMED, wrongSource.source)
    }

    @Test fun busySourceDuringActiveCallDoesNotForceRecorderShutdown() {
        val deferred = CaptureSetupProtocol.evaluateMode("1 WAIT 1", "SOURCE BUSY", 1, true, 0)
        assertTrue(deferred.ready)
        assertFalse(deferred.incompatible)
        assertEquals(CaptureSetupProtocol.Source.DEFERRED_DURING_CALL, deferred.source)
    }

    @Test fun rejectedOrMissingModeAcknowledgementsFailClosed() {
        assertFalse(CaptureSetupProtocol.evaluateMode(null, "SOURCE 0", 1, false, 0).ready)
        assertFalse(CaptureSetupProtocol.evaluateMode("1 WAIT 0", null, 1, false, 0).ready)
        assertFalse(CaptureSetupProtocol.modeAcceptedMode("1 WAIT 1", 1, false))
        assertFalse(CaptureSetupProtocol.modeAcceptedMode("2 WAIT 1", 2, true))
    }

    @Test fun transientHelperFailureRetriesWithoutExtraAttempts() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Int>()
        val success = CaptureSetupProtocol.verifyWithRetry(
            configure = { ++attempts >= 3 },
            incompatible = { false },
            waitBetween = { waits.add(it) },
        )
        assertTrue(success)
        assertEquals(3, attempts)
        assertEquals(listOf(1, 2), waits)
    }

    @Test fun incompatibleHelperStopsRetriesImmediately() = runBlocking {
        var attempts = 0
        val success = CaptureSetupProtocol.verifyWithRetry(
            configure = { attempts++; false },
            incompatible = { true },
            waitBetween = { error("incompatible helper must not sleep") },
        )
        assertFalse(success)
        assertEquals(1, attempts)
    }
}
