package com.vrec.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMethodPolicyTest {
    @Test fun idleAllowsSwitch() {
        assertTrue(RecordingMethodPolicy.canChange(true, false))
    }

    @Test fun standardRecorderLocksMethod() {
        assertFalse(RecordingMethodPolicy.canChange(false, false))
    }

    @Test fun shellRecorderLocksMethod() {
        assertFalse(RecordingMethodPolicy.canChange(true, true))
    }

    @Test fun bothActiveLocksMethod() {
        assertFalse(RecordingMethodPolicy.canChange(false, true))
    }
}
