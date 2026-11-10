package com.vrec.app

import com.vrec.app.experimental.daemon.CaptureSetupProtocol
import org.junit.Assert.*
import org.junit.Test

/** No caller-based allowlist, and enabled recording never enters ask-first mode. */
class AllCallsModeContractTest {
    @Test fun enabledRecordingUsesAutomaticMode() {
        assertEquals(1, CaptureSetupProtocol.selectedMode(true, true))
    }
    @Test fun masterSwitchOffDisablesCapture() {
        assertEquals(0, CaptureSetupProtocol.selectedMode(false, true))
    }
    @Test fun sourceContainsNoSelectiveRecordingRule() {
        val source = java.io.File("src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
            .readText()
        assertFalse(source.contains("ContactRecordingRules"))
        assertTrue(source.contains("Prefs.autoRecord(context)"))
        val prefs = java.io.File("src/main/java/com/vrec/app/Prefs.kt").readText()
        assertTrue(prefs.contains("fun autoRecord(c: Context) = true"))
        assertTrue(prefs.contains("fun recordVoip(c: Context) = true"))
    }
}
