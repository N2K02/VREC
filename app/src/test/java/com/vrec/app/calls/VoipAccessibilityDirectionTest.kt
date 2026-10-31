package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoipAccessibilityDirectionTest {
    @Test fun recognizesOnlyExplicitDirection() {
        assertEquals(CallDirection.OUTGOING,
            VoipAccessibilityDirection.fromCallLabels(listOf("Sam", "Calling…", "End call")))
        assertEquals(CallDirection.INCOMING,
            VoipAccessibilityDirection.fromCallLabels(listOf("Incoming video call", "Sam")))
    }
    @Test fun doesNotGuessFromOngoingOrRingingLabels() {
        assertNull(VoipAccessibilityDirection.fromCallLabels(listOf("Sam", "Ongoing voice call", "End call")))
        assertNull(VoipAccessibilityDirection.fromCallLabels(listOf("Sam", "Ringing", "End call")))
        assertNull(VoipAccessibilityDirection.fromCallLabels(emptyList()))
    }
    @Test fun conflictingSignalsRemainUnknown() {
        assertNull(VoipAccessibilityDirection.fromCallLabels(
            listOf("Calling Sam", "Incoming call", "End call")))
    }
}
