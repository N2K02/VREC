package com.vrec.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureControlPolicyTest {
    @Test fun recordingPermitsPauseAndStopOnly() {
        assertTrue(CaptureControlPolicy.isAllowed('P', true, false))
        assertFalse(CaptureControlPolicy.isAllowed('R', true, false))
        assertTrue(CaptureControlPolicy.isAllowed('S', true, false))
    }

    @Test fun pausedPermitsResumeAndStopOnly() {
        assertFalse(CaptureControlPolicy.isAllowed('P', true, true))
        assertTrue(CaptureControlPolicy.isAllowed('R', true, true))
        assertTrue(CaptureControlPolicy.isAllowed('S', true, true))
    }

    @Test fun stoppedOrMissingShellForbidsAllControlCommands() {
        for (action in "PRS") {
            assertFalse(CaptureControlPolicy.isAllowed(action, false, false))
            assertFalse(CaptureControlPolicy.isAllowed(action, false, true))
        }
        assertFalse(CaptureControlPolicy.isAllowed('X', true, false))
    }

    @Test fun repeatedCommandsAgainstOldStateAreRejected() {
        assertFalse(CaptureControlPolicy.isAllowed('P', true, true))
        assertFalse(CaptureControlPolicy.isAllowed('R', true, false))
        assertFalse(CaptureControlPolicy.isAllowed('S', false, false))
    }
}
