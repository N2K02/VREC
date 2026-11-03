package com.vrec.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ThirdPartyRecordingIdentityTest {
    private fun recording() = Recording(File("call.ogg"), 1L, CallDirection.VOIP, "Shrey", null,
        false, 60_000L, contactName = "Shreya", originalCallerName = "Shrey et titi",
        phoneNumbers = listOf("51234567", "59876543"))

    @Test fun originalAppTitleWinsOverContactsAndSurvivesDetailsEdits() {
        val rec = recording()
        assertEquals("Shrey et titi", rec.displayName)
        val edited = rec.copy(notes = "Group call", favorite = true, contactName = "Different contact")
        assertEquals(rec.originalCallerName, edited.displayName)
        assertEquals(rec.phoneNumbers, edited.phoneNumbers)
    }

    @Test fun legacyRecordingsKeepTheirContactName() {
        assertEquals("Shreya", recording().copy(originalCallerName = null, phoneNumbers = emptyList()).displayName)
    }
}
