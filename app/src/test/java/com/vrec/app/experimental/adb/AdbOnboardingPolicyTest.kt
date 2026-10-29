package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbOnboardingPolicyTest {
    private fun state(
        healthy: Boolean = false, tcp: Boolean = false, uid: Int? = null,
        paired: Boolean = false, pairing: Boolean = false, wireless: Boolean = false,
        tcpEnabled: Boolean = false, retry: Boolean = false,
    ) = AdbOnboardingPolicy.Signals(healthy, tcp, uid, paired, pairing, wireless, tcpEnabled, retry)

    @Test fun healthyDetachedHelperNeverReattachesBecauseOfNavigation() {
        assertEquals(AdbOnboardingPolicy.Step.READY, AdbOnboardingPolicy.next(state(healthy = true)))
    }

    @Test fun firstTimeNeedsDiscoveryAndPairingCode() {
        assertEquals(AdbOnboardingPolicy.Step.DISCOVER_WIRELESS, AdbOnboardingPolicy.next(state()))
        assertEquals(AdbOnboardingPolicy.Step.WAIT_FOR_PAIRING_CODE,
            AdbOnboardingPolicy.next(state(pairing = true)))
    }

    @Test fun pairedDeviceReconnectsWithoutAnotherCode() {
        assertEquals(AdbOnboardingPolicy.Step.CONNECT_PAIRED_WIRELESS,
            AdbOnboardingPolicy.next(state(paired = true, wireless = true)))
    }

    @Test fun verifiedShellSwitchesWhenTcpModeIsEnabled() {
        assertEquals(AdbOnboardingPolicy.Step.SWITCH_TO_TCP,
            AdbOnboardingPolicy.next(state(uid = 2000, tcpEnabled = true)))
        assertEquals(AdbOnboardingPolicy.Step.START_HELPER,
            AdbOnboardingPolicy.next(state(uid = 2000)))
    }

    @Test fun tcpRepairAndManualRetryAreExplicit() {
        assertEquals(AdbOnboardingPolicy.Step.USE_EXISTING_TCP,
            AdbOnboardingPolicy.next(state(tcp = true)))
        assertEquals(AdbOnboardingPolicy.Step.MANUAL_RETRY,
            AdbOnboardingPolicy.next(state(retry = true, wireless = true, paired = true)))
    }
}
