package com.vrec.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbHomeDisplayPolicyTest {
    @Test fun healthyRecordingCollapsesSetupButtons() {
        assertTrue(AdbHomeDisplayPolicy.compact(recordingActive = true, helperHealthy = true))
    }

    @Test fun idleAlwaysRestoresFullHelperCard() {
        assertFalse(AdbHomeDisplayPolicy.compact(recordingActive = false, helperHealthy = true))
        assertFalse(AdbHomeDisplayPolicy.compact(recordingActive = false, helperHealthy = false))
    }

    @Test fun disconnectDuringRecordingKeepsRecoveryControlsVisible() {
        assertFalse(AdbHomeDisplayPolicy.compact(recordingActive = true, helperHealthy = false))
    }

    @Test fun standardAndShellCaptureHaveDistinctDescriptions() {
        assertEquals("Standard recording active · helper available",
            AdbHomeDisplayPolicy.subtitle(standardRecording = true))
        assertEquals("Enhanced recording active · helper healthy",
            AdbHomeDisplayPolicy.subtitle(standardRecording = false))
    }
}
