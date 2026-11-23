package com.vrec.app.experimental.adb

/**
 * Decides when VRec may run an ADB transport action that can restart adbd.
 *
 * Promoting Wireless debugging (TLS) to TCP/5555 sends `tcpip:5555` when no
 * listener exists yet. adbd then restarts, and Android init kills every
 * process in adbd's cgroup, including the detached recording helper that VRec
 * launched over that ADB shell. The helper must never be the price of a
 * transport upgrade: once it runs, VRec keeps the transport it already has.
 */
internal object AdbdRestartGuard {
    data class Signals(
        val quitting: Boolean,
        val liveCall: Boolean,
        val enhancedCaptureActive: Boolean,
        /** Cached ready flag OR a live authenticated probe of the helper. */
        val helperAlive: Boolean,
    )

    fun mayPromoteToTcp(s: Signals): Boolean =
        !s.quitting && !s.liveCall && !s.enhancedCaptureActive && !s.helperAlive

    /**
     * A user Retry on an already verified session has nothing to reconnect;
     * calling the core's retry() would only re-run TLS->TCP promotion.
     */
    fun mayRetryVerifiedSession(sessionVerified: Boolean, helperAlive: Boolean): Boolean =
        !(sessionVerified && helperAlive)
}
