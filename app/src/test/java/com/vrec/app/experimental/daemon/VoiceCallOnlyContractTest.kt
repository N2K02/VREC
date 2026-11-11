package com.vrec.app.experimental.daemon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A previously selected diagnostic source must not change the proven SIM capture path. */
class VoiceCallOnlyContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun settingsNoLongerExposeAudioSourceOrAlternativeModes() {
        val settings = source("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertFalse(settings.contains("Triple(\"Audio source\""))
        assertFalse(settings.contains("section == \"Audio source\""))
        assertFalse(settings.contains("SimCaptureSource.entries"))
        assertFalse(settings.contains("SourceOption("))
    }

    @Test fun reconnectAlwaysConfiguresVoiceCallNotLegacySavedPreferences() {
        val policy = source("app/src/main/java/com/vrec/app/experimental/daemon/EnhancedCapturePreferences.kt")
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(policy.contains("SIM_VOICE_CALL_SOURCE = 0"))
        assertFalse(policy.contains("enhanced_sim_capture_source"))
        assertTrue(daemon.contains("val selectedWireValue = SIM_VOICE_CALL_SOURCE"))
        assertTrue(daemon.contains("selectedWireValue.toByte()"))
        assertFalse(daemon.contains("EnhancedCapturePreferences.simSource(context)"))
    }

    @Test fun knownWorkingVoiceCallCaptureRemainsDefault() {
        val main = source("shellserver/src/com/vrec/app/shell/Main.java")
        val sim = source("shellserver/src/com/vrec/app/shell/SimAudioCapture.java")
        assertTrue(main.contains("simCaptureMode = SimAudioCapture.VOICE_CALL"))
        assertTrue(main.contains("SimAudioCapture.create(captureMode)"))
        assertTrue(sim.contains("new AudioDirectCapture(AudioSource.VOICE_CALL)"))
    }
}
