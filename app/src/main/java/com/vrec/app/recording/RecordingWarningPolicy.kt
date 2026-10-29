package com.vrec.app.recording

/**
 * Pure warning lifecycle rules so a recovered helper clears only its own
 * warning, without erasing an unrelated failed recording or import notice.
 */
internal object RecordingWarningPolicy {
    const val HELPER_START_FAILURE =
        "Recording helper could not start after ADB authorization. Calls will not be captured until recovery succeeds."

    fun clearAfterHelperRecovered(currentWarning: String?): Boolean =
        currentWarning == HELPER_START_FAILURE

    fun clearAfterCaptureStarted(currentWarning: String?, persistent: Boolean): Boolean =
        currentWarning != null && !persistent
}
