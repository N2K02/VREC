package com.vrec.app.experimental.adb

/** A readable TCP port is merely a candidate, never authorization. */
internal object LocalAdbSetupPolicy {
    enum class Next { SELECT_ADB_MODE, READY, USE_VERIFIED_SESSION, CONNECT_TCP, NEED_USB_HOST }
    data class Signals(
        val adbModeSelected: Boolean,
        val helperHealthy: Boolean,
        val verifiedAdbSession: Boolean,
        val tcpPortListening: Boolean,
    )
    fun next(s: Signals): Next = when {
        !s.adbModeSelected -> Next.SELECT_ADB_MODE
        s.helperHealthy -> Next.READY
        s.verifiedAdbSession -> Next.USE_VERIFIED_SESSION
        s.tcpPortListening -> Next.CONNECT_TCP
        else -> Next.NEED_USB_HOST
    }
}
