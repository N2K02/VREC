package com.vrec.app.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSourceConflictPolicyTest {
    @Test fun mirroredWhatsappOffhookCannotCreateIndependentSimOwner() {
        assertTrue(CallSourceConflictPolicy.suppressFallbackSimOffhook(
            activeTelecomVoip = true, knownCellularTelecom = false))
    }

    @Test fun realCellularTelecomCallIsNeverSuppressed() {
        assertFalse(CallSourceConflictPolicy.suppressFallbackSimOffhook(
            activeTelecomVoip = true, knownCellularTelecom = true))
        assertFalse(CallSourceConflictPolicy.suppressFallbackSimOffhook(
            activeTelecomVoip = false, knownCellularTelecom = true))
        assertFalse(CallSourceConflictPolicy.suppressFallbackSimOffhook(
            activeTelecomVoip = false, knownCellularTelecom = false))
    }
}
