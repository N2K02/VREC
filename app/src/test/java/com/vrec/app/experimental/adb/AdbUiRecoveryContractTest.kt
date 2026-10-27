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
        assertTrue(card.contains("AdbLifecycle.coordinateSetup"))
    }

    @Test fun foregroundAndBackgroundRecoveryShareCoordinator() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("recoveryMutex.withLock { block() }"))
        assertTrue(lifecycle.contains("recoveryMutex.withLock {"))
        assertTrue(lifecycle.contains("wifiRecoveryJobs.forEach { it.cancel() }"))
    }

    @Test fun tcpApprovalIsOneTimeAndExplicit() {
        val onboarding = source("app/src/main/java/com/vrec/app/ui/WirelessAdbSetupCard.kt")
        assertTrue(onboarding.contains("AdbUserChoices.setTcpMode(context, true)"))
        assertTrue(onboarding.contains("confirmTcp = true"))
        assertTrue(onboarding.contains("TCP/5555 can expose Android debugging"))
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(wireless.contains("if (AdbUserChoices.tcpMode(app)) return switchToLocalTcp(app)"))
    }

    @Test fun detachedHelperDoesNotFalselyShowVerifiedShellUid() {
        val dashboard = source("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(dashboard.contains("if (verified) \"2000\" else \"Detached\""))
        assertTrue(dashboard.contains("val allReady = healthy && recordingOn && !busy"))
    }
}
