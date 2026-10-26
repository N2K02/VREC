package com.vrec.app.experimental.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRecoveryPolicyTest {
    private fun eligible() = AdbRecoveryPolicy.Conditions(
        adbMethodSelected = true, recordingEnabled = true,
        autoRecoveryAllowed = true, connectionBusy = false,
        adbIdentityPresent = true, tcpVerifiedBefore = true,
        tcpPortListening = true,
    )

    @Test fun restoresOnlyAlreadyAuthorizedUsbBootstrappedTcp() {
        assertTrue(AdbRecoveryPolicy.shouldRetry(eligible()))
    }

    @Test fun usbDebuggingAndOpenPortAloneAreNeverEnough() {
        assertFalse(AdbRecoveryPolicy.shouldRetry(eligible().copy(tcpVerifiedBefore = false)))
        assertFalse(AdbRecoveryPolicy.shouldRetry(eligible().copy(adbIdentityPresent = false)))
    }

    @Test fun disconnectStandardModeHomeOffAndBusyAlwaysBlockRecovery() {
        listOf(
            eligible().copy(autoRecoveryAllowed = false),
            eligible().copy(adbMethodSelected = false),
            eligible().copy(recordingEnabled = false),
            eligible().copy(connectionBusy = true),
            eligible().copy(tcpPortListening = false),
        ).forEach { assertFalse(AdbRecoveryPolicy.shouldRetry(it)) }
    }
}
