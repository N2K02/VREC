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
        fun read(path: String): String = listOf(
            java.io.File(path), java.io.File("../$path")
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing $path")
        val source = read("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertFalse(source.contains("ContactRecordingRules"))
        assertTrue(source.contains("Prefs.autoRecord(context)"))
        val prefs = read("app/src/main/java/com/vrec/app/Prefs.kt")
        assertTrue(prefs.contains("fun autoRecord(c: Context) = true"))
        assertTrue(prefs.contains("fun recordVoip(c: Context) = true"))
    }
}
