package com.vrec.app.experimental.daemon

import android.content.Context
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.ExperimentalRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages VRec's ADB-bootstrapped Java shell service, without Shizuku.
 * ADB/TCP is only required to start a missing service. An authenticated
 * loopback channel remains usable while the shell process is alive.
 * This does not grant root or bypass Android audio restrictions.
 */
object AdbPrivilegedApiHelper {
    enum class State { STOPPED, STARTING, RUNNING, NEEDS_ADB, ERROR }
    val state = MutableStateFlow(State.STOPPED)
    private val lock = Mutex()

    suspend fun reconnect(context: Context): Boolean = lock.withLock {
        if (!ExperimentalCaptureDaemon.probe(context.applicationContext)) {
            state.value = State.NEEDS_ADB
            return@withLock false
        }
        val armed = Prefs.recordingEnabled(context)
        val ok = ExperimentalCaptureDaemon.setMode(context.applicationContext, armed)
        state.value = if (ok) State.RUNNING else State.ERROR
        ok
    }

    suspend fun startWithAdb(context: Context): Result<Unit> = lock.withLock {
        // No auto-bootstrap from the background or from an unverified TCP port.
        // Only the explicit ADB connection flow, after verifying shell UID 2000,
        // can launch a new shell Java process.
        val connection = ExperimentalRecording.status.value
        if (!connection.connected || connection.shellUid != 2000) {
            state.value = State.NEEDS_ADB
            return@withLock Result.failure(IllegalStateException(
                "Connect to USB-bootstrapped localhost TCP/5555 first (shell UID 2000)."
            ))
        }
        state.value = State.STARTING
        val result = ExperimentalCaptureDaemon.ensureStarted(context.applicationContext)
        if (result.isFailure) {
            state.value = State.ERROR
            return@withLock result
        }
        if (!ExperimentalCaptureDaemon.probe(context.applicationContext)) {
            state.value = State.ERROR
            return@withLock Result.failure(IllegalStateException(
                "ADB connected, but the enhanced helper could not be verified."
            ))
        }
        state.value = State.RUNNING
        Result.success(Unit)
    }

    /** Stops the shell helper unless a call or import is in progress. */
    suspend fun stop(context: Context): ExperimentalCaptureDaemon.QuitResult = lock.withLock {
        val result = ExperimentalCaptureDaemon.quit(context.applicationContext)
        if (result == ExperimentalCaptureDaemon.QuitResult.STOPPED ||
            result == ExperimentalCaptureDaemon.QuitResult.NOT_RUNNING) {
            ExperimentalCaptureDaemon.ready.value = false
            state.value = State.STOPPED
        }
        result
    }

    suspend fun checkHealth(context: Context): State = lock.withLock {
        val app = context.applicationContext
        val available = ExperimentalCaptureDaemon.probe(app)
        // A process-level ERROR may be stale (permission or Telecom mode was
        // fixed since the last probe). Re-verify the authenticated mode handshake
        // before declaring RUNNING. A plain PONG must NEVER reset ERROR.
        val armed = available && Prefs.recordingEnabled(app)
        val verified = available && ExperimentalCaptureDaemon.setMode(app, armed)
        state.value = HelperHealthPolicy.nextState(available, verified)
        ExperimentalCaptureDaemon.ready.value = verified
        state.value
    }
}
