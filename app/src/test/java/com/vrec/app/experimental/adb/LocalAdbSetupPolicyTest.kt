package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAdbSetupPolicyTest {
    private fun usbOnly() = LocalAdbSetupPolicy.Signals(
        adbModeSelected = true, helperHealthy = false, verifiedAdbSession = false,
        usbDebugging = true, tcpPortListening = false,
    )

    @Test fun usbDebuggingAloneCannotAuthorizeVrec() {
        assertEquals(LocalAdbSetupPolicy.Next.NEED_USB_HOST, LocalAdbSetupPolicy.next(usbOnly()))
    }

    @Test fun discoveredTcpStillRequiresActualUidAuthorization() {
        assertEquals(LocalAdbSetupPolicy.Next.CONNECT_TCP,
            LocalAdbSetupPolicy.next(usbOnly().copy(tcpPortListening = true)))
    }

    @Test fun existingHelperAndVerifiedSessionArePrioritized() {
        assertEquals(LocalAdbSetupPolicy.Next.READY,
            LocalAdbSetupPolicy.next(usbOnly().copy(helperHealthy = true)))
        assertEquals(LocalAdbSetupPolicy.Next.USE_VERIFIED_SESSION,
            LocalAdbSetupPolicy.next(usbOnly().copy(verifiedAdbSession = true)))
    }

    @Test fun missingPermissionsAndModeAreHandled() {
        assertEquals(LocalAdbSetupPolicy.Next.SELECT_ADB_MODE,
            LocalAdbSetupPolicy.next(usbOnly().copy(adbModeSelected = false)))
        assertEquals(LocalAdbSetupPolicy.Next.ENABLE_USB_DEBUGGING,
            LocalAdbSetupPolicy.next(usbOnly().copy(usbDebugging = false)))
    }
}
