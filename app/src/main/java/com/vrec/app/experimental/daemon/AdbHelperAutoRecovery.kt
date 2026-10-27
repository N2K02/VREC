package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.AdbFailure
import com.vrec.app.experimental.adb.AdbFailurePolicy
import com.vrec.app.experimental.adb.AdbLifecycle
import com.vrec.app.experimental.adb.AdbRecoveryBackoff
import com.vrec.app.experimental.adb.AdbRecoveryPolicy
import com.vrec.app.experimental.adb.AdbSelfRecovery
import com.vrec.app.experimental.adb.AdbSelfRecovery.RestoreOutcome
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.LocalWirelessAdb
import com.vrec.app.experimental.adb.adbFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Reattach the independent helper; otherwise restore only ADB the user already
 * authorized and hasn't disconnected: localhost TCP/5555 while it still
 * listens, or the paired Wireless debugging. Every path re-verifies UID 2000
 * and requires the helper to answer. Decisions use typed [AdbFailure]s:
 * only CONNECTION_LOST is retried; authorization failures wait for the user's
 * Retry, and other failures back off exponentially.
 */
object AdbHelperAutoRecovery {
    enum class Outcome {
        HELPER_RUNNING, RESTORED_TCP, RESTORED_WIRELESS,
        /** Not applicable now: recording off, Quit, user Disconnect, busy, nothing authorized. */
        SKIPPED,
        BACKING_OFF,
        /** Authorization timed out or was rejected: needs the user's Retry. */
        MANUAL_RETRY_REQUIRED,
        FAILED,
    }

    private val mutex = Mutex()
    private val tcpBackoff = AdbRecoveryBackoff()
    private val wirelessBackoff = AdbRecoveryBackoff()

    /** Typed result of the last attempt, for tests and UI decisions. */
    val lastOutcome = MutableStateFlow<Outcome?>(null)
    /** Human-readable last attempt, shown on Home to validate recovery on a device. */
    val lastResult = MutableStateFlow<String?>(null)

