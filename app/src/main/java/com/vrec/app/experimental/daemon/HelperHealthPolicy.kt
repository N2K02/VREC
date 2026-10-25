package com.vrec.app.experimental.daemon

/** Pure state transition: a PONG is not evidence that an older helper supports the current mode. */
internal object HelperHealthPolicy {
    fun nextState(probeVerified: Boolean, modeVerified: Boolean): AdbPrivilegedApiHelper.State =
        when {
            !probeVerified -> AdbPrivilegedApiHelper.State.NEEDS_ADB
            !modeVerified -> AdbPrivilegedApiHelper.State.ERROR
            else -> AdbPrivilegedApiHelper.State.RUNNING
        }
}
