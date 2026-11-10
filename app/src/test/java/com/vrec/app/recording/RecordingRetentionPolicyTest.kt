package com.vrec.app.recording

import org.junit.Assert.*
import org.junit.Test

class RecordingRetentionPolicyTest {
    private val now = 500L * RecordingRetentionPolicy.DAY_MS
    @Test fun savedCallsNeverDeleteByDefault() {
        assertFalse(RecordingRetentionPolicy.savedEligible(now - 400L * RecordingRetentionPolicy.DAY_MS,
            false, 0, now))
    }
    @Test fun favoritesAlwaysProtected() {
        assertFalse(RecordingRetentionPolicy.savedEligible(now - 400L * RecordingRetentionPolicy.DAY_MS,
            true, 30, now))
    }
    @Test fun onlyOldSavedCallsMoveToTrash() {
        assertTrue(RecordingRetentionPolicy.savedEligible(now - 40L * RecordingRetentionPolicy.DAY_MS,
            false, 30, now))
        assertFalse(RecordingRetentionPolicy.savedEligible(now - 10L * RecordingRetentionPolicy.DAY_MS,
            false, 30, now))
    }
    @Test fun trashExpiresOnlyAfterConfiguredDays() {
        assertTrue(RecordingRetentionPolicy.trashExpired(now - 31L * RecordingRetentionPolicy.DAY_MS, 30, now))
        assertFalse(RecordingRetentionPolicy.trashExpired(now - 29L * RecordingRetentionPolicy.DAY_MS, 30, now))
    }
}
