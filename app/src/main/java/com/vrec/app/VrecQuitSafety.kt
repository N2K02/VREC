package com.vrec.app

import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.HelperPresence
import com.vrec.app.recording.RecordingService

/** Fail-closed shutdown. Never disconnect the shell while recording or importing. */
internal object VrecQuitSafety {
    fun idle(state: RecordingService.State, shellActive: Boolean, callTracked: Boolean): Boolean =
        state is RecordingService.State.Idle && !shellActive && !callTracked

    /** Only a refused loopback connection confirms the recorder is absent. */
    fun canQuitWithoutHelper(presence: HelperPresence): Boolean =
        presence == HelperPresence.ABSENT

    /** Stages are ordered; failure short-circuits and preserves the ADB connection. */
    suspend fun sequence(
        stopCapture: suspend () -> Boolean,
        importCompleted: suspend () -> Boolean,
        quitHelper: suspend () -> Boolean,
    ): Boolean = stopCapture() && importCompleted() && quitHelper()
}
