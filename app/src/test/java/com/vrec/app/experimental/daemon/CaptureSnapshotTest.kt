package com.vrec.app.experimental.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSnapshotTest {
    @Test fun activeRecordingCarriesValidatedIdentity() {
        val state = ExperimentalCaptureDaemon.parseCaptureSnapshot("RECORDING vrec_rec_123_in.dat")
        assertEquals("vrec_rec_123_in.dat",
            (state as ExperimentalCaptureDaemon.CaptureSnapshot.Recording).file)
    }

    @Test fun pausedAndIdleAreDistinct() {
        assertTrue(ExperimentalCaptureDaemon.parseCaptureSnapshot("PAUSED") ===
            ExperimentalCaptureDaemon.CaptureSnapshot.Paused)
        assertTrue(ExperimentalCaptureDaemon.parseCaptureSnapshot("IDLE") ===
            ExperimentalCaptureDaemon.CaptureSnapshot.Idle)
    }

    @Test fun malformedOrLegacyResponsesNeverClaimRecording() {
        listOf("RECORDING", "RECORDING ../bad.dat", "RECORDING vrec_rec_1.dat extra",
            "RECORDING vrec_rec_1.mp3",
            "RECORDING vrec_rec_" + "a".repeat(200) + ".dat",
            "UNAVAILABLE", "").forEach { reply ->
            assertTrue(reply, ExperimentalCaptureDaemon.parseCaptureSnapshot(reply) ===
                ExperimentalCaptureDaemon.CaptureSnapshot.Unavailable)
        }
    }
}
