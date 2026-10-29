package com.vrec.app.experimental.adb

import com.vrec.app.experimental.daemon.AdbHelperStartFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory end-to-end journeys: no device, ADB daemon or Android runtime.
 * Uses the same decision and ordered-start functions that production calls.
 * These supplement, but do not replace, an Android pairing/capture device test.
 */
class AdbSetupJourneySimulationTest {
    private data class Phone(
        var helperReady: Boolean = false,
        var tcpListener: Boolean = false,
        var uid: Int? = null,
        var paired: Boolean = false,
        var pairingFound: Boolean = false,
        var wirelessFound: Boolean = false,
        var tcpEnabled: Boolean = true,
        var manualRetryRequired: Boolean = false,
    ) {
        fun next(): AdbOnboardingPolicy.Step =
            AdbOnboardingPolicy.next(AdbOnboardingPolicy.Signals(
                helperHealthy = helperReady,
                tcpListening = tcpListener,
                shellUid = uid,
                paired = paired,
                pairingPortFound = pairingFound,
                wirelessPortFound = wirelessFound,
                tcpEnabled = tcpEnabled,
                awaitingManualRetry = manualRetryRequired,
            ))
    }

    private class Startup(private val phone: Phone) : AdbHelperStartFlow.Steps {
        val actions = mutableListOf<String>()
        var unavailable: List<String> = emptyList()
        var startFails = false

        override suspend fun verifyUid2000(): Boolean {
            actions += "uid"
            return phone.uid == 2000
        }
        override suspend fun configurePermissions(): List<String> {
            actions += "permissions"
            return unavailable
        }
        override suspend fun ensureHelperStarted(): Result<Unit> {
            actions += "helper start"
            return if (startFails) Result.failure(IllegalStateException("helper cannot launch"))
                else Result.success(Unit)
        }
        override suspend fun probeHelper(): Boolean {
            actions += "authenticated probe"
            return !startFails
        }
    }

    @Test fun firstTimePairingToTcpToHelperIncludesPermissionsAndSurvivesNavigation() = runBlocking {
        val phone = Phone()
        assertEquals(AdbOnboardingPolicy.Step.DISCOVER_WIRELESS, phone.next())
        phone.pairingFound = true
        assertEquals(AdbOnboardingPolicy.Step.WAIT_FOR_PAIRING_CODE, phone.next())
        // Android code is supplied once; the service publishes its connect port.
        phone.pairingFound = false
        phone.paired = true
        phone.wirelessFound = true
        assertEquals(AdbOnboardingPolicy.Step.CONNECT_PAIRED_WIRELESS, phone.next())
        phone.uid = 2000
        assertEquals(AdbOnboardingPolicy.Step.SWITCH_TO_TCP, phone.next())
        // adbd restarts: the TLS session disappears before the TCP listener opens.
        phone.uid = null
        phone.tcpListener = true
        assertEquals(AdbOnboardingPolicy.Step.USE_EXISTING_TCP, phone.next())
        phone.uid = 2000 // authenticated TCP reconnect, not just an open port
        assertEquals(AdbOnboardingPolicy.Step.START_HELPER, phone.next())
        val startup = Startup(phone).apply {
            unavailable = listOf("READ_CALL_LOG requires Android approval")
        }
        assertTrue(AdbHelperStartFlow.run(startup) is AdbHelperStartFlow.Outcome.Ready)
        assertEquals(listOf("uid", "permissions", "helper start", "authenticated probe"),
            startup.actions)
        phone.helperReady = true
        // Home -> Settings -> Recordings -> Home cannot start anything again.
        repeat(4) { assertEquals(AdbOnboardingPolicy.Step.READY, phone.next()) }
        assertEquals(4, startup.actions.size)
    }

    @Test fun pairedRecoveryDoesNotRepeatPairingOrAttemptUnauthorizedStart() = runBlocking {
        val phone = Phone(paired = true, wirelessFound = true, tcpListener = false)
        assertEquals(AdbOnboardingPolicy.Step.CONNECT_PAIRED_WIRELESS, phone.next())
        // If the restored shell is not authorized, privileged grants/launch never run.
        val startup = Startup(phone)
        assertTrue(AdbHelperStartFlow.run(startup) is AdbHelperStartFlow.Outcome.Failed)
        assertEquals(listOf("uid"), startup.actions)
        phone.manualRetryRequired = true
        assertEquals(AdbOnboardingPolicy.Step.MANUAL_RETRY, phone.next())
        phone.manualRetryRequired = false // explicit user Retry
        phone.uid = 2000
        assertEquals(AdbOnboardingPolicy.Step.SWITCH_TO_TCP, phone.next())
    }

    @Test fun existingTcpListenerMustStillReverifyUidBeforeStartingHelper() = runBlocking {
        val phone = Phone(tcpListener = true)
        assertEquals(AdbOnboardingPolicy.Step.USE_EXISTING_TCP, phone.next())
        val startup = Startup(phone)
        assertTrue(AdbHelperStartFlow.run(startup) is AdbHelperStartFlow.Outcome.Failed)
        assertEquals(listOf("uid"), startup.actions)
        phone.uid = 2000
        assertEquals(AdbOnboardingPolicy.Step.START_HELPER, phone.next())
        assertTrue(AdbHelperStartFlow.run(startup) is AdbHelperStartFlow.Outcome.Ready)
    }

    @Test fun tcpOptOutPreservesAuthorizedWirelessSession() = runBlocking {
        val phone = Phone(uid = 2000, paired = true, wirelessFound = true, tcpEnabled = false)
        assertEquals(AdbOnboardingPolicy.Step.START_HELPER, phone.next())
        val startup = Startup(phone)
        assertTrue(AdbHelperStartFlow.run(startup) is AdbHelperStartFlow.Outcome.Ready)
    }
}
