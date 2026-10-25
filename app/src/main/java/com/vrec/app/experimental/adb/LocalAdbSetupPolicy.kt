package com.vrec.app.experimental.adb

/** USB-only setup decisions; port availability is not authentication. */
internal object LocalAdbSetupPolicy {
    enum class Next { SELECT_ADB_MODE, READY, USE_VERIFIED_SESSION, CONNECT_TCP, ENABLE_USB_DEBUGGING, NEED_USB_HOST }
    data class Signals(
        val adbModeSelected: Boolean,
        val helperHealthy: Boolean,
        val verifiedAdbSession: Boolean,
        val usbDebugging: Boolean,
        val tcpPortListening: Boolean,
    )
    fun next(s: Signals): Next = when {
        !s.adbModeSelected -> Next.SELECT_ADB_MODE
        s.helperHealthy -> Next.READY
        s.verifiedAdbSession -> Next.USE_VERIFIED_SESSION
        s.tcpPortListening -> Next.CONNECT_TCP
        !s.usbDebugging -> Next.ENABLE_USB_DEBUGGING
        else -> Next.NEED_USB_HOST
    }
}
