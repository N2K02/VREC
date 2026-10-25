package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbRecoveryPolicyTest {
    private fun allowed() = AdbRecoveryPolicy.Conditions(
        adbMethodSelected = true,
        recordingEnabled = true,
        autoRecoveryAllowed = true,
        connectionBusy = false,
        pairedIdentityPresent = true,
        wirelessVerifiedBefore = true,
        tcpVerifiedBefore = true,
        wirelessDebuggingEnabled = true,
        usbDebuggingEnabled = true,
        tcpPortListening = true,
        preferred = AdbRecoveryPolicy.Endpoint.WIRELESS,
    )

    @Test fun prefersVerifiedWireless() {
        assertEquals(listOf(AdbRecoveryPolicy.Endpoint.WIRELESS, AdbRecoveryPolicy.Endpoint.TCP),
            AdbRecoveryPolicy.availableEndpoints(allowed()))
    }

    @Test fun recoversUsingTcpIfWirelessUnavailable() {
        assertEquals(listOf(AdbRecoveryPolicy.Endpoint.TCP),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(wirelessDebuggingEnabled = false)))
    }

    @Test fun neverBootstrapsFromTcpSocketAlone() {
        assertEquals(emptyList<AdbRecoveryPolicy.Endpoint>(),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(
                tcpVerifiedBefore = false, wirelessDebuggingEnabled = false)))
    }

    @Test fun usbSwitchAloneIsNotEnoughWithoutListener() {
        assertEquals(emptyList<AdbRecoveryPolicy.Endpoint>(),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(
                wirelessDebuggingEnabled = false, tcpPortListening = false)))
    }

    @Test fun requiresSavedPairedIdentity() {
        assertEquals(emptyList<AdbRecoveryPolicy.Endpoint>(),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(pairedIdentityPresent = false)))
    }

    @Test fun honorsDisconnectAndStandardModeAndHomeOff() {
        for (conditions in listOf(
            allowed().copy(autoRecoveryAllowed = false),
            allowed().copy(adbMethodSelected = false),
            allowed().copy(recordingEnabled = false),
            allowed().copy(connectionBusy = true),
        )) {
            assertEquals(emptyList<AdbRecoveryPolicy.Endpoint>(),
                AdbRecoveryPolicy.availableEndpoints(conditions))
        }
    }

    @Test fun onlyPreviouslyVerifiedWirelessMayBeUsed() {
        assertEquals(emptyList<AdbRecoveryPolicy.Endpoint>(),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(
                wirelessVerifiedBefore = false, tcpVerifiedBefore = false)))
    }

    @Test fun prefersTcpWhenLastVerifiedAndFallsBackToWireless() {
        assertEquals(listOf(AdbRecoveryPolicy.Endpoint.TCP, AdbRecoveryPolicy.Endpoint.WIRELESS),
            AdbRecoveryPolicy.availableEndpoints(allowed().copy(
                preferred = AdbRecoveryPolicy.Endpoint.TCP)))
    }
}
