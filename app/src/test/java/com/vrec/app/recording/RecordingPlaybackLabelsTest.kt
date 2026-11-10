package com.vrec.app.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackLabelsTest {
    @Test fun whatsappIncomingIncludesCallMetadata() {
        val label = RecordingPlaybackLabels.details("com.whatsapp", "INCOMING", 1_700_000_000_000L)
        assertTrue(label.contains("WhatsApp"))
        assertTrue(label.contains("Incoming"))
        assertTrue(label.contains("2023"))
    }

    @Test fun cellularOutgoingUsesPhoneCall() {
        val label = RecordingPlaybackLabels.details(null, "OUTGOING", 1_700_000_000_000L)
        assertTrue(label.contains("Phone call"))
        assertTrue(label.contains("Outgoing"))
    }

    @Test fun unknownPackagesDoNotLeakPackageIdentifiers() {
        val label = RecordingPlaybackLabels.details("example.private.app", "UNKNOWN", 1_700_000_000_000L)
        assertTrue(label.contains("App call"))
        assertFalse(label.contains("example.private.app"))
    }
}
