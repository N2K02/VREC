package com.vrec.app.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallMetadataJournalTest {
    @Test fun matchesCloseShellAndTelecomStarts() {
        assertTrue(ShellCallMetadataJournal.matches(100_000L, 108_000L, null, "com.whatsapp"))
    }

    @Test fun rejectsOldOrFutureCallMetadata() {
        assertFalse(ShellCallMetadataJournal.matches(100_000L, 195_000L, null, "com.whatsapp"))
        assertFalse(ShellCallMetadataJournal.matches(200_000L, 100_000L, null, "com.whatsapp"))
    }

    @Test fun rejectsDifferentVoipApp() {
        assertFalse(ShellCallMetadataJournal.matches(
            100_000L, 108_000L, "com.whatsapp", "org.telegram.messenger"))
    }

    @Test fun allowsLegacyShellFilenameWithoutPackage() {
        assertTrue(ShellCallMetadataJournal.matches(100_000L, 108_000L, null, "com.whatsapp"))
    }
}
