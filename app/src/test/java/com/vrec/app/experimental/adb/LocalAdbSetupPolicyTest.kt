package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAdbSetupPolicyTest {
    private fun usbOnly() = LocalAdbSetupPolicy.Signals(
        adbModeSelected = true, helperHealthy = false, verifiedAdbSession = false,
        usbDebugging = true, wirelessDebugging = false, pairedIdentity = false,
        tcpPortListening = false,
    )

    @Test fun usbOnlyHuaweiDoesNotPretendToBeAuthorized() {
        assertEquals(LocalAdbSetupPolicy.Next.NEED_USB_HOST, LocalAdbSetupPolicy.next(usbOnly()))
    }

    @Test fun tcpListenerCanBeTriedButNotPresumedAuthorized() {
        assertEquals(LocalAdbSetupPolicy.Next.CONNECT_TCP,
            LocalAdbSetupPolicy.next(usbOnly().copy(tcpPortListening = true)))
    }

    @Test fun wirelessRequiresPairingBeforeAutomaticConnection() {
        assertEquals(LocalAdbSetupPolicy.Next.PAIR_WIRELESS,
            LocalAdbSetupPolicy.next(usbOnly().copy(wirelessDebugging = true)))
        assertEquals(LocalAdbSetupPolicy.Next.CONNECT_WIRELESS,
            LocalAdbSetupPolicy.next(usbOnly().copy(wirelessDebugging = true, pairedIdentity = true)))
    }

    @Test fun runningHelperNeedsNoNewAdb() {
        assertEquals(LocalAdbSetupPolicy.Next.READY,
            LocalAdbSetupPolicy.next(usbOnly().copy(helperHealthy = true)))
        assertEquals(LocalAdbSetupPolicy.Next.USE_VERIFIED_SESSION,
            LocalAdbSetupPolicy.next(usbOnly().copy(verifiedAdbSession = true)))
    }

    @Test fun selectionAndDeveloperOptionsAreNotBypassed() {
        assertEquals(LocalAdbSetupPolicy.Next.SELECT_ADB_MODE,
            LocalAdbSetupPolicy.next(usbOnly().copy(adbModeSelected = false)))
        assertEquals(LocalAdbSetupPolicy.Next.ENABLE_DEBUGGING,
            LocalAdbSetupPolicy.next(usbOnly().copy(usbDebugging = false)))
    }
}
