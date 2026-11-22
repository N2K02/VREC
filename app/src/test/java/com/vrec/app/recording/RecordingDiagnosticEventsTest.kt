package com.vrec.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingDiagnosticEventsTest {
    @Test fun onlySafeLabelsAreRetained() {
        RecordingDiagnosticEvents.clear()
        RecordingDiagnosticEvents.record(
            "Capture diagnostic: shell recording import failed file=/data/local/tmp/123456789.dat cause=secret"
        )
        assertEquals(listOf("Recording import failed"), RecordingDiagnosticEvents.snapshot())
        RecordingDiagnosticEvents.record("Private caller +2301234567")
        assertEquals(1, RecordingDiagnosticEvents.snapshot().size)
        RecordingDiagnosticEvents.clear()
    }

    @Test fun eventHistoryIsBoundedAndClearable() {
        RecordingDiagnosticEvents.clear()
        repeat(120) { RecordingDiagnosticEvents.record("Capture diagnostic: shell import found 2 pending") }
        assertEquals(100, RecordingDiagnosticEvents.snapshot().size)
        RecordingDiagnosticEvents.clear()
        assertTrue(RecordingDiagnosticEvents.snapshot().isEmpty())
    }
}
