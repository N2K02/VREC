package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbUiRecoveryContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun navigatingHomeDoesNotLaunchSetupOrWirelessDiscovery() {
        val home = source("app/src/main/java/com/vrec/app/MainActivity.kt")
            .substringAfter("private fun HomeScreen()")
            .substringBefore("private fun RecordingMasterCard(")
        val card = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertFalse(home.contains("AdbPrivilegedApiHelper.checkHealth(context)"))
        assertFalse(home.contains("ExperimentalRecording.refreshConnectionStatus()"))
        assertFalse(card.contains("LocalAdbMiddleware.setup(context, userInitiated = false)"))
        assertTrue(card.contains("AdbForegroundSetup.startOrRepair(context)"))
        val foreground = source("app/src/main/java/com/vrec/app/experimental/adb/AdbForegroundSetup.kt")
        assertTrue(foreground.contains("AdbLifecycle.coordinateSetup {"))
    }

    @Test fun foregroundAndBackgroundRecoveryShareCoordinator() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("transportMutex.withLock { block() }"))
        assertTrue(lifecycle.contains("recoveryMutex.withLock {"))
        assertTrue(lifecycle.contains("wifiRecoveryJobs.forEach { it.cancel() }"))
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(wireless.contains("AdbLifecycle.coordinateSetup {"))
    }

    @Test fun tcpSwitchIsDefaultWithUserOptOutAndNetworkWarning() {
        val onboarding = source("app/src/main/java/com/vrec/app/ui/WirelessAdbSetupCard.kt")
        val choices = source("app/src/main/java/com/vrec/app/experimental/adb/AdbUserChoices.kt")
        assertTrue(choices.contains("getBoolean(TCP_MODE, true)"))
        assertTrue(onboarding.contains("AdbUserChoices.setTcpMode(context, enabled)"))
        assertTrue(onboarding.contains("Other devices on the network may reach TCP/5555"))
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(wireless.contains("val switchToTcp = AdbUserChoices.tcpMode(app)"))
        assertTrue(wireless.contains("if (switchToTcp) return switchToLocalTcp(app)"))
        assertTrue(wireless.contains("lastTcpPromotionAtMs != 0L"))
        assertTrue(wireless.contains("CallMonitor.hasLiveCall()"))
        assertTrue(wireless.contains("AdbLifecycle.coordinateSetup {"))
    }

    @Test fun detachedHelperDoesNotFalselyShowVerifiedShellUid() {
        val dashboard = source("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(dashboard.contains("if (verified) \"2000\" else \"Detached\""))
        assertTrue(dashboard.contains("val allReady = healthy && recordingOn && !busy"))
    }
}
