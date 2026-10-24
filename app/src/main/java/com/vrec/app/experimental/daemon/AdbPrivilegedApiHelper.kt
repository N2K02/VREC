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
    enum class State { STOPPED, RUNNING, NEEDS_ADB, ERROR }
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
        ok
    }

    suspend fun startWithAdb(context: Context): Result<Unit> = lock.withLock {
        if (ExperimentalRecording.method(context) != ExperimentalRecording.Method.BUILT_IN_ADB) {
            state.value = State.NEEDS_ADB
            return@withLock Result.failure(IllegalStateException("Select Built-in ADB first"))
        }
        val result = ExperimentalCaptureDaemon.ensureStarted(context.applicationContext)
        state.value = when {
            result.isFailure -> State.NEEDS_ADB
            ExperimentalCaptureDaemon.probe(context.applicationContext) -> State.RUNNING
            else -> State.ERROR
        }
        result
    }

    suspend fun checkHealth(context: Context): State {
        state.value = if (ExperimentalCaptureDaemon.probe(context.applicationContext))
            State.RUNNING else State.NEEDS_ADB
        return state.value
    }
}
