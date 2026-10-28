package com.vrec.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDashboardPresentationTest {
    @Test fun activeRecordingOverridesSwitchAndReadinessIndicators() {
        assertEquals(HomeDashboardPresentation.State.RECORDING,
            HomeDashboardPresentation.resolve(enabled = false, ready = false,
                recording = true, paused = false))
        assertEquals("Recording in progress",
            HomeDashboardPresentation.title(HomeDashboardPresentation.State.RECORDING))
    }

    @Test fun pausedRecordingsAreNotMislabelledReadyOrOff() {
        assertEquals(HomeDashboardPresentation.State.PAUSED,
            HomeDashboardPresentation.resolve(enabled = true, ready = true,
                recording = false, paused = true))
    }

    @Test fun disabledRecordingNeverClaimsCaptureReadiness() {
        assertEquals(HomeDashboardPresentation.State.OFF,
            HomeDashboardPresentation.resolve(enabled = false, ready = true,
                recording = false, paused = false))
    }

    @Test fun readyRequiresBothMasterToggleAndWorkingHelper() {
        assertEquals(HomeDashboardPresentation.State.READY,
            HomeDashboardPresentation.resolve(enabled = true, ready = true,
                recording = false, paused = false))
        assertEquals(HomeDashboardPresentation.State.SETUP_NEEDED,
            HomeDashboardPresentation.resolve(enabled = true, ready = false,
                recording = false, paused = false))
    }

    @Test fun helperProblemsAreNotDescribedAsSuccessfulRecording() {
        assertEquals("The shell helper needs attention before new calls can be captured.",
            HomeDashboardPresentation.detail(HomeDashboardPresentation.State.SETUP_NEEDED))
    }
    @Test fun preparingAndSavingNeverClaimSuccessfulCapture() {
        val starting = HomeDashboardPresentation.resolve(enabled = true, ready = true,
            recording = false, paused = false, starting = true)
        val saving = HomeDashboardPresentation.resolve(enabled = true, ready = false,
            recording = false, paused = false, saving = true)
        assertEquals(HomeDashboardPresentation.State.STARTING, starting)
        assertEquals("Preparing recording", HomeDashboardPresentation.title(starting))
        assertEquals(HomeDashboardPresentation.State.SAVING, saving)
        assertEquals("Saving recording", HomeDashboardPresentation.title(saving))
    }
}
