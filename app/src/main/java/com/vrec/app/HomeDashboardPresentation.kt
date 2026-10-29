package com.vrec.app

/**
 * Home's display-only summary. It never controls capture or ADB recovery:
 * the actual service, preferences and helper states remain authoritative.
 */
internal object HomeDashboardPresentation {
    enum class State { RECORDING, PAUSED, STARTING, SAVING, OFF, READY, SETUP_NEEDED }

    fun resolve(
        enabled: Boolean,
        ready: Boolean,
        recording: Boolean,
        paused: Boolean,
        starting: Boolean = false,
        saving: Boolean = false,
    ): State =
        when {
            recording -> State.RECORDING
            paused -> State.PAUSED
            saving -> State.SAVING
            starting -> State.STARTING
            !enabled -> State.OFF
            ready -> State.READY
            else -> State.SETUP_NEEDED
        }

    fun title(state: State): String = when (state) {
        State.RECORDING -> "Recording in progress"
        State.PAUSED -> "Recording paused"
        State.STARTING -> "Preparing recording"
        State.SAVING -> "Saving recording"
        State.OFF -> "Recording is off"
        State.READY -> "Ready for your next call"
        State.SETUP_NEEDED -> "Recording setup needed"
    }

    fun detail(state: State, automatic: Boolean = true): String = when (state) {
        State.RECORDING -> "Capture is active. Recording controls remain available below."
        State.PAUSED -> "The current recording is paused."
        State.STARTING -> "Waiting for the capture service to confirm recording."
        State.SAVING -> "Finishing the recording and saving its audio."
        State.OFF -> "Enable recording below when you need it."
        State.READY -> if (automatic)
            "Automatic recording is enabled and the shell helper is ready."
        else "Manual recording is available for a connected call."
        State.SETUP_NEEDED -> "The shell helper needs attention before new calls can be captured."
    }
}
