package com.vrec.app.experimental.adb

import android.content.Context
import android.os.Build
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import com.vrec.app.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.net.InetSocketAddress
import java.net.Socket

/** Privilege/setup controller, intentionally independent from the standard audio recorder. */
object ExperimentalRecording {
    enum class Method { STANDARD, BUILT_IN_ADB }
    data class Status(
        val message: String = "Standard recording available.",
        val shellUid: Int? = null,
        val connected: Boolean = false,
        /** Socket presence only; never grants access to the shell helper. */
        val tcpPortOpen: Boolean = false,
        val busy: Boolean = false,
    )
    val status = MutableStateFlow(Status())
    @Volatile var awaitingManualRetry: Boolean = false
        private set

    fun allowManualRetry() { awaitingManualRetry = false }
    private const val PREFERENCES = "vrec_experimental_recorder"
    private const val METHOD_KEY = "recorder_method"
    private const val AUTO_RECOVERY_KEY = "auto_reconnect_after_verified_adb"
    private const val VERIFIED_TCP_KEY = "verified_tcp_adb"
    private const val VERIFIED_WIRELESS_KEY = "verified_wireless_adb"

    fun wirelessRecoveryAllowed(context: Context): Boolean =
        wirelessWasPaired(context) && recoveryAuthorization(context).allowed

    fun wirelessWasPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(VERIFIED_WIRELESS_KEY, false)

