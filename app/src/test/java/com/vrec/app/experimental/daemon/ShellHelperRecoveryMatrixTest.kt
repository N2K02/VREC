package com.vrec.app.experimental.daemon

import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Simulates the helper-side failures seen on the physical phone:
 * PONG + UID 2000 but no SIM-source acknowledgement, an obsolete J protocol,
 * an unresponsive but current helper, and an active call during repair.
 */
class ShellHelperRecoveryMatrixTest {
    private val current = ShellHelperBuildPolicy.Decision.CURRENT
    private val old = ShellHelperBuildPolicy.Decision.STALE

    @Test fun timeoutDoesNotClaimHealthyOrTriggerBackgroundRestart() {
        val verdict = CaptureSetupProtocol.evaluate("1 WAIT 0", null, true, false, 0)
        assertFalse(verdict.ready)
        assertFalse(verdict.incompatible)
        assertEquals(CaptureSetupProtocol.Source.UNCONFIRMED, verdict.source)
        assertFalse(ShellHelperRepairPolicy.shouldRequestReplacement(current, verdict.incompatible, false))
        assertTrue(ShellHelperRepairPolicy.shouldRequestReplacement(current, verdict.incompatible, true))
    }

    @Test fun oldHelperMustBeUpgradedEvenIfItsPongProtocolIsCurrent() {
        val build = ShellHelperBuildPolicy.decide("PONG 2 1234abcd", "aabbccdd")
        assertEquals(old, build)
        assertTrue(ShellHelperRepairPolicy.shouldRequestReplacement(build, false, false))
    }

    @Test fun validSourceAcknowledgementsForAllSelectedSimSources() {
        for (source in 0..3) {
            val reply = "SOURCE $source"
            val verdict = CaptureSetupProtocol.evaluate("1 WAIT 0", reply, true, false, source)
            assertTrue("SIM source $source was rejected", verdict.ready)
            assertEquals(CaptureSetupProtocol.Source.APPLIED, verdict.source)
            assertFalse(verdict.incompatible)
        }
    }

    @Test fun wrongSourceInvalidOrLegacyAreNotReady() {
        for (reply in listOf("SOURCE 1", "SOURCE INVALID", "SOURCE UNSUPPORTED", "bogus")) {
            val verdict = CaptureSetupProtocol.evaluate("1 WAIT 0", reply, true, false, 0)
            assertFalse(verdict.ready)
            assertTrue(verdict.incompatible)
        }
    }

    @Test fun activeCallSourceBusyIsDeferredAndPreservesReadiness() {
        for (source in 0..3) {
            val verdict = CaptureSetupProtocol.evaluate("1 WAIT 0", "SOURCE BUSY", true, false, source)
            assertTrue(verdict.ready)
            assertFalse(verdict.incompatible)
            assertEquals(CaptureSetupProtocol.Source.DEFERRED_DURING_CALL, verdict.source)
        }
    }

    @Test fun aSpuriousEmptyModeReplyCannotAuthorizeReplacement() {
        val verdict = CaptureSetupProtocol.evaluate(null, null, true, false, 0)
        assertFalse(verdict.ready)
        assertFalse(verdict.incompatible)
        assertFalse(ShellHelperRepairPolicy.shouldRequestReplacement(current, verdict.incompatible, false))
    }

    @Test fun maxRetriesAreBoundedAndSuccessStopsImmediately() = runBlocking {
        for (successOn in 1..3) {
            var attempts = 0
            var waits = 0
            val ready = CaptureSetupProtocol.verifyWithRetry(
                maxAttempts = 3,
                configure = { ++attempts == successOn },
                incompatible = { false },
                waitBetween = { waits++ },
            )
            assertTrue(ready)
            assertEquals(successOn, attempts)
            assertEquals(successOn - 1, waits)
        }
    }

    @Test fun terminalIncompatibilityStopsRetryingBeforeAnyDelay() = runBlocking {
        var attempts = 0
        val ready = CaptureSetupProtocol.verifyWithRetry(
            configure = { attempts++; false },
            incompatible = { true },
            waitBetween = { error("No retries on an incompatible response") },
        )
        assertFalse(ready)
        assertEquals(1, attempts)
    }

    @Test fun timeoutsAndResetsAreReportedButNotMistakenForLegacyEof() = runBlocking {
        for (failureException in listOf(SocketTimeoutException("timeout"), SocketException("reset"))) {
            var attempts = 0
            var failure: String? = null
            val result = CaptureSourceHandshake.request(
                read = { attempts++; throw failureException },
                waitBetween = { error("A broken socket must not trigger duplicate commands") },
                onTransportError = { failure = it.javaClass.simpleName },
            )
            assertNull(result)
            assertEquals(1, attempts)
            assertEquals(failureException.javaClass.simpleName, failure)
        }
    }

    @Test fun boundedEofThenValidSourceReplyDoesNotUpgrade() = runBlocking {
        var attempts = 0
        val result = CaptureSourceHandshake.request(
            read = { if (++attempts == 1) null else "SOURCE 0" },
            waitBetween = {},
        )
        assertEquals("SOURCE 0", result)
        assertEquals(2, attempts)
        assertTrue(CaptureSetupProtocol.evaluate("1 WAIT 0", result, true, false, 0).ready)
    }

    @Test fun emptySourceResponsesRequireTwoIndependentEofsBeforeLegacyClassification() = runBlocking {
        var attempts = 0
        val result = CaptureSourceHandshake.request(
            read = { attempts++; null },
            waitBetween = {},
        )
        assertEquals(2, attempts)
        assertEquals(CaptureSourceHandshake.UNSUPPORTED, result)
        assertTrue(CaptureSetupProtocol.evaluate("1 WAIT 0", result, true, false, 0).incompatible)
    }
}
