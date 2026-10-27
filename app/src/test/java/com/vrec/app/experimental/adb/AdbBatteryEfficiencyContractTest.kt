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
        assertTrue(log.contains("redact(message)"))
        assertTrue(log.contains("lastInfoTimes[safe]"))
        assertTrue(log.indexOf("lastInfoTimes[safe]") < log.indexOf("AndroidLog.i(TAG, safe)"))
    }

    @Test fun recoveryStatusAndRoutineLogsDoNotChurn() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("lastOutcome.value == outcome"))
        val updater = source("app/src/main/java/com/vrec/app/update/AppUpdater.kt")
        assertTrue(updater.contains("if (manual) com.vrec.app.Log.i"))
        val transport = source("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt")
        assertFalse(transport.contains("connect: already connected"))
    }

    @Test fun shellStateControlsUpdaterAndPausedFinalization() {
        val updater = source("app/src/main/java/com/vrec/app/LiveUpdater.kt")
        assertTrue(updater.contains("AdbFallbackNotice.enhancedActive.value"))
        assertTrue(updater.contains("CallMonitor.hasLiveCall()"))
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertFalse(notice.contains("if (enhancedPaused.value) return"))
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertFalse(daemon.contains("RecordingService.state.value is RecordingService.State.Idle"))
    }

    @Test fun callMonitorAndOverlayNeverDependOnPermanentlyIdleLegacyState() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertFalse(monitor.contains("RecordingService.state.value"))
        assertTrue(monitor.contains("ExperimentalCaptureDaemon.captureControl"))
        val overlay = source("app/src/main/java/com/vrec/app/recording/OverlayControlService.kt")
        assertTrue(overlay.contains("ExperimentalCaptureDaemon.captureControl"))
        assertFalse(overlay.contains(".setAction(RecordingService.ACTION_PAUSE)"))
        assertFalse(overlay.contains(".setAction(RecordingService.ACTION_RESUME)"))
    }

    @Test fun activeCaptureGetsPeriodicReconciliationEvenWhenHelperIsHealthy() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("AdbFallbackNotice.enhancedActive.value ||"))
        assertTrue(lifecycle.contains("captureControl(app, 'I')"))
        assertTrue(lifecycle.contains("\"IDLE\" -> AdbFallbackNotice.reconcileAuthenticatedIdle(app)"))
    }

    @Test fun resumeWaitsForAuthenticatedStartedWake() {
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertTrue(notice.contains("else if (!shellLifecycle.isActive())"))
        assertTrue(notice.contains("if (!shellLifecycle.stoppedByUser() && !enhancedPaused.value && !enhancedActive.value) return"))
    }

    @Test fun shellStateReconciliationRequiresAuthenticatedIdle() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertTrue(lifecycle.contains("if (healthy && AdbFallbackNotice.enhancedActive.value)"))
        assertTrue(lifecycle.contains("captureControl(app, 'I')"))
        assertTrue(lifecycle.contains("\"IDLE\" -> AdbFallbackNotice.reconcileAuthenticatedIdle(app)"))
        assertTrue(notice.contains("fun reconcileAuthenticatedIdle(context: Context)"))
    }

    @Test fun notificationStopSuppressesAutomaticRestart() {
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(receiver.contains("\"STOPPED\" -> CallMonitor.onShellStopConfirmed(context)"))
        assertTrue(monitor.contains("fun onShellStopConfirmed(context: Context)"))
        assertTrue(monitor.contains("sessions.values.filter { it.active }.forEach { suppressed.add(it.id) }"))
        assertTrue(receiver.contains("pending.finish()"))
    }

    @Test fun interruptedShellImportIsRetriedWithoutDeletingOriginal() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("File(dest.parentFile, dest.name + \".json\").exists()"))
        assertTrue(daemon.contains("Cannot remove incomplete previous import"))
        assertTrue(daemon.contains("if (t is CancellationException) throw t"))
        assertTrue(daemon.contains("temp.delete()"))
    }

    @Test fun manualStartUsesConfirmedShellAndNeverLegacyFacade() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val section = monitor.substringAfter("private fun requestManualSegment")
            .substringBefore("@Synchronized\\n    fun manualStop")
        assertTrue(section.contains("startCurrentCellularCall(app)"))
        assertTrue(section.contains("if (result.isSuccess)"))
        assertFalse(section.contains("RecordingService.start("))
        assertTrue(section.contains("candidates.singleOrNull()"))
    }

    @Test fun shellManualRestartWakesUiAndDoesNotAutoRestart() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        val command = shell.substringAfter("if (command == COMMAND_START)")
            .substringBefore("if (command == COMMAND_SET_TRACKING)")
        assertTrue(command.contains("userStoppedCapture = false"))
        assertTrue(command.contains("wake(ACTION_CALL_STARTED"))
        assertTrue(command.contains("activeRecording == null"))
    }

    @Test fun quitCancelsWifiRecoveryAndSerializesRecovery() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("recoveryMutex.withLock"))
        assertTrue(lifecycle.contains("wifiRecoveryJobs.forEach { it.cancel() }"))
    }

    @Test fun shellLifecycleFeedsLegacyUiAndControlsFailClosed() {
        val service = source("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        assertTrue(service.contains("combine(AdbFallbackNotice.enhancedActive"))
        assertTrue(service.contains("State.Paused"))
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        assertTrue(receiver.contains("Shell recording control not confirmed"))
    }

    @Test fun diagnosticsAreBatched() {
        val log = source("app/src/main/java/com/vrec/app/Log.kt")
        assertTrue(log.contains("postDelayed(flush, 1500L)"))
        assertTrue(log.contains("writeHandler.removeCallbacks(flush)"))
        assertTrue(log.contains("persistNow()"))
    }

    @Test fun recoveryIsSerialized() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("private val mutex = Mutex()"))
        assertTrue(recovery.contains("mutex.withLock { attempt(context.applicationContext) }"))
    }
}
