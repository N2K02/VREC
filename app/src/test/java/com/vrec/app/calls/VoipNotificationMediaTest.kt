package com.vrec.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoipNotificationMediaTest {
    @Test fun explicitlyLabeledVideoCallsAreVideo() {
        assertEquals("VIDEO", VoipNotificationMedia.type("Incoming video call", "Nina"))
        assertEquals("VIDEO", VoipNotificationMedia.type("Nina", "WhatsApp video calling"))
        assertEquals("VIDEO", VoipNotificationMedia.type(null, "Video chat with Nina"))
    }

    @Test fun ordinaryAndUnknownCallsNeverInventVideo() {
        assertNull(VoipNotificationMedia.type("Ongoing voice call", "Nina"))
        assertNull(VoipNotificationMedia.type("WhatsApp", "Nina"))
        assertNull(VoipNotificationMedia.type(null, null))
        assertNull(VoipNotificationMedia.type("Calling", "Audio call"))
    }
}