    private fun rememberVerifiedWireless(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_RECOVERY_KEY, true)
            .putBoolean(VERIFIED_WIRELESS_KEY, true)
            .apply()
    }

    data class RecoveryAuthorization(
        val allowed: Boolean,
        val tcpVerified: Boolean,
    )

    fun recoveryAuthorization(context: Context): RecoveryAuthorization {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return RecoveryAuthorization(
            allowed = prefs.getBoolean(AUTO_RECOVERY_KEY, false),
            tcpVerified = prefs.getBoolean(VERIFIED_TCP_KEY, false),
        )
    }

    private fun rememberVerifiedTcp(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_RECOVERY_KEY, true)
            .putBoolean(VERIFIED_TCP_KEY, true)
            .remove("last_adb_transport")
            .apply()
    }

    /** Reconcile after returning from Developer options, without trusting a TCP socket as ADB. */
    suspend fun refreshConnectionStatus() {
        val transportConnected = withContext(Dispatchers.IO) { AdbTransport.isConnected }
        val tcpListening = withContext(Dispatchers.IO) { tcpPresent() }
        val current = status.value
        if (current.busy) return
        status.value = if (current.connected && !transportConnected) {
            current.copy(connected = false, shellUid = null,
                tcpPortOpen = tcpListening,
                message = "ADB session ended. The recording helper can keep running independently.")
        } else current.copy(tcpPortOpen = tcpListening)
    }

    /** ADB is the single recording mode. Legacy STANDARD choices are ignored. */
    fun method(context: Context): Method = Method.BUILT_IN_ADB

    fun adbIdentityPresent(context: Context): Boolean = AdbIdentity.exists(context)

    /** A TCP listener is not evidence of ADB authorization. */
    fun tcpPortListening(): Boolean = tcpPresent()

    private fun tcpPresent(): Boolean = runCatching {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", 5555), 600)
            true
        }
    }.getOrDefault(false)

    /**
     * Called automatically once the user has explicitly selected ADB and
     * authorized an ADB connection. This is a separate Telecom AppOp, not
     * shell/recording-helper authorization.
     */
    suspend fun grantAdvancedDetection(context: Context): Result<Unit> {
        if (method(context) != Method.BUILT_IN_ADB) {
            return Result.failure(IllegalStateException("Select the ADB recording method first."))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Result.success(Unit)
        val result = if (CallDetectionAccess.isGranted(context)) Result.success(Unit)
            else CallDetectionAccess.grantWithAdb(context)
        val confirmed = result.isSuccess && CallDetectionAccess.isGranted(context)
        Prefs.of(context).edit().putBoolean(
            Prefs.EXPERIMENTAL_CALL_DETECTION, confirmed
        ).apply()
        // The permission may have been granted AFTER the Java helper was
        // launched. Reconfigure that running daemon now, so the first outgoing
        // call waits for Telecom ACTIVE rather than recording the dial tone.
        if (confirmed && com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.ready.value) {
            com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.setMode(
                context.applicationContext, Prefs.recordingEnabled(context))
        }
        return if (confirmed) Result.success(Unit)
            else Result.failure(result.exceptionOrNull()
                ?: IllegalStateException("Android did not confirm advanced detection permission."))
    }

    /** Connect only to USB-bootstrapped localhost TCP/5555, never external hosts. */
    suspend fun connect(context: Context, automatic: Boolean = false): Result<Int> {
        if (automatic && awaitingManualRetry)
            return Result.failure(IllegalStateException("ADB authorization stopped. Tap Retry on Home."))
        if (!automatic) allowManualRetry()
        if (status.value.busy)
            return Result.failure(IllegalStateException("ADB setup is already running"))
        status.value = status.value.copy(busy = true, connected = false, shellUid = null,
            message = "Checking USB-bootstrapped localhost ADB…")
        Log.i("ADB: verifying localhost:5555 authorization (15-second limit)")
        val result: Result<Int> = try {
            withTimeout(15_000L) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        check(tcpPresent()) {
                            "No local ADB endpoint. An authorized USB ADB host must first run adb tcpip 5555."
                        }
                        AdbTransport.close()
                        AdbTransport.connect(context.applicationContext, "127.0.0.1", 5555).getOrThrow()
                        val uid = AdbTransport.exec("id -u", timeoutMs = 3_000).getOrThrow()
                            .lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull()
                            ?: error("ADB returned no shell UID")
                        check(uid == 2000) { "ADB did not verify shell UID 2000 (received $uid)" }
                        uid
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("ADB authorization timed out after 15 seconds. Tap Retry on Home."))
        }
        if (result.isFailure) {
            Log.w("ADB: authorization failed — ${result.exceptionOrNull()?.message ?: "unknown error"}")
            awaitingManualRetry = true
            withContext(Dispatchers.IO) { AdbTransport.close() }
            status.value = status.value.copy(
                busy = false, connected = false, shellUid = null,
                tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
                message = "ADB connection failed: ${result.exceptionOrNull()?.message}",
            )
            return result
        }
        Log.i("ADB: Android shell UID 2000 verified")
        rememberVerifiedTcp(context)
        status.value = status.value.copy(busy = true, connected = true,
            shellUid = 2000, tcpPortOpen = true,
            message = "Android shell UID 2000 verified. Configuring supported permissions…")
        val permissionResult = runCatching {
            AdbPermissionBootstrap.grantEligible(context)
        }.getOrElse { AdbPermissionBootstrap.ResultSummary(
            unavailable = listOf("Permission setup failed: ${it.message}"), finished = true)
        }
        Log.i("ADB: supported permissions verified — ${permissionResult.granted.size} granted, ${permissionResult.unavailable.size} unavailable")
        val helper = AdbPrivilegedApiHelper.startWithAdb(context)
        if (helper.isSuccess) Log.i("ADB: recording helper started and verified")
        else Log.w("ADB: recording helper failed — ${helper.exceptionOrNull()?.message ?: "unknown error"}")
        status.value = status.value.copy(busy = false, message = when {
            helper.isFailure -> "Shell UID 2000 verified, but recorder helper failed: ${helper.exceptionOrNull()?.message}"
            permissionResult.unavailable.isNotEmpty() ->
                "Shell UID 2000 verified; helper ready. Some optional access remains: " +
                    permissionResult.unavailable.joinToString().take(180)
            else -> "Shell UID 2000 verified · Recording helper ready · Permissions configured"
        })
        return if (helper.isSuccess) result else Result.failure(
            helper.exceptionOrNull() ?: IllegalStateException("Enhanced helper failed"))
    }

    /** Completes setup only after the same UID 2000 check used for USB.
     * Wireless connection has a separate dynamic Android TLS port. */
    suspend fun connectWireless(context: Context, port: Int, automatic: Boolean = false): Result<Int> {
        if (automatic && awaitingManualRetry)
            return Result.failure(IllegalStateException("ADB authorization stopped; press Retry."))
        if (status.value.busy)
            return Result.failure(IllegalStateException("ADB setup is already running"))
        if (!automatic) allowManualRetry()
        status.value = status.value.copy(busy = true, connected = false, shellUid = null,
            message = "Connecting to this phone's Wireless debugging TLS endpoint…")
        val attempt: Result<Int> = try {
            withTimeout(15_000L) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        AdbTransport.close()
                        AdbTransport.connectLocalTls(context, port).getOrThrow()
                        val uid = AdbTransport.exec("id -u", timeoutMs = 3_000).getOrThrow()
                            .lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull()
                            ?: error("ADB did not report a shell UID")
                        check(uid == 2000) { "ADB UID $uid is not Android shell UID 2000" }
                        uid
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(IllegalStateException("Wireless ADB authorization timed out after 15 seconds"))
        }
        if (attempt.isFailure) {
            awaitingManualRetry = true
            AdbTransport.close()
            status.value = status.value.copy(busy = false, connected = false, shellUid = null,
                message = "Wireless ADB failed: ${attempt.exceptionOrNull()?.message}. Retry from Home.")
            Log.w("ADB: Wireless connection failed — ${attempt.exceptionOrNull()?.message}")
            return attempt
        }
        rememberVerifiedWireless(context)
        Log.i("ADB: wireless TLS Android shell UID 2000 verified")
        status.value = status.value.copy(busy = true, connected = true,
            shellUid = 2000, message = "Wireless ADB UID 2000 verified; granting eligible permissions…")
        val permissions = runCatching { AdbPermissionBootstrap.grantEligible(context) }
            .getOrElse { AdbPermissionBootstrap.ResultSummary(unavailable =
                listOf("Permission setup failed: ${it.message}"), finished = true) }
        val helper = AdbPrivilegedApiHelper.startWithAdb(context)
        Log.i("ADB: wireless setup ${if (helper.isSuccess) "helper ready" else "helper failed"}; ${permissions.unavailable.size} unavailable permissions")
        status.value = status.value.copy(busy = false, message = when {
            helper.isFailure -> "Wireless UID 2000 verified; helper failed: ${helper.exceptionOrNull()?.message}"
            permissions.unavailable.isNotEmpty() ->
                "Wireless UID 2000 verified; helper ready; permissions needing attention: ${permissions.unavailable.joinToString().take(160)}"
            else -> "Wireless UID 2000 verified; recording helper ready; supported permissions configured"
        })
        return if (helper.isSuccess) attempt else Result.failure(
            helper.exceptionOrNull() ?: IllegalStateException("Recording helper failed"))
    }

    /** Ends only VRec's ADB session. The detached shell recorder is not stopped. */
    suspend fun disconnect(context: Context) {
        // A deliberate Disconnect must not be undone by the foreground retry loop.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_RECOVERY_KEY, false).apply()
        status.value = status.value.copy(busy = true, message = "Disconnecting ADB…")
        AdbTransport.close()
        status.value = status.value.copy(
            busy = false, connected = false, shellUid = null, 
            tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
            message = "ADB disconnected. The enhanced helper may keep running; " +
                "use Home to turn recording on or off.",
        )
    }

    suspend fun disableTcp(context: Context): Result<Unit> {
        val authorized = status.value.connected && status.value.shellUid == 2000 &&
            withContext(Dispatchers.IO) { AdbTransport.isConnected }
        if (!authorized) return Result.failure(IllegalStateException(
            "Connect to an authorized ADB session before disabling TCP/5555."
        ))
        status.value = status.value.copy(busy = true, message = "Returning ADB to USB mode…")
        val result = AdbTransport.switchToUsb()
        // Restarting adbd deliberately disconnects the current ADB session.
        withContext(Dispatchers.IO) { AdbTransport.close() }
        var listening = withContext(Dispatchers.IO) { tcpPresent() }
        if (result.isSuccess) {
            for (attempt in 1..5) {
                if (!listening) break
                delay(500L)
                listening = withContext(Dispatchers.IO) { tcpPresent() }
            }
        }
        status.value = status.value.copy(
            busy = false, connected = false, shellUid = null,
            tcpPortOpen = listening,
            message = when {
                result.isFailure -> "TCP disable failed: " + result.exceptionOrNull()?.message
                listening -> "USB mode requested, but TCP/5555 is still listening. Check Developer options."
                else -> "TCP/5555 stopped. You may disable USB debugging in Android Settings."
            },
        )
        if (result.isSuccess && !listening) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(VERIFIED_TCP_KEY, false).apply()
        }
        return if (result.isFailure) result
            else if (listening) Result.failure(IllegalStateException(
                "TCP/5555 is still listening; verify debugging settings."
            )) else Result.success(Unit)
    }

}
