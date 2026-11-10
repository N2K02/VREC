package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class MissedCallWatchAuditTest {
    @Test fun detectedCallDoesNotTriggerWarning() {
        assertFalse(MissedCallWatchAudit.missedSince(10000, 20000, listOf(19000)))
    }
    @Test fun missedRecentCallTriggersWarning() {
        assertTrue(MissedCallWatchAudit.missedSince(10000, 15000, listOf(20000)))
    }
    @Test fun ignoresCallBeforeWatchStarted() {
        assertFalse(MissedCallWatchAudit.missedSince(10000, 0, listOf(9000)))
    }
    @Test fun noBaselineMeansInconclusiveNotFailure() {
        assertFalse(MissedCallWatchAudit.missedSince(0, 0, listOf(4000)))
    }
}
