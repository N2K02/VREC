package com.vrec.app

/**
 * Home's display-only summary. It never controls capture or ADB recovery:
 * the actual service, preferences and helper states remain authoritative.
 */
internal object HomeDashboardPresentation {
    enum class State { RECORDING, PAUSED, OFF, READY, SETUP_NEEDED }

    fun resolve(enabled: Boolean, ready: Boolean, recording: Boolean, paused: Boolean): State =
        when {
            recording -> State.RECORDING
            paused -> State.PAUSED
            !enabled -> State.OFF
            ready -> State.READY
            else -> State.SETUP_NEEDED
        }

    fun title(state: State): String = when (state) {
        State.RECORDING -> "Recording in progress"
        State.PAUSED -> "Recording paused"
        State.OFF -> "Recording is off"
        State.READY -> "Ready for your next call"
        State.SETUP_NEEDED -> "Recording setup needed"
    }

    fun detail(state: State): String = when (state) {
        State.RECORDING -> "Capture is active. Recording controls remain available below."
        State.PAUSED -> "The current recording is paused."
        State.OFF -> "Enable automatic recording below when you need it."
        State.READY -> "Automatic recording is enabled and the shell helper is ready."
        State.SETUP_NEEDED -> "The shell helper needs attention before new calls can be captured."
    }
}
