package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard against reintroducing frequent polling or parallel recovery loops. */
class AdbBatteryEfficiencyContractTest {
    private fun source(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun activityDoesNotOwnCompetingRecoveryLoop() {
        val activity = source("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertFalse(activity.contains("adbRecoveryJob"))
        assertTrue(activity.contains("AdbLifecycle.startBackground(applicationContext)"))
    }

    @Test fun homeDoesNotPollEveryThreeSeconds() {
        val home = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertFalse(home.contains("delay(3_000L)"))
        assertTrue(home.contains("120_000L else 30_000L"))
    }

    @Test fun lifecycleSkipsHealthyHelperAndQuitStopsListeners() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("!ExperimentalCaptureDaemon.ready.value"))
        assertTrue(lifecycle.contains("Prefs.recordingEnabled(app)"))
        assertTrue(lifecycle.contains("unregisterNetworkCallback"))
        assertTrue(lifecycle.contains("loop?.cancel()"))
    }

    @Test fun helperReadinessUsesReactiveState() {
        val home = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(home.contains("combine(ExperimentalCaptureDaemon.ready, AdbPrivilegedApiHelper.state"))
        assertTrue(home.contains(".distinctUntilChanged().collect"))
    }

    @Test fun serviceCleanupAndNoLegacyHandoff() {
        val service = source("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        assertTrue(service.contains("serviceScope.cancel()"))
        assertTrue(service.contains("handler.removeCallbacks(sampler)"))
        assertFalse(service.contains("private fun requestHandoff()"))
        assertFalse(service.contains("recoverStandardAfterAdbHandoffFailure"))
    }

    @Test fun recoveryIsSerialized() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("private val mutex = Mutex()"))
        assertTrue(recovery.contains("mutex.withLock { attempt(context.applicationContext) }"))
    }
}
