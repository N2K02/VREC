package com.vrec.app.experimental.adb

/**
 * Pure decision logic for VRec's one-tap setup. A local shell, Termux-like
 * command runner, or an open TCP socket cannot authorize Android's adbd.
 */
internal object LocalAdbSetupPolicy {
    enum class Next {
        SELECT_ADB_MODE,
        READY,
        USE_VERIFIED_SESSION,
        CONNECT_TCP,
        CONNECT_WIRELESS,
        PAIR_WIRELESS,
        ENABLE_DEBUGGING,
        NEED_USB_HOST,
    }

    data class Signals(
        val adbModeSelected: Boolean,
        val helperHealthy: Boolean,
        val verifiedAdbSession: Boolean,
        val usbDebugging: Boolean,
        val wirelessDebugging: Boolean,
        val pairedIdentity: Boolean,
        val tcpPortListening: Boolean,
    )

    fun next(s: Signals): Next = when {
        !s.adbModeSelected -> Next.SELECT_ADB_MODE
        s.helperHealthy -> Next.READY
        s.verifiedAdbSession -> Next.USE_VERIFIED_SESSION
        s.usbDebugging && s.tcpPortListening -> Next.CONNECT_TCP
        s.wirelessDebugging && s.pairedIdentity -> Next.CONNECT_WIRELESS
        s.wirelessDebugging -> Next.PAIR_WIRELESS
        s.usbDebugging -> Next.NEED_USB_HOST
        else -> Next.ENABLE_DEBUGGING
    }
}
