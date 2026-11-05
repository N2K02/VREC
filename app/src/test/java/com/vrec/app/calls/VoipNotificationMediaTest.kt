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

    @Test fun explicitlyLabeledVoiceAndAudioAreVoice() {
        assertEquals("VOICE", VoipNotificationMedia.type("Outgoing voice call", "Chachi Renou"))
        assertEquals("VOICE", VoipNotificationMedia.type("Chachi Renou", "Audio call"))
        assertEquals("VIDEO", VoipNotificationMedia.type("Video call", "Voice call"))
    }

    @Test fun ordinaryAndUnknownCallsNeverInventVideo() {
        assertEquals("VOICE", VoipNotificationMedia.type("Ongoing voice call", "Nina"))
        assertNull(VoipNotificationMedia.type("WhatsApp", "Nina"))
        assertNull(VoipNotificationMedia.type(null, null))
        assertEquals("VOICE", VoipNotificationMedia.type("Calling", "Audio call"))
    }
}
