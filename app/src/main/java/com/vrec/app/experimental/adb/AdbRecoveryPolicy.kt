package com.vrec.app.experimental.adb

/**
 * Pure decision logic for a previously authorized ADB helper recovery.
 * An open TCP port is not proof of authorization; connect() must still verify UID 2000.
 */
internal object AdbRecoveryPolicy {
    enum class Endpoint { WIRELESS, TCP }

    data class Conditions(
        val adbMethodSelected: Boolean,
        val recordingEnabled: Boolean,
        val autoRecoveryAllowed: Boolean,
        val connectionBusy: Boolean,
        val pairedIdentityPresent: Boolean,
        val wirelessVerifiedBefore: Boolean,
        val tcpVerifiedBefore: Boolean,
        val wirelessDebuggingEnabled: Boolean,
        val usbDebuggingEnabled: Boolean,
        val tcpPortListening: Boolean,
        val preferred: Endpoint,
    )

    fun availableEndpoints(c: Conditions): List<Endpoint> {
        if (!c.adbMethodSelected || !c.recordingEnabled || !c.autoRecoveryAllowed ||
            c.connectionBusy || !c.pairedIdentityPresent) return emptyList()

        val wireless = c.wirelessVerifiedBefore && c.wirelessDebuggingEnabled
        val tcp = c.tcpVerifiedBefore && c.usbDebuggingEnabled && c.tcpPortListening
        val alternate = if (c.preferred == Endpoint.WIRELESS) Endpoint.TCP else Endpoint.WIRELESS
        return listOf(c.preferred, alternate).filter {
            when (it) {
                Endpoint.WIRELESS -> wireless
                Endpoint.TCP -> tcp
            }
        }
    }
}