    private fun record(outcome: Outcome, text: String) {
        // Healthy periodic probes are not new events: avoid changing Home state
        // and persisting identical diagnostics every recovery interval.
        if (lastOutcome.value == outcome &&
            lastResult.value?.substringAfter(" · ") == text) return
        lastOutcome.value = outcome
        lastResult.value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + " · " + text
        Log.i("ADB auto-recovery: $outcome — $text")
    }

    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { attempt(context.applicationContext) }.let {
            it == Outcome.HELPER_RUNNING || it == Outcome.RESTORED_TCP || it == Outcome.RESTORED_WIRELESS
        }
    }

    private suspend fun attempt(app: Context): Outcome {
        if (AdbLifecycle.quitting) return Outcome.SKIPPED
        if (AdbPrivilegedApiHelper.reconnect(app)) {
            ExperimentalCaptureDaemon.collect(app)
            // The shell helper survives an ADB transport detach. Restore the
            // authorized transport opportunistically without restarting capture.
            val saved = ExperimentalRecording.recoveryAuthorization(app)
            val detached = !ExperimentalRecording.status.value.connected ||
                !AdbTransport.isConnected
            if (detached && saved.allowed && saved.tcpVerified &&
                Prefs.recordingEnabled(app) &&
                !ExperimentalRecording.awaitingManualRetry &&
                !ExperimentalRecording.status.value.busy &&
                ExperimentalRecording.tcpPortListening()) {
                val now = SystemClock.elapsedRealtime()
                if (tcpBackoff.allowed(now)) {
                    val failure = reconnectTcp(app, automatic = true)
                    if (failure == null) {
                        tcpBackoff.onSuccess()
                        record(Outcome.RESTORED_TCP, "ADB transport reattached; running helper preserved")
                        return Outcome.RESTORED_TCP
                    }
                    if (AdbFailurePolicy.requiresManualRetry(failure)) {
                        record(Outcome.MANUAL_RETRY_REQUIRED, "ADB transport needs user authorization")
                        return Outcome.MANUAL_RETRY_REQUIRED
                    }
                    tcpBackoff.onFailure(SystemClock.elapsedRealtime())
                }
            }
            wirelessBackoff.onSuccess()
            lastOutcome.value = Outcome.HELPER_RUNNING
            return Outcome.HELPER_RUNNING
        }
        // Preserve the user's choices: recording OFF and a deliberate Disconnect
        // both stop automatic reconnection.
        val saved = ExperimentalRecording.recoveryAuthorization(app)
        if (!Prefs.recordingEnabled(app) || !saved.allowed ||
            ExperimentalRecording.status.value.busy) return Outcome.SKIPPED
        if (ExperimentalRecording.awaitingManualRetry) return Outcome.MANUAL_RETRY_REQUIRED

        val now = SystemClock.elapsedRealtime()
        val tcpEligible = AdbRecoveryPolicy.shouldRetry(AdbRecoveryPolicy.Conditions(
            adbMethodSelected = true,
            recordingEnabled = true,
            autoRecoveryAllowed = saved.allowed,
            connectionBusy = false,
            adbIdentityPresent = ExperimentalRecording.adbIdentityPresent(app),
            tcpVerifiedBefore = saved.tcpVerified,
            tcpPortListening = ExperimentalRecording.tcpPortListening(),
        ))
        if (tcpEligible) {
            if (!tcpBackoff.allowed(now)) return Outcome.BACKING_OFF
            val failure = recoverOverTcp(app)
            return if (failure == null) {
                tcpBackoff.onSuccess(); wirelessBackoff.onSuccess()
                record(Outcome.RESTORED_TCP, "helper restored over TCP/5555 (UID 2000 verified)")
                Outcome.RESTORED_TCP
            } else if (AdbFailurePolicy.requiresManualRetry(failure)) {
                record(Outcome.MANUAL_RETRY_REQUIRED, "TCP/5555 needs Retry ($failure)")
                Outcome.MANUAL_RETRY_REQUIRED
            } else {
                tcpBackoff.onFailure(SystemClock.elapsedRealtime())
                record(Outcome.FAILED, "TCP/5555 reconnect failed ($failure); next try in " +
                    tcpBackoff.delayFor(tcpBackoff.failures) / 60_000 + " min")
                Outcome.FAILED
            }
        }

        if (ExperimentalRecording.status.value.connected) return Outcome.SKIPPED
        if (!AdbSelfRecovery.canSelfRestore(app)) {
            // Not opted in to restore: never touch the setting, but if Wireless
            // debugging is already on and paired, a (throttled) search may reconnect.
            if (ExperimentalRecording.wirelessRecoveryAllowed(app) &&
                AdbSelfRecovery.wirelessDebuggingOn(app) && AdbSelfRecovery.onWifi(app))
                LocalWirelessAdb.start(app)
            return Outcome.SKIPPED
        }
        if (!wirelessBackoff.allowed(now)) return Outcome.BACKING_OFF
        return when (val restore = AdbSelfRecovery.restoreWireless(app)) {
            RestoreOutcome.RESTORED -> {
                wirelessBackoff.onSuccess(); tcpBackoff.onSuccess()
                ExperimentalCaptureDaemon.collect(app)
                record(Outcome.RESTORED_WIRELESS, "helper restored through paired Wireless debugging")
                Outcome.RESTORED_WIRELESS
            }
            // Waiting for Wi-Fi or for the user is not a failure to back off from.
            RestoreOutcome.NO_WIFI, RestoreOutcome.NOT_ELIGIBLE -> Outcome.SKIPPED
            RestoreOutcome.USER_DISABLED -> {
                record(Outcome.SKIPPED, "Wireless debugging was turned off in Settings; not re-enabling")
                Outcome.SKIPPED
            }
            RestoreOutcome.MANUAL_RETRY_REQUIRED -> Outcome.MANUAL_RETRY_REQUIRED
            RestoreOutcome.SETTING_REJECTED, RestoreOutcome.NOT_VERIFIED -> {
                val kind = ExperimentalRecording.status.value.lastFailure
                if (AdbFailurePolicy.requiresManualRetry(kind)) {
                    record(Outcome.MANUAL_RETRY_REQUIRED, "Wireless restore needs Retry ($kind)")
                    Outcome.MANUAL_RETRY_REQUIRED
                } else {
                    wirelessBackoff.onFailure(SystemClock.elapsedRealtime())
                    record(Outcome.FAILED, (if (restore == RestoreOutcome.SETTING_REJECTED)
                        "Android refused to turn on Wireless debugging"
                        else "Wireless restore not verified") +
                        "; next try in " + wirelessBackoff.delayFor(wirelessBackoff.failures) / 60_000 + " min")
                    Outcome.FAILED
                }
            }
        }
    }

    /** Returns null on success (UID 2000 and helper verified), else the typed failure. */
    private suspend fun recoverOverTcp(app: Context): AdbFailure? {
        val link = ExperimentalRecording.status.value
        if (link.connected && link.shellUid == 2000 && AdbTransport.isConnected) {
            val confirmed = AdbTransport.exec("id -u").getOrNull()
                ?.lineSequence()?.any { it.trim() == "2000" } == true
            if (confirmed && AdbPrivilegedApiHelper.startWithAdb(app).isSuccess &&
                ExperimentalCaptureDaemon.probe(app)) {
                ExperimentalCaptureDaemon.collect(app)
                return null
            }
        }
        if (link.connected) {
            AdbTransport.close()
            ExperimentalRecording.refreshConnectionStatus()
        }
        return reconnectTcp(app, automatic = true)
    }

    /**
     * Connect to localhost TCP/5555 and verify UID 2000 and the helper. Only a
     * CONNECTION_LOST failure (adbd restarting, socket reset) gets two quick
     * retries; the manual-retry latch is never cleared here, so an automatic
     * retry can't re-trigger Android's authorization prompt.
     * Returns null on success, else the typed failure.
     */
    suspend fun reconnectTcp(app: Context, automatic: Boolean): AdbFailure? {
        var failure: AdbFailure? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) {
                delay(if (attempt == 1) 2_000L else 5_000L)
                if (!ExperimentalRecording.tcpPortListening()) return AdbFailure.NETWORK_UNAVAILABLE
            }
            val result = ExperimentalRecording.connect(app, automatic = automatic)
            if (result.isSuccess) {
                if (ExperimentalCaptureDaemon.probe(app)) {
                    ExperimentalCaptureDaemon.collect(app)
                    return null
                }
                return AdbFailure.HELPER_UNAVAILABLE
            }
            failure = result.exceptionOrNull().adbFailure ?: AdbFailure.CONNECTION_LOST
            if (!AdbFailurePolicy.retryable(failure)) return failure
        }
        return failure
    }
}
