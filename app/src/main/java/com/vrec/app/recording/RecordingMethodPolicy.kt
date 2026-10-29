package com.vrec.app.recording

/** Prevent changing capture engines during an active app or shell session. */
internal object RecordingMethodPolicy {
    fun canChange(appRecorderIdle: Boolean, shellRecordingActive: Boolean): Boolean =
        appRecorderIdle && !shellRecordingActive
}
