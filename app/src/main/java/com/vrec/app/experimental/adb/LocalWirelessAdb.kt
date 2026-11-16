package com.vrec.app.experimental.adb

import android.content.Context
import android.os.Build
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.shizuku.ShizukuSession
import com.vrec.app.shizuku.ShizukuPhase
import com.vrec.app.shizuku.ShizukuState
import com.vrec.app.shizuku.ShizukuTransportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Pure legacy compatibility policy; all discovery/retry decisions live in ADB core. */
internal object WirelessPortRetryPolicy {
    fun freshPort(
        failedPort: Int, observedPort: Int?, attemptedPorts: Set<Int>,
        failure: AdbFailure?,
    ): Int? = observedPort?.takeIf {
        (failure == AdbFailure.CONNECTION_LOST || failure == AdbFailure.NETWORK_UNAVAILABLE) &&
            it in 1..65535 && it != failedPort && it !in attemptedPorts &&
            attemptedPorts.size < 3
    }
}

/**
 * VRec's existing Home/status callers observe the shared core. No independent
 * NSD listener, multicast lock, watchdog, recovery state machine or libadb
 * transport is constructed by this facade.
 */
object LocalWirelessAdb {
    enum class Phase { IDLE, SEARCHING, PAIRING, CONNECTING, SWITCHING, VERIFYING_TCP, READY, RECOVERY_REQUIRED }
    data class State(
        val phase: Phase = Phase.IDLE,
        val supported: Boolean = Build.VERSION.SDK_INT >= 30,
        val pairingPort: Int? = null,
        val connectPort: Int? = null,
        val scanning: Boolean = false,
        val pairing: Boolean = false,
        val message: String = "Checking Android Wireless debugging…",
        val paired: Boolean = false,
    )
    val status = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observing: Job? = null
    private var owning = false
    private var observedCore: ShizukuSession? = null

    private fun snapshot(s: ShizukuState): State = State(
        phase = when (s.phase) {
            ShizukuPhase.IDLE, ShizukuPhase.STOPPED -> Phase.IDLE
            ShizukuPhase.SEARCHING -> Phase.SEARCHING
            ShizukuPhase.PAIRING -> Phase.PAIRING
            ShizukuPhase.CONNECTING -> Phase.CONNECTING
            ShizukuPhase.VERIFIED -> Phase.READY
            ShizukuPhase.NEEDS_SETUP -> Phase.RECOVERY_REQUIRED
        },
        pairingPort = s.pairingPort,
        connectPort = s.connectionPort,
        scanning = s.scanning,
        pairing = s.phase == ShizukuPhase.PAIRING,
        message = s.message,
        paired = s.connected || s.lastVerifiedAt > 0L,
    )

    @Synchronized fun start(context: Context, userInitiated: Boolean = false) {
        val app = context.applicationContext
        if (AdbLifecycle.quitting || AdbUserChoices.manuallyStopped(app)) return
        val adb = ShizukuHostAdapter.core(app)
        if (observedCore !== adb) {
            observing?.cancel()
            observedCore = adb
            status.value = snapshot(adb.state.value)
            observing = scope.launch {
                var previouslyVerified = adb.state.value.connected
                adb.state.collect { next ->
                    status.value = snapshot(next)
                    if (next.connected && !previouslyVerified &&
                        !AdbLifecycle.quitting) {
                        // Helper is app-owned; the shared library only owns ADB.
                        AdbLifecycle.requestRecovery(app, "shared ADB UID 2000 verified")
                    }
                    previouslyVerified = next.connected
                }
            }
        }
        if (!owning) {
            owning = true
            adb.acquireService()
        }
        if (userInitiated) scope.launch { adb.retry(auto = false) }
    }

    /** Codes may come only from the user-enabled VRec Settings accessibility reader. */
    fun pair(context: Context, code: String, pairingPort: Int? = null) {
        start(context)
        val adb = observedCore ?: return
        val port = pairingPort?.takeIf { it in 1..65535 } ?: adb.state.value.pairingPort ?: return
        scope.launch {
            if (adb.pair(port, code)) {
                status.value = snapshot(adb.state.value)
                AdbLifecycle.requestRecovery(context.applicationContext, "automatic pairing completed")
            }
        }
    }

    fun retry(context: Context) {
        start(context, userInitiated = true)
    }

    fun promoteVerifiedShellToTcp(context: Context) {
        if (AdbLifecycle.quitting ||
            com.vrec.app.calls.CallMonitor.hasLiveCall() ||
            com.vrec.app.recording.AdbFallbackNotice.enhancedActive.value) return
        scope.launch { ShizukuHostAdapter.core(context).retry(auto = true) }
    }

    fun openSettings(context: Context) {
        start(context, userInitiated = true)
        observedCore?.beginAutomaticPairingDiscovery()
        AdbSelfRecovery.openWirelessDebugging(context)
    }

    @Synchronized fun stop(context: Context? = null) {
        if (owning) {
            owning = false
            observedCore?.releaseService()
        }
        observing?.cancel()
        observing = null
        observedCore = null
        status.value = State(message = "Automatic ADB discovery inactive")
    }

    fun shutdown(context: Context) = stop(context)
}

