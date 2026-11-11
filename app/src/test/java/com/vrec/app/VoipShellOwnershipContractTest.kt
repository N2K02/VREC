package com.vrec.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guards for the WhatsApp Telecom/telephony duplicate-signal path. */
class VoipShellOwnershipContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun phoneStateChecksIndependentVoipAudioBeforeClaimingCellular() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        val voip = source("shellserver/src/com/vrec/app/shell/Voip.java")
        assertTrue(shell.contains("callAudio.getMode() == AudioManager.MODE_IN_COMMUNICATION"))
        assertTrue(shell.contains("messenger = Voip.uniquelyCallingApp(callAudio)"))
        assertTrue(shell.contains("onCallStart(incoming, messenger)"))
        assertTrue(voip.contains("for (int uid : callingUids(audio, false))"))
        assertTrue(voip.contains("if (only != null && !only.equals(pkg)) return null;"))
        assertTrue(voip.contains("if (audio == null || policy == null) return null;"))
    }

    @Test fun lateVoipEventOnlyCorrectsUnrecordedSessionWithMatchingAudioEvidence() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(shell.contains("app.equals(callVoipApp)"))
        assertTrue(shell.contains("activeRecording == null && Voip.uniquelyCallingApp(callAudio) != null"))
        assertTrue(shell.contains("app.equals(Voip.uniquelyCallingApp(callAudio))"))
        assertTrue(shell.contains("answerGate.reset();"))
        assertTrue(shell.contains("callActive = false;"))
        assertTrue(shell.contains("if (callActive && (app != null || callVoipApp != null))"))
    }

    @Test fun cellularCallEndDoesNotStartMessengerRecording() {
        // Restored v6.8 behaviour: ending a phone call never opens a VoIP capture.
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertFalse(shell.contains("Voip.followedApp()"))
        assertFalse(shell.contains("continuing messenger call"))
    }

    @Test fun voipCaptureUsesV68ClockAndMicGuard() {
        val voip = source("shellserver/src/com/vrec/app/shell/Voip.java")
        assertTrue(voip.contains("startUs = SystemClock.elapsedRealtimeNanos() / 1000;"))
        assertFalse(voip.contains("microphone safety check failed"))
    }

    @Test fun telegramViberAndMeetAllRemainArmedWithoutCallerFilters() {
        val policy = source("app/src/main/java/com/vrec/app/experimental/daemon/EnhancedCapturePreferences.kt")
        assertTrue(policy.contains("org.telegram.messenger"))
        assertTrue(policy.contains("com.viber.voip"))
        assertTrue(policy.contains("com.google.android.apps.tachyon"))
        assertTrue(policy.contains("sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertFalse(policy.contains("selectedContacts"))
    }

    @Test fun voipWarningDisplaysStableShellStateInsteadOfObfuscatedClassName() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(monitor.contains("RECORDING_OTHER_SOURCE"))
        assertTrue(monitor.contains("CaptureSnapshot.Idle -> \"IDLE\""))
        assertTrue(monitor.contains("CaptureSnapshot.Unavailable -> \"UNAVAILABLE\""))
        assertFalse(monitor.contains("snapshot.javaClass.simpleName"))
    }
}
