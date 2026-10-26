package com.vrec.app.experimental.adb

import android.content.Context
import com.vrec.app.Log
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Built into VRec: local Android app shell + its existing embedded ADB client.
 * No Termux APK, Linux container, Windows emulator, or downloaded binary.
 *
 * A local command inherits VRec's ordinary app UID, never Android shell UID.
 * Only a separately *authorized* adbd transport runs privileged commands.
 */
object LocalAdbMiddleware {
    data class LocalResult(val exitCode: Int, val output: String)
    enum class LocalDiagnostic(val command: List<String>) {
        APP_UID(listOf("/system/bin/id", "-u")),
        MANUFACTURER(listOf("/system/bin/getprop", "ro.product.manufacturer")),
        ANDROID_VERSION(listOf("/system/bin/getprop", "ro.build.version.release")),
    }

    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("First time? Expand USB instructions below, then tap Set up ADB recording.")
    val lastSetupFailed = MutableStateFlow(false)
    /** Neutral prerequisite state: a USB ADB host must enable localhost TCP first. */
    val needsUsbBootstrap = MutableStateFlow(false)
    private val mutex = Mutex()

    /** Termux-style local commands, executed without a companion app. Not privileged. */
    suspend fun runLocal(diagnostic: LocalDiagnostic): Result<LocalResult> =
        withContext(Dispatchers.IO) {
            catchingNonCancellation {
                val process = ProcessBuilder(diagnostic.command)
                    .redirectErrorStream(true).start()
                try {
                    check(process.waitFor(3, TimeUnit.SECONDS)) { "Local diagnostic timed out" }
                    LocalResult(process.exitValue(),
                        process.inputStream.bufferedReader().use { it.readText().take(4096).trim() })
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        }

    /** Never run adbd commands through the ordinary app shell. */
    suspend fun runAuthorizedAdbCommand(command: String): Result<String> {
        val state = ExperimentalRecording.status.value
        if (!state.connected || state.shellUid != 2000 || !AdbTransport.isConnected) {
            return Result.failure(IllegalStateException(
                "Android ADB shell is not authorized. The local app shell cannot grant access."
            ))
        }
        return AdbTransport.exec(command)
    }

    /** Attempt USB-bootstrapped localhost ADB only; never enable adbd. */
    suspend fun setup(context: Context, userInitiated: Boolean = false): Boolean = mutex.withLock {
        val app = context.applicationContext
        busy.value = true
        lastSetupFailed.value = false
        needsUsbBootstrap.value = false
        if (userInitiated) Log.i("ADB setup: user requested first-time USB bootstrap check")
        if (AdbLifecycle.quitting) { busy.value = false; return@withLock false }
        try {
            if (userInitiated) ExperimentalRecording.allowManualRetry()
            // Detached helper can survive ADB disconnect and needs no new bootstrap.
            if (AdbPrivilegedApiHelper.reconnect(app)) {
                Log.i("ADB setup: previously started recording helper reattached")
                message.value = "Enhanced recording helper is connected and ready."
                return@withLock true
            }
            if (!userInitiated && ExperimentalRecording.awaitingManualRetry) {
                message.value = "Authorization stopped; tap Retry on Home to try again."
                return@withLock false
            }
            val session = ExperimentalRecording.status.value
            val next = LocalAdbSetupPolicy.next(LocalAdbSetupPolicy.Signals(
                adbModeSelected = true,
                helperHealthy = ExperimentalCaptureDaemon.ready.value &&
                    AdbPrivilegedApiHelper.state.value == AdbPrivilegedApiHelper.State.RUNNING,
                verifiedAdbSession = session.connected && session.shellUid == 2000 &&
                    AdbTransport.isConnected,
                tcpPortListening = ExperimentalRecording.tcpPortListening(),
            ))
            val success = when (next) {
                LocalAdbSetupPolicy.Next.READY -> {
                    message.value = "Enhanced recording helper is ready."
                    if (userInitiated) Log.i("ADB setup: recording helper already ready")
                    true
                }
                LocalAdbSetupPolicy.Next.USE_VERIFIED_SESSION -> {
                    Log.i("ADB setup: shell already verified; restoring permissions and helper")
                    val permissions = AdbPermissionBootstrap.grantEligible(app)
                    val result = AdbPrivilegedApiHelper.startWithAdb(app)
                    message.value = if (result.isSuccess)
                        "Enhanced helper started. Permissions needing attention: ${permissions.unavailable.size}"
                        else "Could not start helper: ${result.exceptionOrNull()?.message}"
                    if (result.isSuccess) Log.i("ADB setup: existing shell session restored helper")
                    else Log.w("ADB setup: helper restore failed: ${result.exceptionOrNull()?.message}")
                    result.isSuccess
                }
                LocalAdbSetupPolicy.Next.CONNECT_TCP -> {
                    message.value = "Checking USB-bootstrapped localhost TCP/5555…"
                    Log.i("ADB setup: localhost TCP/5555 detected; requesting Android authorization")
                    val result = ExperimentalRecording.connect(app, automatic = !userInitiated)
                    message.value = if (result.isSuccess) "Authorized ADB connected; helper started."
                        else "ADB could not connect: ${result.exceptionOrNull()?.message}. Tap Retry on Home."
                    result.isSuccess
                }
                LocalAdbSetupPolicy.Next.NEED_USB_HOST -> {
                    needsUsbBootstrap.value = true
                    message.value = "No ADB endpoint at localhost:5555. Use a trusted USB-connected computer: enable USB debugging, approve the host, then run adb devices and adb tcpip 5555. Return here to connect."
                    if (userInitiated) Log.i("ADB setup: localhost TCP/5555 missing; USB host bootstrap required (not an authorization failure)")
                    false
                }
                LocalAdbSetupPolicy.Next.SELECT_ADB_MODE -> false
            }
            lastSetupFailed.value = !success && !needsUsbBootstrap.value
            success
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            // Quit or a closed screen cancelled setup: stop here, no failure banner,
            // no stale message. The transport is closed by whoever cancelled.
            throw c
        } catch (t: Throwable) {
            lastSetupFailed.value = true
            Log.w("ADB setup failed: ${t.message ?: "unknown error"}", t)
            message.value = "ADB setup failed: ${t.message ?: "unknown error"}. Tap Retry on Home."
            false
        } finally {
            busy.value = false
        }
    }
}
