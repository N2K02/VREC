package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.AdbRecoveryPolicy
import com.vrec.app.experimental.adb.AdbSelfRecovery
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reattach independent helper, otherwise restore only already verified local ADB. */
object AdbHelperAutoRecovery {
    private const val RETRY_INTERVAL_MS = 60_000L
    private val mutex = Mutex()
    private var retryAfterElapsedMs = 0L

    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            if (AdbPrivilegedApiHelper.reconnect(app)) {
                ExperimentalCaptureDaemon.collect(app)
                return@withLock true
            }
            val saved = ExperimentalRecording.recoveryAuthorization(app)
            if (!AdbRecoveryPolicy.shouldRetry(AdbRecoveryPolicy.Conditions(
                adbMethodSelected = ExperimentalRecording.method(app) ==
                    ExperimentalRecording.Method.BUILT_IN_ADB,
                recordingEnabled = Prefs.recordingEnabled(app),
                autoRecoveryAllowed = saved.allowed,
                connectionBusy = ExperimentalRecording.status.value.busy,
                adbIdentityPresent = ExperimentalRecording.adbIdentityPresent(app),
                tcpVerifiedBefore = saved.tcpVerified,
                tcpPortListening = ExperimentalRecording.tcpPortListening(),
            ))) {
                // TCP/5555 is gone after a restart. If the user paired Wireless
                // debugging and VRec holds the secure-settings grant, switch it
                // back on; the paired discovery reconnects and restarts the helper.
                val now = SystemClock.elapsedRealtime()
                if (Prefs.recordingEnabled(app) && now >= retryAfterElapsedMs &&
                    !ExperimentalRecording.status.value.connected &&
                    !ExperimentalRecording.status.value.busy &&
                    !ExperimentalRecording.awaitingManualRetry &&
                    AdbSelfRecovery.restoreWireless(app)) {
                    retryAfterElapsedMs = now + RETRY_INTERVAL_MS
                    Log.i("Restoring helper through paired Wireless debugging")
                }
                return@withLock false
            }

            val now = SystemClock.elapsedRealtime()
            if (now < retryAfterElapsedMs) return@withLock false
            retryAfterElapsedMs = now + RETRY_INTERVAL_MS
            val link = ExperimentalRecording.status.value
            if (link.connected && link.shellUid == 2000 && AdbTransport.isConnected) {
                val confirmed = AdbTransport.exec("id -u").getOrNull()
                    ?.lineSequence()?.any { it.trim() == "2000" } == true
                if (confirmed && AdbPrivilegedApiHelper.startWithAdb(app).isSuccess) {
                    ExperimentalCaptureDaemon.collect(app)
                    return@withLock true
                }
            }
            if (link.connected) {
                AdbTransport.close()
                ExperimentalRecording.refreshConnectionStatus()
            }
            val result = ExperimentalRecording.connect(app, automatic = true)
            if (result.isSuccess) {
                ExperimentalCaptureDaemon.collect(app)
                Log.i("Restored helper through authorized USB-bootstrapped localhost ADB")
                return@withLock true
            }
            Log.i("Enhanced helper needs authorized USB-bootstrapped ADB")
            false
        }
    }
}
