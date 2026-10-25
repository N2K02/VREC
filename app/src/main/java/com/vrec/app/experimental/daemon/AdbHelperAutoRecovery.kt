package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.AdbRecoveryPolicy
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reattach the authenticated loopback helper first. If it died, attempt a new
 * bootstrap only after the user previously verified that transport via UID 2000
 * and did not deliberately choose Disconnect or Standard mode.
 *
 * Never changes Android's debugging settings, enables TCP, pairs automatically,
 * or approves the system's ADB authorization prompt.
 */
object AdbHelperAutoRecovery {
    private const val RETRY_INTERVAL_MS = 60_000L
    private val mutex = Mutex()
    private var retryAfterElapsedMs = 0L

    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            // Reattachment works even after ADB itself disconnects; it also
            // reconciles Home Off before considering any new shell process.
            if (AdbPrivilegedApiHelper.reconnect(app)) return@withLock true

            val saved = ExperimentalRecording.recoveryAuthorization(app)
            val preferred = if (ExperimentalRecording.preferredTransport(app) ==
                ExperimentalRecording.Transport.TCP) AdbRecoveryPolicy.Endpoint.TCP
                else AdbRecoveryPolicy.Endpoint.WIRELESS
            val candidates = AdbRecoveryPolicy.availableEndpoints(
                AdbRecoveryPolicy.Conditions(
                    adbMethodSelected = ExperimentalRecording.method(app) ==
                        ExperimentalRecording.Method.BUILT_IN_ADB,
                    recordingEnabled = Prefs.recordingEnabled(app),
                    autoRecoveryAllowed = saved.allowed,
                    connectionBusy = ExperimentalRecording.status.value.busy,
                    pairedIdentityPresent = ExperimentalRecording.pairedLocally(app),
                    wirelessVerifiedBefore = saved.wirelessVerified,
                    tcpVerifiedBefore = saved.tcpVerified,
                    wirelessDebuggingEnabled = ExperimentalRecording.wirelessDebuggingFlag(app) == true,
                    usbDebuggingEnabled = ExperimentalRecording.usbDebuggingFlag(app) == true,
                    tcpPortListening = ExperimentalRecording.tcpPortListening(),
                    preferred = preferred,
                )
            )
            if (candidates.isEmpty()) return@withLock false

            val now = SystemClock.elapsedRealtime()
            if (now < retryAfterElapsedMs) return@withLock false
            // A failed authorization or lost network must not trigger an ADB
            // handshake every few seconds while the app stays open.
            retryAfterElapsedMs = now + RETRY_INTERVAL_MS

            val connection = ExperimentalRecording.status.value
            if (connection.connected && connection.shellUid == 2000 && AdbTransport.isConnected) {
                val uidConfirmed = AdbTransport.exec("id -u").getOrNull()
                    ?.lineSequence()?.any { it.trim() == "2000" } == true
                if (uidConfirmed && AdbPrivilegedApiHelper.startWithAdb(app).isSuccess) {
                    ExperimentalCaptureDaemon.collect(app)
                    Log.i("Automatically restored enhanced helper using authorized ADB")
                    return@withLock true
                }
            }

            // Drop a stale manager before trying a fresh, already-approved
            // endpoint. A live socket alone must never count as shell access.
            if (ExperimentalRecording.status.value.connected) {
                AdbTransport.close()
                ExperimentalRecording.refreshConnectionStatus()
            }
            for (candidate in candidates) {
                val result = ExperimentalRecording.connect(
                    app,
                    manualPort = if (candidate == AdbRecoveryPolicy.Endpoint.TCP) 5555 else null,
                    wirelessOnly = candidate == AdbRecoveryPolicy.Endpoint.WIRELESS,
                    automatic = true,
                )
                if (result.isSuccess) {
                    ExperimentalCaptureDaemon.collect(app)
                    Log.i("Automatically restarted enhanced helper via verified ${candidate.name} ADB")
                    return@withLock true
                }
            }
            Log.i("Enhanced helper needs authorized ADB; automatic recovery will retry later")
            false
        }
    }
}
