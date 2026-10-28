package com.vrec.app

import com.vrec.app.recording.RecordingService

/**
 * Display-only transport policy. Commands still go through the authenticated
 * shell receiver, not RecordingService's legacy microphone compatibility facade.
 */
internal object RecordingTransportPresentation {
    fun canPauseOrResume(state: RecordingService.State, shellActive: Boolean): Boolean =
        shellActive && (state is RecordingService.State.Recording ||
            state is RecordingService.State.Paused)

    fun canStop(state: RecordingService.State, shellActive: Boolean): Boolean =
        canPauseOrResume(state, shellActive)

    fun canChangeMasterToggle(
        state: RecordingService.State,
        shellActive: Boolean,
    ): Boolean = !shellActive && state is RecordingService.State.Idle

    fun elapsedSegment(startedAtMs: Long?, nowMs: Long, pausedMs: Long?): Long? =
        pausedMs ?: startedAtMs?.let { (nowMs - it).coerceAtLeast(0L) }

    fun durationLabel(elapsedMs: Long?): String {
        if (elapsedMs == null) return "--:--"
        val seconds = elapsedMs / 1000L
        val hours = seconds / 3600L
        return if (hours > 0L) "%02d:%02d:%02d".format(
            hours, (seconds % 3600L) / 60L, seconds % 60L)
        else "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }
}
