package com.vrec.app

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotificationPresentationTest {
    @Test fun notificationKeepsCallerAppDirectionAndLiveStatus() {
        val info = LiveRecordingInfo("Alice", "WhatsApp", 1_000L,
            "com.whatsapp", CallDirection.INCOMING)
        assertEquals("Recording now · Alice",
            RecordingNotificationPresentation.title(info.caller, false))
        assertEquals("WhatsApp · Incoming · Recording",
            RecordingNotificationPresentation.description(info, false, "01:24"))
        assertTrue(RecordingNotificationPresentation.showsChronometer(false, 1_000L))
    }

    @Test fun pausedNotificationShowsFrozenTimeInsteadOfRunningChronometer() {
        val info = LiveRecordingInfo("Bob", "SIM call", 1_000L, null, CallDirection.OUTGOING)
        assertEquals("Paused · Bob",
            RecordingNotificationPresentation.title(info.caller, true))
        assertEquals("SIM call · Outgoing · Paused at 02:05",
            RecordingNotificationPresentation.description(info, true, "02:05"))
        assertFalse(RecordingNotificationPresentation.showsChronometer(true, 1_000L))
    }

    @Test fun unknownAndRestartRecoveryNeverInventElapsedTime() {
        val unknown = LiveRecordingInfo("Unknown caller", "ADB shell", null)
        assertEquals("ADB shell · Call · Recording",
            RecordingNotificationPresentation.description(unknown, false, "--:--"))
        assertEquals("ADB shell · Call · Paused at --:--",
            RecordingNotificationPresentation.description(unknown, true, "--:--"))
        assertFalse(RecordingNotificationPresentation.showsChronometer(false, null))
    }

    @Test fun callerSelectionPrefersKnownFieldsWithoutGuessing() {
        assertEquals("Known contact",
            LiveRecordingInfoResolver.preferredCaller("Known contact", "+23050123456", null))
        assertEquals("+23050123456",
            LiveRecordingInfoResolver.preferredCaller(null, " +23050123456 "))
        assertEquals("Unknown caller",
            LiveRecordingInfoResolver.preferredCaller(" ", null, ""))
    }
}
