package com.vrec.app.recording

import com.vrec.app.recording.CallDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoipCallTitlePresentationTest {
    private fun recording(
        latest: List<String> = emptyList(), legacy: String? = "Chachi Renou",
    ) = Recording(
        file = File("20261105_091300_VOIP.ogg"),
        startedAt = 1_000_000L,
        direction = CallDirection.VOIP,
        peer = "WhatsApp",
        sourceLabel = "WhatsApp",
        silent = false,
        durationMs = 8_000L,
        originalCallerName = legacy,
        callNameHistory = latest,
    )

    @Test fun latestObservedGroupNameWinsOverOriginalCaller() {
        val rec = recording(listOf("Chachi Renou", "Chachi Renou & Mom Orange"))
        assertEquals("Chachi Renou & Mom Orange", rec.latestCallName)
        assertEquals("Chachi Renou & Mom Orange", rec.displayName)
    }

    @Test fun legacyRecordingsStillDisplayTheirSavedOriginalName() {
        val rec = recording()
        assertEquals("Chachi Renou", rec.latestCallName)
        assertEquals("Chachi Renou", rec.displayName)
    }

    @Test fun noNameNeverCreatesAnImaginaryContact() {
        val rec = recording(legacy = null)
        assertNull(rec.latestCallName)
        assertEquals("WhatsApp", rec.displayName)
    }
}
