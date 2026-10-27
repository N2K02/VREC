package com.vrec.app.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnhancedCaptureControlsContractTest {
    private fun source(path: String) = File(path).readText()

    @Test fun shellControlsRequireAuthenticatedCommandAndFinalizeBeforeImport() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(shell.contains("if (!authenticate(client))"))
        assertTrue(shell.contains("COMMAND_CAPTURE_CONTROL"))
        assertTrue(shell.contains("userPausedCapture = true"))
        assertTrue(shell.contains("userStoppedCapture = true"))
        assertTrue(shell.contains("stopRecording(null)"))
        assertTrue(shell.contains("if (userPausedCapture || userStoppedCapture) return null;"))
    }

    @Test fun notificationAndOverlayUseSameShellControlProtocol() {
        val overlay = source("app/src/main/java/com/vrec/app/recording/OverlayControlService.kt")
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        assertTrue(overlay.contains("ExperimentalCaptureDaemon.captureControl"))
        assertTrue(notice.contains("controlIntent(context, 'S'"))
        assertTrue(receiver.contains("ExperimentalCaptureDaemon.captureControl"))
        assertTrue(notice.contains("enhancedPaused"))
    }
}
