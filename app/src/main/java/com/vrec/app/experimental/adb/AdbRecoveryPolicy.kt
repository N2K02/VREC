package com.vrec.app.experimental.adb

/** Only retry after shell UID 2000 was verified via USB-bootstrapped localhost ADB. */
internal object AdbRecoveryPolicy {
    data class Conditions(
        val adbMethodSelected: Boolean,
        val recordingEnabled: Boolean,
        val autoRecoveryAllowed: Boolean,
        val connectionBusy: Boolean,
        val adbIdentityPresent: Boolean,
        val tcpVerifiedBefore: Boolean,
        val tcpPortListening: Boolean,
    )
    fun shouldRetry(c: Conditions): Boolean =
        c.adbMethodSelected && c.recordingEnabled && c.autoRecoveryAllowed &&
        !c.connectionBusy && c.adbIdentityPresent && c.tcpVerifiedBefore &&
        c.tcpPortListening
}
