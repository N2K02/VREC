package com.vrec.app

import com.vrec.app.recording.RecordingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VrecQuitSafetyTest {
    @Test fun unreachablePreviouslyAuthorizedHelperCannotBeTreatedAsIdle() {
        org.junit.Assert.assertFalse(VrecQuitSafety.canQuitWithoutHelper(true, false))
        org.junit.Assert.assertFalse(VrecQuitSafety.canQuitWithoutHelper(false, true))
        org.junit.Assert.assertTrue(VrecQuitSafety.canQuitWithoutHelper(false, false))
    }

    @Test fun activeRecordingAndShellAreNeverEligibleForQuit() {
        assertTrue(VrecQuitSafety.idle(RecordingService.State.Idle, false, false))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Idle, true, false))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Idle, false, true))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Starting, false, false))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Stopping, false, false))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Recording("ADB", null, false), false, false))
        assertFalse(VrecQuitSafety.idle(RecordingService.State.Paused("ADB", null, false, 0L), false, false))
    }

    @Test fun shutdownOrdersModeOffImportAndExit() = runBlocking {
        val steps = mutableListOf<String>()
        assertTrue(VrecQuitSafety.sequence(
            { steps += "disable"; true },
            { steps += "import"; true },
            { steps += "exit"; true },
        ))
        assertEquals(listOf("disable", "import", "exit"), steps)
    }

    @Test fun failureDoesNotDisconnectOrProceedToLaterStages() = runBlocking {
        for (failedStage in listOf("disable", "import", "exit")) {
            val steps = mutableListOf<String>()
            assertFalse(VrecQuitSafety.sequence(
                { steps += "disable"; failedStage != "disable" },
                { steps += "import"; failedStage != "import" },
                { steps += "exit"; failedStage != "exit" },
            ))
            val expected = listOf("disable", "import", "exit").take(
                listOf("disable", "import", "exit").indexOf(failedStage) + 1)
            assertEquals(expected, steps)
        }
    }
}
