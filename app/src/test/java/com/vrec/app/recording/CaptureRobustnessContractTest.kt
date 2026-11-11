package com.vrec.app.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards existing audio behavior when making shell-side reliability changes. */
class CaptureRobustnessContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun dualSourceKeepsUnconsumedSamplesAcrossReads() {
        val sim = source("shellserver/src/com/vrec/app/shell/SimAudioCapture.java")
        assertTrue(sim.contains("nearAvailable"))
        assertTrue(sim.contains("farAvailable"))
        assertTrue(sim.contains("nearOffset += frames"))
        assertTrue(sim.contains("farOffset += frames"))
        assertTrue(sim.contains("nearAvailable -= frames"))
        assertTrue(sim.contains("farAvailable -= frames"))
        assertTrue(sim.contains("if (nearAvailable == 0)"))
        assertTrue(sim.contains("if (farAvailable == 0)"))
        assertTrue(sim.contains("nearTimestampUs += frames"))
        assertTrue(sim.contains("if (n % 4 != 0"))
    }

    @Test fun voipNeverStartsAmbiguousCallAndCleansUpCaptureFailures() {
        val voip = source("shellserver/src/com/vrec/app/shell/Voip.java")
        assertTrue(voip.contains("candidateUid != uid"))
        assertTrue(voip.contains("multiple eligible calling apps"))
        assertTrue(voip.contains("return null;"))
        assertTrue(voip.contains("mic.release()"))
        assertTrue(voip.contains("far.release()"))
        assertTrue(voip.contains("farPump.close()"))
        assertTrue(voip.contains("capture.dropMic()"))
    }

    @Test fun originalAudioAndImportProtectionRemainInPlace() {
        val main = source("shellserver/src/com/vrec/app/shell/Main.java")
        val voip = source("shellserver/src/com/vrec/app/shell/Voip.java")
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(main.contains("SimAudioCapture.create(captureMode)"))
        assertTrue(main.contains("Voip.newCapture()"))
        assertTrue(main.contains("PcmChannelHealth"))
        assertTrue(voip.contains("setPrivacySensitive(false)"))
        assertTrue(daemon.contains("RecordingStore.hasValidMetadata(dest)"))
    }
}
