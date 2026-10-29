package com.vrec.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A green permissions card or a PONG alone must never hide capture failures. */
class RecordingWarningPolicyTest {
    @Test fun confirmedHelperRecoveryClearsPreviousStartupWarning() {
        assertTrue(RecordingWarningPolicy.clearAfterHelperRecovered(
            RecordingWarningPolicy.HELPER_START_FAILURE))
    }

    @Test fun unrelatedImportFailureSurvivesHelperRecovery() {
        assertFalse(RecordingWarningPolicy.clearAfterHelperRecovered(
            "Recording import failed; original shell copy preserved."))
    }

    @Test fun unrelatedCallAudioFailureSurvivesHelperRecovery() {
        assertFalse(RecordingWarningPolicy.clearAfterHelperRecovered(
            "Call audio was silent or unavailable."))
    }

    @Test fun noWarningDoesNotTriggerRedundantDismiss() {
        assertFalse(RecordingWarningPolicy.clearAfterHelperRecovered(null))
        assertFalse(RecordingWarningPolicy.clearAfterCaptureStarted(null, false))
    }

    @Test fun newRecordingClearsOnlyTransientWarnings() {
        val warning = RecordingWarningPolicy.HELPER_START_FAILURE
        assertTrue(RecordingWarningPolicy.clearAfterCaptureStarted(warning, false))
        assertFalse(RecordingWarningPolicy.clearAfterCaptureStarted(warning, true))
    }
}
