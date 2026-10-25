package com.vrec.app.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingCallStartPolicyTest {
    @Test fun outgoingOffhookWaitsWhenExactAnswerSignalIsAvailable() {
        assertTrue(OutgoingCallStartPolicy.waitForTelecomAnswer(false, true, true))
    }

    @Test fun incomingAnswerCanRecordImmediately() {
        assertFalse(OutgoingCallStartPolicy.waitForTelecomAnswer(true, true, true))
    }

    @Test fun legacyOffhookFallbackRemainsWhenTelecomIsUnavailable() {
        assertFalse(OutgoingCallStartPolicy.waitForTelecomAnswer(false, false, true))
        assertFalse(OutgoingCallStartPolicy.waitForTelecomAnswer(false, true, false))
    }
}
