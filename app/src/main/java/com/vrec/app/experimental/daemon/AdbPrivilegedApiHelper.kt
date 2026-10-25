package com.vrec.app.experimental.daemon

import android.content.Context
import com.vrec.app.Prefs
import com.vrec.app.recording.RecordingService
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
        val armed = Prefs.recordingEnabled(context) &&
            ExperimentalRecording.method(context) == ExperimentalRecording.Method.BUILT_IN_ADB
        val ok = ExperimentalCaptureDaemon.setMode(context.applicationContext, armed)
        state.value = if (ok) State.RUNNING else State.ERROR
        if (ok) RecordingService.requestAdbHandoff(context.applicationContext)
        ok
    }

    suspend fun startWithAdb(context: Context): Result<Unit> = lock.withLock {
        if (ExperimentalRecording.method(context) != ExperimentalRecording.Method.BUILT_IN_ADB) {
            state.value = State.NEEDS_ADB
            return@withLock Result.failure(IllegalStateException("Select Built-in ADB first"))
        }
        // No auto-bootstrap from the background or from an unverified TCP port.
        // Only the explicit ADB connection flow, after verifying shell UID 2000,
        // can launch a new shell Java process.
        val connection = ExperimentalRecording.status.value
        if (!connection.connected || connection.shellUid != 2000) {
            state.value = State.NEEDS_ADB
            return@withLock Result.failure(IllegalStateException(
                "Connect with Wireless debugging or TCP/5555 first (shell UID 2000)."
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
        RecordingService.requestAdbHandoff(context.applicationContext)
        Result.success(Unit)
    }

    suspend fun checkHealth(context: Context): State = lock.withLock {
        val available = ExperimentalCaptureDaemon.probe(context.applicationContext)
        // checkHealth must not resurrect an incompatible helper after reconnect
        // rejected its mode handshake.
        val usable = available && state.value != State.ERROR
        if (!usable) ExperimentalCaptureDaemon.ready.value = false
        state.value = if (usable) State.RUNNING else if (available) State.ERROR else State.NEEDS_ADB
        if (usable) RecordingService.requestAdbHandoff(context.applicationContext)
        state.value
    }
}
