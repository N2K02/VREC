package com.vrec.app.experimental.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
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
    private const val PREFERENCES = "vrec_experimental_recorder"
    private const val METHOD_KEY = "recorder_method"
    private const val AUTO_RECOVERY_KEY = "auto_reconnect_after_verified_adb"
    private const val VERIFIED_TCP_KEY = "verified_tcp_adb"

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
            .remove("verified_wireless_adb")
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

    fun method(context: Context): Method {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val stored = prefs.getString(METHOD_KEY, Method.STANDARD.name)
        val resolved = Method.entries.firstOrNull { it.name == stored } ?: Method.STANDARD
        // Migrate unsupported legacy settings to Standard safely.
        if (stored != resolved.name) prefs.edit().putString(METHOD_KEY, resolved.name).apply()
        return resolved
    }

    fun select(context: Context, method: Method) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(METHOD_KEY, method.name)
            .apply {
                if (method == Method.STANDARD) putBoolean(AUTO_RECOVERY_KEY, false)
            }.apply()
    }

    fun adbIdentityPresent(context: Context): Boolean = AdbIdentity.exists(context)

    fun usbDebuggingFlag(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrNull()

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
        if (status.value.busy)
            return Result.failure(IllegalStateException("ADB setup is already running"))
        status.value = status.value.copy(busy = true, connected = false, shellUid = null,
            message = "Checking USB-bootstrapped localhost ADB…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                check(tcpPresent()) {
                    "No local ADB endpoint. An authorized USB ADB host must first run adb tcpip 5555."
                }
                AdbTransport.close()
                AdbTransport.connect(context.applicationContext, "127.0.0.1", 5555).getOrThrow()
                val uid = AdbTransport.exec("id -u").getOrThrow().lineSequence()
                    .mapNotNull { it.trim().toIntOrNull() }.firstOrNull()
                    ?: error("ADB returned no shell UID")
                check(uid == 2000) { "ADB did not verify shell UID 2000 (received $uid)" }
                uid
            }
        }
        if (result.isFailure) {
            status.value = status.value.copy(
                busy = false, connected = false, shellUid = null,
                tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
                message = "ADB connection failed: ${result.exceptionOrNull()?.message}",
            )
            return result
        }
        if (method(context) == Method.BUILT_IN_ADB) rememberVerifiedTcp(context)
        status.value = status.value.copy(busy = true, connected = true,
            shellUid = 2000, tcpPortOpen = true,
            message = "ADB shell UID 2000 verified. Starting helper…")
        val helper = if (method(context) == Method.BUILT_IN_ADB)
            AdbPrivilegedApiHelper.startWithAdb(context) else Result.success(Unit)
        val telecom = if (!automatic && method(context) == Method.BUILT_IN_ADB)
            grantAdvancedDetection(context) else Result.success(Unit)
        status.value = status.value.copy(busy = false, message = when {
            helper.isFailure -> "ADB verified, but helper failed: ${helper.exceptionOrNull()?.message}"
            telecom.isFailure -> "Helper ready; advanced call detection still needs access: ${telecom.exceptionOrNull()?.message}"
            else -> "ADB verified (shell UID 2000). Enhanced helper ready."
        })
        return if (helper.isSuccess) result else Result.failure(
            helper.exceptionOrNull() ?: IllegalStateException("Enhanced helper failed"))
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
