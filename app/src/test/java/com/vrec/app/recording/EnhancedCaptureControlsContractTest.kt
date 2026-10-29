package com.vrec.app.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnhancedCaptureControlsContractTest {
    private fun source(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

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
        // All four surfaces route to one guarded shell receiver instead of
        // racing separate captureControl calls.
        assertTrue(overlay.contains("EnhancedCaptureControlReceiver.request(applicationContext, action)"))
        assertTrue(overlay.contains("EnhancedCaptureControlReceiver.request(applicationContext, 'S')"))
        assertTrue(notice.contains("controlIntent(context, 'S'"))
        assertTrue(receiver.contains("ExperimentalCaptureDaemon.captureControl(context, action)"))
        assertTrue(receiver.contains("pendingAction.compareAndSet(null, action)"))
        assertTrue(receiver.contains("CaptureControlPolicy.isAllowed(action"))
        assertTrue(notice.contains("enhancedPaused"))
    }
}
