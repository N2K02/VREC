package com.vrec.app.experimental.daemon

import org.junit.Assert.*
import org.junit.Test

class ImportBatchPolicyTest {
    @Test fun silentRetentionOnlyDiscardsStrongHealthEvidenceWhenEnabled() {
        assertTrue(ImportBatchPolicy.shouldDiscardSilent(true, "BOTH_CHANNELS_QUIET"))
        assertTrue(ImportBatchPolicy.shouldDiscardSilent(true, "NO_AUDIO"))
        assertFalse(ImportBatchPolicy.shouldDiscardSilent(false, "BOTH_CHANNELS_QUIET"))
        assertFalse(ImportBatchPolicy.shouldDiscardSilent(true, null))
        assertFalse(ImportBatchPolicy.shouldDiscardSilent(true, "ONE_CHANNEL_QUIET"))
        assertFalse(ImportBatchPolicy.shouldDiscardSilent(true, "CLIPPING"))
    }

    @Test fun oneCorruptRecordingDoesNotHideLaterSuccessfulImports() {
        assertFalse(ImportBatchPolicy.shouldFailBatch(1, 1))
        assertTrue(ImportBatchPolicy.message(1, 1).contains("1 failed"))
        assertTrue(ImportBatchPolicy.message(1, 1).contains("kept"))
    }

    @Test fun allCorruptFilesReportFailureAndKeepOriginals() {
        assertTrue(ImportBatchPolicy.shouldFailBatch(0, 2))
        assertTrue(ImportBatchPolicy.message(0, 2).contains("originals kept"))
    }

    @Test fun successfulAndEmptyCollectionsAreNotFailures() {
        assertFalse(ImportBatchPolicy.shouldFailBatch(3, 0))
        assertFalse(ImportBatchPolicy.shouldFailBatch(0, 0))
        assertEquals("Imported 3 enhanced recordings.", ImportBatchPolicy.message(3, 0))
    }
}
