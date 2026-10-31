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
        assertFalse(home.contains("LocalAdbMiddleware.setup(context, userInitiated = false)"))
    }

    @Test fun lifecycleSkipsHealthyHelperAndQuitStopsListeners() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("if (Prefs.recordingEnabled(app)) recover(app, \"background check\")"))
        assertTrue(lifecycle.contains("watchLiveCall(context: Context)"))
        assertTrue(lifecycle.contains("unregisterNetworkCallback"))
        assertTrue(lifecycle.contains("loop?.cancel()"))
    }

    @Test fun helperReadinessUsesReactiveState() {
        val home = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(home.contains("ExperimentalCaptureDaemon.ready.collectAsState()"))
        assertTrue(home.contains("AdbPrivilegedApiHelper.state.collectAsState()"))
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

    @Test fun loggingIsSanitizedAndUsesOnlyLogcat() {
        val log = source("app/src/main/java/com/vrec/app/Log.kt")
        assertTrue(log.contains("private fun redact(message: String)"))
        assertTrue(log.contains("AndroidLog.i(TAG, redact(message)"))
        assertTrue(log.contains("AndroidLog.w(TAG, safe"))
        assertFalse(log.contains("persistNow("))
        assertFalse(log.contains("lastInfoTimes"))
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
        // The facade state is now reactive and is used ONLY to lock the
        // global toggle during a call, not to start microphone capture.
        assertTrue(monitor.contains("RecordingService.state.value !is RecordingService.State.Idle"))
        assertTrue(monitor.contains("ExperimentalCaptureDaemon.captureControl"))
        val overlay = source("app/src/main/java/com/vrec/app/recording/OverlayControlService.kt")
        assertTrue(overlay.contains("EnhancedCaptureControlReceiver.request(applicationContext, action)"))
        assertTrue(overlay.contains("EnhancedCaptureControlReceiver.request(applicationContext, 'S')"))
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        assertTrue(receiver.contains("ExperimentalCaptureDaemon.captureControl(context, action)"))
        assertFalse(overlay.contains(".setAction(RecordingService.ACTION_PAUSE)"))
        assertFalse(overlay.contains(".setAction(RecordingService.ACTION_RESUME)"))
    }

    @Test fun pausedRecoveryCreatesNotificationChannelAndDismissesStaleOverlay() {
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        val paused = notice.substringAfter("fun reconcileAuthenticatedPaused(context: Context)")
            .substringBefore("fun reconcileAuthenticatedIdle(context: Context)")
        assertTrue(paused.contains("shellLifecycle.reconcilePaused()"))
        assertTrue(paused.contains("context.stopService(Intent(context, OverlayControlService::class.java))"))
        val notification = notice.substringAfter("private fun notifyShellCapture(context: Context, paused: Boolean)")
            .substringBefore("fun shellRecordingEnded(context: Context, file: String)")
        assertTrue(notice.contains("notifyShellCapture(context, paused)"))
        assertTrue(notification.contains("manager.getNotificationChannel(CHANNEL) == null"))
        assertTrue(notification.contains("manager.createNotificationChannel(NotificationChannel("))
    }

    @Test fun processRestartRecoversAuthenticatedRecordingIdentityAndPausedState() {
        val helper = source("shellserver/src/com/vrec/app/shell/Main.java")
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertTrue(helper.contains("\"RECORDING \" + activeRecording.name"))
        assertTrue(daemon.contains("fun parseCaptureSnapshot(reply: String)"))
        assertTrue(daemon.contains("file.matches(Regex("))
        assertTrue(lifecycle.contains("AdbFallbackNotice.reconcileAuthenticatedRecording(app, snapshot.file)"))
        assertTrue(lifecycle.contains("AdbFallbackNotice.reconcileAuthenticatedPaused(app)"))
        assertTrue(notice.contains("fun reconcileAuthenticatedPaused(context: Context)"))
    }

    @Test fun activeCaptureGetsPeriodicReconciliationEvenWhenHelperIsHealthy() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("CallMonitor.hasLiveCall()"))
        assertTrue(lifecycle.contains("ExperimentalCaptureDaemon.captureSnapshot(app)"))
        assertTrue(lifecycle.contains("AdbFallbackNotice.reconcileAuthenticatedIdle(app)"))
    }

    @Test fun resumeWaitsForAuthenticatedStartedWake() {
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertTrue(notice.contains("else if (!shellLifecycle.isActive())"))
        assertTrue(notice.contains("if (!shellLifecycle.stoppedByUser() && !enhancedPaused.value && !enhancedActive.value) return"))
    }

    @Test fun shellStateReconciliationRequiresAuthenticatedIdle() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val notice = source("app/src/main/java/com/vrec/app/recording/AdbFallbackNotice.kt")
        assertTrue(lifecycle.contains("if (healthy && !quitting)"))
        assertTrue(lifecycle.contains("ExperimentalCaptureDaemon.captureSnapshot(app)"))
        assertTrue(lifecycle.contains("AdbFallbackNotice.reconcileAuthenticatedIdle(app)"))
        assertTrue(notice.contains("fun reconcileAuthenticatedIdle(context: Context)"))
    }

    @Test fun automaticCallObservationDoesNotInventRecordingOwnership() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val start = monitor.substringAfter("private fun reconcile(context: Context)")
            .substringBefore("private fun rememberShellIdentity(")
        assertFalse(start.contains("recordingId = selected.id"))
        assertFalse(start.lines().any { it.trimStart().startsWith("RecordingService.start(") })
        assertTrue(start.contains("autoAttempted.add(selected.id)"))
        assertTrue(start.contains("rememberShellIdentity(context, selected)"))
    }

    @Test fun notificationControlsAreSerializedAcrossReceiverInstances() {
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        // A single atomic pending slot protects notification PendingIntents
        // and the Home/mini-bar/overlay requests across receiver instances.
        assertTrue(receiver.contains("pendingAction.compareAndSet(null, action)"))
        assertTrue(receiver.contains("private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(receiver.contains("CaptureControlPolicy.isAllowed(action"))
        assertTrue(receiver.contains("if (action == 'P')"))
        assertTrue(receiver.contains("AdbFallbackNotice.setEnhancedPaused(context, true)"))
        assertTrue(receiver.contains("if (action == 'R')"))
        assertTrue(receiver.contains("AdbFallbackNotice.setEnhancedPaused(context, false)"))
        assertTrue(receiver.contains("if (action == 'S')"))
        assertTrue(receiver.contains("CallMonitor.onShellStopConfirmed(context)"))
    }

    @Test fun notificationReceiverHasBoundedExecutionAndAlwaysFinishes() {
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        assertTrue(receiver.contains("withTimeout(8_000L)"))
        assertTrue(receiver.contains("finally {"))
        assertTrue(receiver.contains("pending.finish()"))
        assertTrue(receiver.contains("if (action == 'S')"))
        assertTrue(receiver.contains("CallMonitor.onShellStopConfirmed(context)"))
    }

    @Test fun notificationStopSuppressesAutomaticRestart() {
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(receiver.contains("if (action == 'S')"))
        assertTrue(receiver.contains("CallMonitor.onShellStopConfirmed(context)"))
        assertTrue(monitor.contains("fun onShellStopConfirmed(context: Context)"))
        assertTrue(monitor.contains("sessions.values.filter { it.active }.forEach { suppressed.add(it.id) }"))
        assertTrue(receiver.contains("pending.finish()"))
    }

    @Test fun interruptedShellImportIsRetriedWithoutDeletingOriginal() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("RecordingStore.hasValidMetadata(dest)"))
        assertTrue(daemon.contains("Cannot remove incomplete previous import"))
        assertTrue(daemon.contains("if (t is CancellationException) throw t"))
        assertTrue(daemon.contains("temp.delete()"))
    }

    @Test fun manualStartUsesConfirmedShellAndNeverLegacyFacade() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val section = monitor.substringAfter("private fun requestManualSegment")
            .substringBefore("@Synchronized\\n    fun manualStop")
        assertTrue(section.contains("startCurrentCall(app)"))
        assertTrue(section.contains("ManualCaptureIdentityPolicy.matches("))
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
        assertTrue(lifecycle.contains("callReconciliation?.cancel()"))
        assertTrue(lifecycle.contains("CallMonitor.hasLiveCall()"))
        assertTrue(lifecycle.contains("ExperimentalCaptureDaemon.collect(app)"))
    }

    @Test fun shellLifecycleFeedsLegacyUiAndControlsFailClosed() {
        val service = source("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        assertTrue(service.contains("combine(AdbFallbackNotice.enhancedActive"))
        assertTrue(service.contains("State.Paused"))
        val receiver = source("app/src/main/java/com/vrec/app/recording/EnhancedCaptureControlReceiver.kt")
        assertTrue(receiver.contains("Shell capture command not confirmed"))
        assertTrue(receiver.contains("CaptureControlPolicy.isAllowed(action"))
    }

    @Test fun diagnosticsAvoidBackgroundDiskWrites() {
        val log = source("app/src/main/java/com/vrec/app/Log.kt")
        assertTrue(log.contains("AndroidLog.i("))
        assertTrue(log.contains("AndroidLog.w("))
        assertFalse(log.contains("postDelayed("))
        assertFalse(log.contains("writeHandler"))
        assertFalse(log.contains("persistNow("))
    }

    @Test fun recoveryIsSerialized() {
        val recovery = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperAutoRecovery.kt")
        assertTrue(recovery.contains("private val mutex = Mutex()"))
        assertTrue(recovery.contains("mutex.withLock { attempt(context.applicationContext) }"))
    }
}
