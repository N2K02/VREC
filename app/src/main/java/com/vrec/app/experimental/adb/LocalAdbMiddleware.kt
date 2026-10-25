package com.vrec.app.experimental.adb

import android.content.Context
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
    val message = MutableStateFlow("Tap Set up automatically to check ADB access on this phone.")
    private val mutex = Mutex()

    /** Termux-style local commands, executed without a companion app. Not privileged. */
    suspend fun runLocal(diagnostic: LocalDiagnostic): Result<LocalResult> =
        withContext(Dispatchers.IO) {
            runCatching {
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
    suspend fun setup(context: Context): Boolean = mutex.withLock {
        val app = context.applicationContext
        busy.value = true
        try {
            // Detached helper can survive ADB disconnect and needs no new bootstrap.
            if (AdbPrivilegedApiHelper.reconnect(app)) {
                message.value = "Enhanced recording helper is connected and ready."
                return@withLock true
            }
            val session = ExperimentalRecording.status.value
            val next = LocalAdbSetupPolicy.next(LocalAdbSetupPolicy.Signals(
                adbModeSelected = true,
                helperHealthy = ExperimentalCaptureDaemon.ready.value &&
                    AdbPrivilegedApiHelper.state.value == AdbPrivilegedApiHelper.State.RUNNING,
                verifiedAdbSession = session.connected && session.shellUid == 2000 &&
                    AdbTransport.isConnected,
                usbDebugging = ExperimentalRecording.usbDebuggingFlag(app) == true,
                tcpPortListening = ExperimentalRecording.tcpPortListening(),
            ))
            when (next) {
                LocalAdbSetupPolicy.Next.READY -> {
                    message.value = "Enhanced recording helper is ready."
                    true
                }
                LocalAdbSetupPolicy.Next.USE_VERIFIED_SESSION -> {
                    val permissions = AdbPermissionBootstrap.grantEligible(app)
                    val result = AdbPrivilegedApiHelper.startWithAdb(app)
                    message.value = if (result.isSuccess)
                        "Enhanced helper started. Permissions needing attention: ${permissions.unavailable.size}"
                        else "Could not start helper: ${result.exceptionOrNull()?.message}"
                    result.isSuccess
                }
                LocalAdbSetupPolicy.Next.CONNECT_TCP -> {
                    message.value = "Checking USB-bootstrapped localhost TCP/5555…"
                    val result = ExperimentalRecording.connect(app, automatic = true)
                    message.value = if (result.isSuccess) "Authorized ADB connected; helper started."
                        else "ADB could not connect: ${result.exceptionOrNull()?.message}. Open USB setup."
                    result.isSuccess
                }
                LocalAdbSetupPolicy.Next.NEED_USB_HOST -> {
                    message.value = "USB debugging is ON, but Android has no local authorized ADB endpoint. A trusted USB ADB host is required once to enable TCP/5555; VRec, Termux and a container cannot do this alone."
                    false
                }
                LocalAdbSetupPolicy.Next.ENABLE_USB_DEBUGGING -> {
                    message.value = "Enable USB debugging in Developer options and retry."
                    false
                }
                LocalAdbSetupPolicy.Next.SELECT_ADB_MODE -> false
            }
        } catch (t: Throwable) {
            message.value = "ADB setup failed: ${t.message ?: "unknown error"}. Open ADB setup."
            false
        } finally {
            busy.value = false
        }
    }
}
