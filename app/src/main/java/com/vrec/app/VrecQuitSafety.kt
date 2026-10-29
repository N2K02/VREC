package com.vrec.app

import com.vrec.app.recording.RecordingService

/** Fail-closed shutdown. Never disconnect the shell while recording or importing. */
internal object VrecQuitSafety {
    fun idle(state: RecordingService.State, shellActive: Boolean, callTracked: Boolean): Boolean =
        state is RecordingService.State.Idle && !shellActive && !callTracked

    /** Stages are ordered; failure short-circuits and preserves the ADB connection. */
    suspend fun sequence(
        stopCapture: suspend () -> Boolean,
        importCompleted: suspend () -> Boolean,
        quitHelper: suspend () -> Boolean,
    ): Boolean = stopCapture() && importCompleted() && quitHelper()
}
