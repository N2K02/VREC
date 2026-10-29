package com.vrec.app

import com.vrec.app.recording.RecordingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTransportPresentationTest {
    private val idle = RecordingService.State.Idle
    private val starting = RecordingService.State.Starting
    private val stopping = RecordingService.State.Stopping
    private val recording = RecordingService.State.Recording("WhatsApp", "Alice", false)
    private val paused = RecordingService.State.Paused("WhatsApp", "Alice", false, 8_000L)

    @Test fun pauseResumeAndStopRequireAuthenticatedActiveShell() {
        assertTrue(RecordingTransportPresentation.canPauseOrResume(recording, true))
        assertTrue(RecordingTransportPresentation.canPauseOrResume(paused, true))
        assertTrue(RecordingTransportPresentation.canStop(recording, true))
        assertTrue(RecordingTransportPresentation.canStop(paused, true))
        // ADB-only RecordingService stays Idle while authenticated shell
        // capture is active; the transport must remain usable in that state.
        assertTrue(RecordingTransportPresentation.canPauseOrResume(idle, true))
        assertTrue(RecordingTransportPresentation.canStop(idle, true))
        for (state in listOf(starting, stopping)) {
            assertFalse(RecordingTransportPresentation.canPauseOrResume(state, true))
            assertFalse(RecordingTransportPresentation.canStop(state, true))
        }
        assertFalse(RecordingTransportPresentation.canPauseOrResume(idle, false))
        assertFalse(RecordingTransportPresentation.canPauseOrResume(recording, false))
        assertFalse(RecordingTransportPresentation.canStop(paused, false))
    }

    @Test fun globalToggleLockedForAllNonIdleStatesAndShellCapture() {
        assertTrue(RecordingTransportPresentation.canChangeMasterToggle(idle, false))
        assertFalse(RecordingTransportPresentation.canChangeMasterToggle(idle, true))
        for (state in listOf(starting, recording, paused, stopping)) {
            assertFalse(RecordingTransportPresentation.canChangeMasterToggle(state, true))
            assertFalse(RecordingTransportPresentation.canChangeMasterToggle(state, false))
        }
    }

    @Test fun elapsedRecordingCountsUpButPausedTimeFreezes() {
        assertEquals(61_000L, RecordingTransportPresentation.elapsedSegment(
            1_000L, 62_000L, null))
        assertEquals(12_000L, RecordingTransportPresentation.elapsedSegment(
            1_000L, 100_000L, 12_000L))
        assertEquals(0L, RecordingTransportPresentation.elapsedSegment(
            100_000L, 1_000L, null))
        assertNull(RecordingTransportPresentation.elapsedSegment(null, 100_000L, null))
    }

    @Test fun durationLabelHandlesMissingSecondsMinutesAndHours() {
        assertEquals("--:--", RecordingTransportPresentation.durationLabel(null))
        assertEquals("00:00", RecordingTransportPresentation.durationLabel(0L))
        assertEquals("00:09", RecordingTransportPresentation.durationLabel(9_000L))
        assertEquals("01:23", RecordingTransportPresentation.durationLabel(83_000L))
        assertEquals("01:02:03", RecordingTransportPresentation.durationLabel(3_723_000L))
    }
}
