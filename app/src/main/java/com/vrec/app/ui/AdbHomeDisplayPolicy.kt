package com.vrec.app.ui

/** Pure presentation policy: never hide recovery actions when the helper is unhealthy. */
internal object AdbHomeDisplayPolicy {
    fun compact(recordingActive: Boolean, helperHealthy: Boolean): Boolean =
        recordingActive && helperHealthy

    fun subtitle(standardRecording: Boolean): String =
        if (standardRecording) "Standard recording active · helper available"
        else "Enhanced recording active · helper healthy"
}
