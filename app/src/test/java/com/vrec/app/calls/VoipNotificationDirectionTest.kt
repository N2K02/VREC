package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class VoipNotificationDirectionTest {
    @Test fun ringingCallIsIncoming() {
        assertEquals(CallDirection.INCOMING,
            VoipNotificationDirection.fromSignal(1, "RINGING", "Sonu", null))
        assertEquals(CallDirection.INCOMING,
            VoipNotificationDirection.fromSignal(0, "RINGING", "Sonu", "Incoming call"))
        assertEquals(CallDirection.INCOMING,
            VoipNotificationDirection.fromSignal(3, "RINGING", null, null))
        assertEquals(CallDirection.INCOMING,
            VoipNotificationDirection.fromSignal(0, "UNKNOWN", "Sonu", "Incoming voice call"))
    }

    @Test fun outgoingRequiresExplicitDirectionEvidence() {
        assertEquals(CallDirection.OUTGOING,
            VoipNotificationDirection.fromSignal(0, "ACTIVE", "Calling Sonu", null))
        assertEquals(CallDirection.OUTGOING,
            VoipNotificationDirection.fromSignal(2, "ACTIVE", "Sonu", "Outgoing call"))
        assertEquals(CallDirection.OUTGOING,
            VoipNotificationDirection.fromSignal(0, "UNKNOWN", "Dialing Sonu", null))
        assertEquals(CallDirection.OUTGOING,
            VoipNotificationDirection.fromSignal(0, "ACTIVE", "Calling…", null))
    }

    @Test fun ongoingOrLegacyStatusAloneCannotProveOutgoing() {
        assertEquals(CallDirection.UNKNOWN,
            VoipNotificationDirection.fromSignal(2, "ACTIVE", "Sonu", "Call in progress"))
        assertEquals(CallDirection.UNKNOWN,
            VoipNotificationDirection.fromSignal(0, "ACTIVE", "Sonu", null))
        assertEquals(CallDirection.UNKNOWN,
            VoipNotificationDirection.fromSignal(0, "UNKNOWN", null, null))
        assertEquals(CallDirection.UNKNOWN,
            VoipNotificationDirection.fromSignal(0, "ACTIVE", "Sonu", "Voice call"))
    }

    @Test fun incomingCallStyleOverridesAmbiguousOutgoingNotificationText() {
        assertEquals(CallDirection.INCOMING,
            VoipNotificationDirection.fromSignal(1, "RINGING", "Calling Sonu", null))
    }
}
