package com.vrec.app.experimental.daemon

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCaptureIdentityPolicyTest {
    @Test fun voipMustMatchExpectedCallApp() {
        val f = "vrec_rec_1793295000000_voip-com.whatsapp.dat"
        assertTrue(ManualCaptureIdentityPolicy.matches(f, "com.whatsapp", CallDirection.VOIP, true))
        assertFalse(ManualCaptureIdentityPolicy.matches(f, "org.telegram.messenger", CallDirection.VOIP, true))
        assertFalse(ManualCaptureIdentityPolicy.matches(f, null, CallDirection.INCOMING, false))
    }

    @Test fun cellularMustMatchDirectionAndNeverVoip() {
        val f = "vrec_rec_1793295000000_in.dat"
        assertTrue(ManualCaptureIdentityPolicy.matches(f, null, CallDirection.INCOMING, false))
        assertFalse(ManualCaptureIdentityPolicy.matches(f, null, CallDirection.OUTGOING, false))
        assertFalse(ManualCaptureIdentityPolicy.matches(f, "com.whatsapp", CallDirection.VOIP, true))
        assertFalse(ManualCaptureIdentityPolicy.matches("invalid.dat", null, CallDirection.INCOMING, false))
    }
}
