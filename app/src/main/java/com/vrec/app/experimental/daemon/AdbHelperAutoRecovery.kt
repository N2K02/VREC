package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.AdbRecoveryBackoff
import com.vrec.app.experimental.adb.AdbRecoveryPolicy
import com.vrec.app.experimental.adb.AdbSelfRecovery
import com.vrec.app.experimental.adb.AdbSelfRecovery.RestoreOutcome
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
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
 * authorized: localhost TCP/5555 when it is still listening, or the paired
 * Wireless debugging after a restart. Every path is verified (UID 2000, then
 * the helper answering) and failures back off exponentially.
 */
object AdbHelperAutoRecovery {
    private val mutex = Mutex()
    private val tcpBackoff = AdbRecoveryBackoff()
    private val wirelessBackoff = AdbRecoveryBackoff()
    /** An authorization timeout means Android showed a prompt nobody answered: don't re-ask automatically. */
    @Volatile private var lastFailureWasAuthTimeout = false

    /** Last automatic recovery attempt, shown on Home so it can be checked on a device. */
    val lastResult = MutableStateFlow<String?>(null)

    private fun record(text: String) {
        lastResult.value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) + " · " + text
        Log.i("ADB auto-recovery: $text")
    }

    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            if (AdbPrivilegedApiHelper.reconnect(app)) {
                ExperimentalCaptureDaemon.collect(app)
                tcpBackoff.onSuccess(); wirelessBackoff.onSuccess()
                return@withLock true
            }
            if (ExperimentalRecording.method(app) != ExperimentalRecording.Method.BUILT_IN_ADB ||
                !Prefs.recordingEnabled(app) || ExperimentalRecording.status.value.busy)
                return@withLock false

            val saved = ExperimentalRecording.recoveryAuthorization(app)
            val tcpEligible = AdbRecoveryPolicy.shouldRetry(AdbRecoveryPolicy.Conditions(
                adbMethodSelected = true,
                recordingEnabled = true,
                autoRecoveryAllowed = saved.allowed,
                connectionBusy = false,
                adbIdentityPresent = ExperimentalRecording.adbIdentityPresent(app),
                tcpVerifiedBefore = saved.tcpVerified,
                tcpPortListening = ExperimentalRecording.tcpPortListening(),
            ))
            val now = SystemClock.elapsedRealtime()
            if (tcpEligible) {
                if (!tcpBackoff.allowed(now)) return@withLock false
                return@withLock if (recoverOverTcp(app)) {
                    tcpBackoff.onSuccess(); wirelessBackoff.onSuccess()
                    record("helper restored over TCP/5555")
                    true
                } else {
                    tcpBackoff.onFailure(SystemClock.elapsedRealtime())
                    record("TCP/5555 reconnect failed; next try in " +
                        tcpBackoff.delayFor(tcpBackoff.failures) / 60_000 + " min")
                    false
                }
            }

            // TCP/5555 is gone (restart, debugging reset). Try the paired
            // Wireless debugging route, verified end to end.
            if (ExperimentalRecording.status.value.connected ||
                ExperimentalRecording.awaitingManualRetry ||
                !AdbSelfRecovery.canSelfRestore(app) ||
                !wirelessBackoff.allowed(now)) return@withLock false
            when (val outcome = AdbSelfRecovery.restoreWireless(app)) {
                RestoreOutcome.RESTORED -> {
                    wirelessBackoff.onSuccess(); tcpBackoff.onSuccess()
                    ExperimentalCaptureDaemon.collect(app)
                    record("helper restored through paired Wireless debugging")
                    true
                }
                // Waiting for Wi-Fi is not a failure; the Wi-Fi callback retries.
                RestoreOutcome.NO_WIFI, RestoreOutcome.NOT_ELIGIBLE -> false
                else -> {
                    wirelessBackoff.onFailure(SystemClock.elapsedRealtime())
                    record((if (outcome == RestoreOutcome.SETTING_REJECTED)
                        "Android refused to turn on Wireless debugging"
                        else "Wireless restore not verified") +
                        "; next try in " + wirelessBackoff.delayFor(wirelessBackoff.failures) / 60_000 + " min")
                    false
                }
            }
        }
    }

    /**
     * Reuse a live verified session if possible, otherwise reconnect to
     * localhost TCP/5555. Transient failures (adbd restarting, socket reset)
     * get two quick retries; an authorization timeout does not, so a pending
     * Android prompt is never repeated.
     */
    private suspend fun recoverOverTcp(app: Context): Boolean {
        val link = ExperimentalRecording.status.value
        if (link.connected && link.shellUid == 2000 && AdbTransport.isConnected) {
            val confirmed = AdbTransport.exec("id -u").getOrNull()
                ?.lineSequence()?.any { it.trim() == "2000" } == true
            if (confirmed && AdbPrivilegedApiHelper.startWithAdb(app).isSuccess) {
                ExperimentalCaptureDaemon.collect(app)
                return true
            }
        }
        if (link.connected) {
            AdbTransport.close()
            ExperimentalRecording.refreshConnectionStatus()
        }
        // A transient failure (adbd restart) latched manual retry; the backoff
        // window has passed, so try again. A timed-out prompt stays latched.
        if (ExperimentalRecording.awaitingManualRetry && !lastFailureWasAuthTimeout)
            ExperimentalRecording.allowManualRetry()
        return reconnectTcpWithRetry(app, automatic = true)
    }

    /** Shared with the wireless-to-TCP switch. Returns true only after UID 2000 and the helper are verified. */
    suspend fun reconnectTcpWithRetry(app: Context, automatic: Boolean): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) {
                delay(if (attempt == 1) 2_000L else 5_000L)
                if (!ExperimentalRecording.tcpPortListening()) return false
                ExperimentalRecording.allowManualRetry()
            }
            val result = ExperimentalRecording.connect(app, automatic = automatic)
            if (result.isSuccess && ExperimentalCaptureDaemon.probe(app)) {
                lastFailureWasAuthTimeout = false
                ExperimentalCaptureDaemon.collect(app)
                return true
            }
            val reason = result.exceptionOrNull()?.message.orEmpty()
            lastFailureWasAuthTimeout = reason.contains("timed out", ignoreCase = true)
            if (lastFailureWasAuthTimeout || reason.contains("stopped", ignoreCase = true)) return false
        }
        return false
    }
}
