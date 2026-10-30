package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executable decisions and cross-component regression guards for ADB recovery. */
class AdbRecoveryExtensionsTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun cachedTlsPortIsOnlyUsedWithinSameKnownBoot() {
        assertEquals(43210, AdbUserChoices.reusablePort(43210, 11, 11))
        assertNull(AdbUserChoices.reusablePort(43210, 11, 12))
        assertNull(AdbUserChoices.reusablePort(43210, -1, 12))
        assertNull(AdbUserChoices.reusablePort(43210, 11, -1))
        assertNull(AdbUserChoices.reusablePort(0, 11, 11))
        assertNull(AdbUserChoices.reusablePort(65536, 11, 11))
    }

    @Test fun stalePortRetriesOnlyFreshVerifiedLocalEndpointCandidates() {
        assertEquals(43211, WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210), AdbFailure.CONNECTION_LOST))
        assertEquals(43212, WirelessPortRetryPolicy.freshPort(
            43211, 43212, setOf(43210, 43211), AdbFailure.NETWORK_UNAVAILABLE))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43210, setOf(43210), AdbFailure.CONNECTION_LOST))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210, 43211), AdbFailure.CONNECTION_LOST))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210), AdbFailure.AUTH_REJECTED))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210), AdbFailure.AUTH_TIMEOUT))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210), AdbFailure.PERMISSION_DENIED))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 43211, setOf(43210, 43211, 43212),
            AdbFailure.CONNECTION_LOST))
        assertNull(WirelessPortRetryPolicy.freshPort(
            43210, 65536, setOf(43210), AdbFailure.CONNECTION_LOST))
    }

    @Test fun discoveryKeepsWatchingUntilAuthorizedTransportSucceeds() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val controller = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(wireless.contains("AdbUserChoices.lastVerifiedPort(app)"))
        assertTrue(wireless.contains("AdbUserChoices.clearVerifiedPort(app, port)"))
        assertTrue(wireless.contains("WirelessPortRetryPolicy.freshPort("))
        assertTrue(wireless.contains("session == searchSession && attached"))
        assertTrue(wireless.contains("if (!belongsToThisPhone(host)"))
        assertTrue(controller.contains("AdbUserChoices.rememberVerifiedPort(context, port)"))
        assertTrue(controller.indexOf("verifyShellUid()") <
            controller.indexOf("AdbUserChoices.rememberVerifiedPort(context, port)"))
    }

    @Test fun tcpSwitchTreatsStreamResetAsUncertainUntilNewUidIsVerified() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val body = wireless.substringAfter("private suspend fun switchToLocalTcp(")
            .substringBefore("fun promoteVerifiedShellToTcp(")
        assertTrue(body.contains("val switched = AdbTransport.switchToTcpip(5555)"))
        assertTrue(body.contains("if (switched.isFailure)"))
        assertTrue(body.contains("listening = ExperimentalRecording.tcpPortListening()"))
        assertTrue(body.contains("reconnectTcpWithinSetup(context, automatic = automatic)"))
        assertTrue(body.indexOf("if (switched.isFailure)") <
            body.indexOf("if (!listening)"))
    }

    @Test fun watchdogAndBootRecoveryHonorQuitAndShareSingleFlight() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val worker = source("app/src/main/java/com/vrec/app/experimental/adb/AdbRecoveryWorker.kt")
        val receiver = source("app/src/main/java/com/vrec/app/calls/PhoneStateReceiver.kt")
        val app = source("app/src/main/java/com/vrec/app/VrecApp.kt")
        val activity = source("app/src/main/java/com/vrec/app/MainActivity.kt")
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(lifecycle.contains("private val recoveryMutex = Mutex()"))
        assertTrue(lifecycle.contains("private val transportMutex = Mutex()"))
        assertTrue(lifecycle.contains("AdbRecoveryWorker.schedule(app)"))
        assertTrue(lifecycle.contains("AdbRecoveryWorker.cancel(app)"))
        assertTrue(lifecycle.contains("AdbUserChoices.setManuallyStopped(app, true)"))
        assertTrue(worker.contains("ExistingPeriodicWorkPolicy.KEEP"))
        assertTrue(worker.contains("AdbUserChoices.manuallyStopped(app)"))
        assertTrue(worker.contains("AdbLifecycle.recoverOnce(app"))
        assertTrue(receiver.contains("Intent.ACTION_BOOT_COMPLETED"))
        assertTrue(receiver.contains("AdbLifecycle.requestRecovery(app"))
        assertTrue(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertTrue(app.contains("AdbUserChoices.manuallyStopped(this)"))
        assertTrue(activity.contains("AdbUserChoices.setManuallyStopped(applicationContext, false)"))
    }

    @Test fun automaticPairingDefaultsOnButStillRequiresEnabledSettingsAccessibility() {
        val choices = source("app/src/main/java/com/vrec/app/experimental/adb/AdbUserChoices.kt")
        val service = source("app/src/main/java/com/vrec/app/calls/VrecAccessibilityService.kt")
        val ui = source("app/src/main/java/com/vrec/app/ui/WirelessAdbSetupCard.kt")
        assertTrue(choices.contains("prefs(context).getBoolean(AUTO_PAIR, true)"))
        assertTrue(service.contains("AdbUserChoices.autoPairCode(this)"))
        assertTrue(service.contains("PairingCodeUiPolicy.isSettingsPackage("))
        assertTrue(service.contains("if (state.pairingPort == null) {"))
        assertTrue(service.contains("LocalWirelessAdb.start(applicationContext, userInitiated = true)"))
        assertTrue(service.contains("lastDiscoveryRequestMs"))
        assertTrue(source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
            .contains("AdbUserChoices.manuallyStopped(app)"))
        assertTrue(service.contains("LocalWirelessAdb.pair(applicationContext, code)"))
        assertTrue(service.contains("pairingHandler.postDelayed(this, 400L)"))
        assertTrue(service.contains("pairingHandler.removeCallbacks(pairingWindowRead)"))
        assertTrue(source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
            .contains("VrecAccessibilityService.onPairingPortDiscovered()"))
        assertFalse(service.contains("Log.i(\"pairing code"))
        assertTrue(ui.contains("AdbUserChoices.setAutoPairCode(context, enabled)"))
        assertTrue(ui.contains("Turn this off to use manual pairing only."))
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(wireless.contains("VrecAccessibilityService.running"))
        assertTrue(wireless.contains("kotlinx.coroutines.delay(7_000L)"))
        assertTrue(wireless.contains("!pairedInThisSession"))
    }
}
