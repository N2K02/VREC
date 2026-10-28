package com.vrec.app.experimental.daemon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays actual E/Y acknowledgements without an Android phone or ADB socket. */
class CaptureSetupFlowTest {
    private val protocol = CaptureSetupProtocol

    @Test fun firstStartReplaysUidVerifiedModeAndSourceThenBecomesReady() = runBlocking {
        val script = listOf(
            null to null, // PONG is up but E handshake has not settled yet
            "1 WAIT 0" to "SOURCE 0",
        )
        var attempts = 0
        var delays = 0
        val confirmed = protocol.verifyWithRetry(
            configure = {
                val (mode, source) = script[attempts++]
                protocol.evaluate(mode, source, true, false, 0).ready
            },
            incompatible = { false },
            waitBetween = { delays++ },
        )
        assertTrue(confirmed)
        assertEquals(2, attempts)
        assertEquals(1, delays)
    }

    @Test fun existingHelperReattachesWithoutRestartingAfterValidAck() = runBlocking {
        var attempts = 0
        val confirmed = protocol.verifyWithRetry(
            configure = {
                attempts++
                protocol.evaluate("0 WAIT 0", "SOURCE 0", false, false, 0).ready
            },
            incompatible = { false },
            waitBetween = { error("No retry expected") },
        )
        assertTrue(confirmed)
        assertEquals(1, attempts)
    }

    @Test fun activeCallDefersSourceChangeWithoutDisablingWorkingHelper() {
        val result = protocol.evaluate("1 WAIT 0", "SOURCE BUSY", true, false, 3)
        assertTrue(result.ready)
        assertEquals(CaptureSetupProtocol.Source.DEFERRED_DURING_CALL, result.source)
        assertFalse(result.incompatible)
    }

    @Test fun answeredCallGateRejectsLegacyOneByteModeAcknowledgement() {
        assertFalse(protocol.evaluate("1", "SOURCE 0", true, true, 0).ready)
        assertTrue(protocol.evaluate("1 WAIT 1", "SOURCE 0", true, true, 0).ready)
        assertFalse(protocol.evaluate("1 WAIT 0", "SOURCE 0", true, true, 0).ready)
    }

    @Test fun invalidOrWrongSimSourceCannotMasqueradeAsSuccessfulStartup() {
        assertFalse(protocol.evaluate("1 WAIT 0", "SOURCE INVALID", true, false, 0).ready)
        assertFalse(protocol.evaluate("1 WAIT 0", "SOURCE 3", true, false, 0).ready)
        assertTrue(protocol.evaluate("1 WAIT 0", "SOURCE 0", true, false, 0).ready)
    }

    @Test fun explicitProtocolIncompatibilityStopsRetriesInsteadOfSpamming() = runBlocking {
        var attempts = 0
        var incompatible = false
        val confirmed = protocol.verifyWithRetry(
            configure = {
                attempts++
                val result = protocol.evaluate("UNKNOWN COMMAND", null, true, false, 0)
                incompatible = result.incompatible
                result.ready
            },
            incompatible = { incompatible },
            waitBetween = { error("Incompatible helper must not be repeatedly polled") },
        )
        assertFalse(confirmed)
        assertEquals(1, attempts)
    }

    @Test fun legacyHelperClosingUnknownSimCommandRequestsSafeUpgrade() = runBlocking {
        // Old helpers can acknowledge E and then close the unknown J command
        // immediately. The caller confirms EOF twice before using this marker.
        val result = protocol.evaluate("1 WAIT 0", "SOURCE UNSUPPORTED", true, false, 0)
        assertFalse(result.ready)
        assertTrue(result.incompatible)
        assertEquals(CaptureSetupProtocol.Source.LEGACY_HELPER, result.source)
        assertTrue(result.detail.contains("upgrade"))

        var attempts = 0
        val upgraded = protocol.verifyWithRetry(
            configure = {
                attempts++
                result.ready
            },
            incompatible = { result.incompatible },
            waitBetween = { error("An incompatible helper must not be reconfigured repeatedly") },
        )
        assertFalse(upgraded)
        assertEquals(1, attempts)
    }

    @Test fun realSocketTimeoutNeverCausesAutomaticHelperReplacement() {
        val result = protocol.evaluate("1 WAIT 0", null, true, false, 0)
        assertFalse(result.ready)
        assertFalse(result.incompatible)
        assertEquals(CaptureSetupProtocol.Source.UNCONFIRMED, result.source)
    }

    @Test fun unresponsiveHelperUsesBoundedRetriesAndDoesNotAssumeReady() = runBlocking {
        var attempts = 0
        val confirmed = protocol.verifyWithRetry(
            configure = {
                attempts++
                protocol.evaluate(null, null, true, false, 0).ready
            },
            incompatible = { false },
            waitBetween = {},
        )
        assertFalse(confirmed)
        assertEquals(3, attempts)
    }

    @Test fun noTelecomAppOpDoesNotAffectModeOrSimConfirmation() {
        // Advanced Telecom detection is optional. E uses WAIT 0 when denied;
        // the helper itself must still be able to start in automatic mode.
        assertTrue(protocol.evaluate("1 WAIT 0", "SOURCE 0", true, false, 0).ready)
    }
}
