package com.vrec.app.experimental.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vrec.app.Log
import com.vrec.app.calls.CallMonitor
import com.vrec.app.Prefs
import com.vrec.app.recording.AdbFallbackNotice
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns VRec's background ADB work so it can be stopped as one unit:
 * the periodic recovery loop, the Wi-Fi callback, wireless discovery and any
 * in-flight connect. Quit stops all of it (the process may stay alive for the
 * accessibility service); opening VRec again restarts it.
 */
object AdbLifecycle {
    @Volatile var quitting = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var callReconciliation: Job? = null
    // Periodic recovery is single-flight, but may await wireless discovery.
    // Never hold transportMutex across that wait: the mDNS callback must be
    // able to finish pairing and switching while recovery observes it.
    private val recoveryMutex = Mutex()
    private val transportMutex = Mutex()
    private val wifiRecoveryJobs = mutableSetOf<Job>()
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Serialize all ADB transport changes, including foreground and wireless setup. */
    suspend fun <T> coordinateSetup(block: suspend () -> T): T = transportMutex.withLock { block() }

    /** Idempotent. Called at process start and when VRec is opened after Quit. */
    @Synchronized fun startBackground(context: Context) {
        val app = context.applicationContext
        // A boot receiver or WorkManager must not undo a deliberate Quit.
        if (AdbUserChoices.manuallyStopped(app)) return
        quitting = false
        // Preserve automatic TLS discovery previously triggered in Application,
        // but only AFTER an authorized entry point (Activity or enabled boot
        // receiver) calls startBackground. Otherwise Application.onCreate would
        // bypass the Start on boot preference before BOOT_COMPLETED is handled.
        if (ExperimentalRecording.wirelessRecoveryAllowed(app) &&
            AdbSelfRecovery.onWifi(app))
            LocalWirelessAdb.start(app)
        // The watchdog switch controls BOTH periodic in-process recovery and
        // the persisted WorkManager safety net. Manual and boot recovery remain
        // independent and available when periodic monitoring is disabled.
        if (AdbUserChoices.connectionWatchdog(app)) {
            catchingNonCancellation { AdbRecoveryWorker.schedule(app) }
                .onFailure { Log.w("ADB watchdog scheduling unavailable", it) }
            if (loop?.isActive != true) {
                // Immediate check, then every two minutes. Transport recovery
                // already applies its own authorization rules and backoff.
                loop = scope.launch {
                    recover(app, "process start")
                    delay(45_000L)
                    while (isActive && AdbUserChoices.connectionWatchdog(app)) {
                        if (Prefs.recordingEnabled(app)) recover(app, "background check")
                        delay(120_000L)
                    }
                }
            }
        } else {
            loop?.cancel()
            loop = null
            catchingNonCancellation { AdbRecoveryWorker.cancel(app) }
                .onFailure { Log.w("ADB watchdog cancel unavailable", it) }
            requestRecovery(app, "app opened")
        }
        if (wifiCallback == null) {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!quitting && AdbSelfRecovery.canSelfRestore(app))
                        synchronized(this@AdbLifecycle) {
                            if (!quitting) {
                                wifiRecoveryJobs.removeAll { !it.isActive }
                                wifiRecoveryJobs.add(scope.launch { recover(app, "Wi-Fi connected") })
                            }
                        }
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            if (catchingNonCancellation { cm.registerNetworkCallback(request, callback) }.isSuccess)
                wifiCallback = callback
        }
    }

    /**
     * Apply the watchdog switch without disconnecting an authorized shell or
     * interrupting a live recording. One-off boot, Wi-Fi and user recovery are
     * separate paths; Quit remains stronger than every preference.
     */
    @Synchronized fun configureWatchdog(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        AdbUserChoices.setConnectionWatchdog(app, enabled)
        if (!enabled) {
            loop?.cancel()
            loop = null
            catchingNonCancellation { AdbRecoveryWorker.cancel(app) }
                .onFailure { Log.w("ADB watchdog cancel unavailable", it) }
        } else if (!quitting && !AdbUserChoices.manuallyStopped(app)) {
            startBackground(app)
        }
    }

    /** Boot, user-unlock and package-replaced starts are safe to request repeatedly. */
    fun requestRecovery(context: Context, reason: String) {
        val app = context.applicationContext
        if (quitting || AdbUserChoices.manuallyStopped(app)) return
        scope.launch { recover(app, reason) }
    }

    /** Used by the persistent worker; uses the exact same single-flight gate. */
    suspend fun recoverOnce(context: Context, reason: String) {
        val app = context.applicationContext
        if (!quitting && !AdbUserChoices.manuallyStopped(app) &&
            Prefs.recordingEnabled(app))
            recover(app, reason)
    }

    /** Authenticated snapshot and import reconciliation while a live call is tracked. */
    @Synchronized fun watchLiveCall(context: Context) {
        if (quitting || callReconciliation?.isActive == true) return
        val app = context.applicationContext
        callReconciliation = scope.launch {
            delay(4_000L)
            while (isActive && !quitting && Prefs.recordingEnabled(app) &&
                CallMonitor.hasLiveCall()) {
                try {
                    if (ExperimentalCaptureDaemon.probe(app)) {
                        when (val snapshot = ExperimentalCaptureDaemon.captureSnapshot(app)) {
                            is ExperimentalCaptureDaemon.CaptureSnapshot.Recording ->
                                AdbFallbackNotice.reconcileAuthenticatedRecording(app, snapshot.file)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Paused ->
                                AdbFallbackNotice.reconcileAuthenticatedPaused(app)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Idle ->
                                AdbFallbackNotice.reconcileAuthenticatedIdle(app)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Unavailable -> Unit
                        }
                        ExperimentalCaptureDaemon.collect(app)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("Call capture reconciliation failed; retrying while call is active", failure)
                }
                delay(20_000L)
            }
        }
    }

    private suspend fun recover(app: Context, reason: String) {
        if (quitting) return
        try {
            recoveryMutex.withLock {
                if (!quitting && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val healthy = AdbHelperAutoRecovery.check(app)
                    if (healthy && !quitting) {
                        // Rebuild transient UI on process start, and reconcile
                        // later. Unavailable/malformed replies never clear state.
                        when (val snapshot = ExperimentalCaptureDaemon.captureSnapshot(app)) {
                            is ExperimentalCaptureDaemon.CaptureSnapshot.Recording ->
                                AdbFallbackNotice.reconcileAuthenticatedRecording(app, snapshot.file)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Paused ->
                                AdbFallbackNotice.reconcileAuthenticatedPaused(app)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Idle ->
                                AdbFallbackNotice.reconcileAuthenticatedIdle(app)
                            ExperimentalCaptureDaemon.CaptureSnapshot.Unavailable -> Unit
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Quit and superseded recovery jobs are expected cancellation, not failures.
            throw cancelled
        } catch (failure: Exception) {
            Log.w("ADB helper recovery failed ($reason)", failure)
        }
    }

    /**
     * Quit: stop recovery attempts, the Wi-Fi listener, wireless discovery and
     * in-flight connects, and close VRec's ADB session. Recording itself is
     * switched off by the caller first; the shell helper only stops capturing.
     */
    @Synchronized fun quit(context: Context) {
        val app = context.applicationContext
        AdbUserChoices.setManuallyStopped(app, true)
        catchingNonCancellation { AdbRecoveryWorker.cancel(app) }
            .onFailure { Log.w("ADB watchdog cancel unavailable", it) }
        quitting = true
        loop?.cancel()
        loop = null
        callReconciliation?.cancel()
        callReconciliation = null
        wifiRecoveryJobs.forEach { it.cancel() }
        wifiRecoveryJobs.clear()
        wifiCallback?.let { cb ->
            catchingNonCancellation {
                app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        wifiCallback = null
        AdbForegroundSetup.shutdown()
        LocalWirelessAdb.shutdown(app)
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) { AdbTransport.close() }
            ExperimentalRecording.refreshConnectionStatus()
        }
        Log.i("Quit: background ADB recovery, discovery and listeners stopped")
    }
}
