package com.vrec.app.experimental.daemon

import android.content.Context
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.AdbPermissionBootstrap
import com.vrec.app.Log
import com.vrec.app.recording.RecordingFailureNotice
import kotlinx.coroutines.CancellationException
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
        if (ExperimentalCaptureDaemon.runningBuildStatus(context.applicationContext) ==
            ShellHelperBuildPolicy.Decision.STALE) {
            state.value = State.NEEDS_ADB
            ExperimentalCaptureDaemon.ready.value = false
            Log.i("ADB helper: running daemon is from an older APK; awaiting safe upgrade over verified ADB")
            return@withLock false
        }
        val armed = Prefs.recordingEnabled(context)
        val ok = ExperimentalCaptureDaemon.setMode(context.applicationContext, armed)
        state.value = if (ok) State.RUNNING else State.ERROR
        if (ok) RecordingFailureNotice.clearAfterHelperRecovered(context.applicationContext)
        ok
    }

    /**
     * The sole helper-start entry point. Do not launch directly from Home,
     * pairing or recovery: each must reverify the live shell and configure
     * eligible permissions first. A denied optional permission is not a fatal
     * helper failure.
     */
    suspend fun startWithAdb(context: Context, manualRepair: Boolean = false): Result<Unit> = lock.withLock {
        val app = context.applicationContext
        val connection = ExperimentalRecording.status.value
        if (!connection.connected || connection.shellUid != 2000 ||
            !AdbTransport.isConnected) {
            state.value = State.NEEDS_ADB
            return@withLock Result.failure(IllegalStateException(
                "ADB shell is not connected and verified as UID 2000. Reconnect or repair ADB first."
            ))
        }
        state.value = State.STARTING
        val startup = try {
            AdbHelperStartFlow.run(object : AdbHelperStartFlow.Steps {
            override suspend fun verifyUid2000(): Boolean =
                AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()
                    ?.lineSequence()?.any { it.trim() == "2000" } == true

            override suspend fun configurePermissions(): List<String> =
                AdbPermissionBootstrap.grantEligible(app).unavailable

            override suspend fun ensureHelperStarted(): Result<Unit> =
                ExperimentalCaptureDaemon.ensureStarted(app, manualRepair = manualRepair)

            override suspend fun probeHelper(): Boolean =
                ExperimentalCaptureDaemon.probe(app)
        })
        } catch (cancelled: CancellationException) {
            // The foreground operation may be cancelled by Quit. Never leave
            // STARTING stuck on-screen, and don't report a false launch error.
            state.value = State.STOPPED
            ExperimentalCaptureDaemon.ready.value = false
            throw cancelled
        }
        when (val outcome = startup) {
            is AdbHelperStartFlow.Outcome.Ready -> {
                state.value = State.RUNNING
                ExperimentalCaptureDaemon.ready.value = true
                RecordingFailureNotice.clearAfterHelperRecovered(app)
                if (outcome.unavailablePermissions.isNotEmpty()) {
                    Log.i("ADB helper started; ${outcome.unavailablePermissions.size} optional Android permissions still require attention")
                }
                Result.success(Unit)
            }
            is AdbHelperStartFlow.Outcome.Failed -> {
                state.value = if (outcome.stage == AdbHelperStartFlow.Stage.VERIFY_SHELL)
                    State.NEEDS_ADB else State.ERROR
                ExperimentalCaptureDaemon.ready.value = false
                Log.w("ADB helper startup ${outcome.stage}: ${outcome.reason}")
                Result.failure(IllegalStateException(
                    "Helper ${outcome.stage.name.lowercase().replace('_', ' ')} failed: ${outcome.reason}"
                ))
            }
        }
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
        val stale = available &&
            ExperimentalCaptureDaemon.runningBuildStatus(app) == ShellHelperBuildPolicy.Decision.STALE
        val armed = available && Prefs.recordingEnabled(app)
        val verified = available && !stale && ExperimentalCaptureDaemon.setMode(app, armed)
        state.value = HelperHealthPolicy.nextState(available, verified)
        ExperimentalCaptureDaemon.ready.value = verified
        if (verified) RecordingFailureNotice.clearAfterHelperRecovered(app)
        state.value
    }
}
