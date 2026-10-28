package com.vrec.app.recording

/**
 * A live shell operation is permitted only in its matching state. Status comes
 * from authenticated shell events, never from UI button presses.
 */
internal object CaptureControlPolicy {
    fun isAllowed(action: Char, active: Boolean, paused: Boolean): Boolean =
        when (action) {
            'P' -> active && !paused
            'R' -> active && paused
            'S' -> active
            else -> false
        }
}
