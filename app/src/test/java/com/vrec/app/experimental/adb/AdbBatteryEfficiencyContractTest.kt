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

    @Test fun microphoneEngineAndLegacyHandoffAreRemoved() {
        val service = source("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        assertFalse(service.contains("RecordingEngine("))
        assertFalse(service.contains("MediaRecorder"))
        assertFalse(service.contains("serviceScope"))
        assertFalse(service.contains("requestAdbHandoff(context"))
        assertFalse(service.contains("private var adbFallback"))
        assertFalse(service.contains("EXTRA_ADB_FALLBACK"))
        assertFalse(service.contains("forceStandardFallback: Boolean"))
        assertTrue(service.contains("stopSelf(startId)"))
    }

    @Test fun loggingSuppressesRepeatedInfoBeforePersistence() {
        val log = source("app/src/main/java/com/vrec/app/Log.kt")
        assertTrue(log.contains("DUPLICATE_INFO_WINDOW_MS"))
        assertTrue(log.contains("message == lastInfo"))
        assertTrue(log.indexOf("message == lastInfo") < log.indexOf("AndroidLog.i(TAG, message)"))
    }

    @Test fun recoveryStatusAndRoutineLogsDoNotChurn() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("lastOutcome.value == outcome"))
        val updater = source("app/src/main/java/com/vrec/app/update/AppUpdater.kt")
        assertTrue(updater.contains("if (manual) com.vrec.app.Log.i"))
        val transport = source("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt")
        assertFalse(transport.contains("connect: already connected"))
    }

    @Test fun recoveryIsSerialized() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("private val mutex = Mutex()"))
        assertTrue(recovery.contains("mutex.withLock { attempt(context.applicationContext) }"))
    }
}
