package com.vrec.app.experimental.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.calls.CallDetectionAccess
import com.vrec.app.Prefs
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Privilege/setup controller, intentionally independent from the standard audio recorder. */
object ExperimentalRecording {
    enum class Method { STANDARD, BUILT_IN_ADB }
    /** The transport used for authorization is not the same as a listening port. */
    enum class Transport { WIRELESS, TCP }
    data class Status(
        val message: String = "Standard recording available.",
        val pairingPort: Int? = null,
        val shellUid: Int? = null,
        val connected: Boolean = false,
        /** Only set after a connection has proven Android shell UID 2000. */
        val connectedTransport: Transport? = null,
        /** What the user chose/last attempted, including an unsuccessful TCP switch. */
        val requestedTransport: Transport? = null,
        /** Socket presence only; never grants access to the shell helper. */
        val tcpPortOpen: Boolean = false,
        val busy: Boolean = false,
    )
    val status = MutableStateFlow(Status())
    private const val PREFERENCES = "vrec_experimental_recorder"
    private const val METHOD_KEY = "recorder_method"
    private const val TRANSPORT_KEY = "last_adb_transport"
    private const val AUTO_RECOVERY_KEY = "auto_reconnect_after_verified_adb"
    private const val VERIFIED_WIRELESS_KEY = "verified_wireless_adb"
    private const val VERIFIED_TCP_KEY = "verified_tcp_adb"

    data class RecoveryAuthorization(
        val allowed: Boolean,
        val wirelessVerified: Boolean,
        val tcpVerified: Boolean,
    )

    fun recoveryAuthorization(context: Context): RecoveryAuthorization {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return RecoveryAuthorization(
            allowed = prefs.getBoolean(AUTO_RECOVERY_KEY, false),
            wirelessVerified = prefs.getBoolean(VERIFIED_WIRELESS_KEY, false),
            tcpVerified = prefs.getBoolean(VERIFIED_TCP_KEY, false),
        )
    }

    private fun rememberVerifiedTransport(context: Context, transport: Transport) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_RECOVERY_KEY, true)
            .putBoolean(if (transport == Transport.WIRELESS) VERIFIED_WIRELESS_KEY else VERIFIED_TCP_KEY, true)
            .putString(TRANSPORT_KEY, transport.name)
            .apply()
    }

    fun preferredTransport(context: Context): Transport {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(TRANSPORT_KEY, null)
        return Transport.entries.firstOrNull { it.name == value }
            ?: if (Build.VERSION.SDK_INT >= 30) Transport.WIRELESS else Transport.TCP
    }

    fun selectTransport(context: Context, transport: Transport) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(TRANSPORT_KEY, transport.name).apply()
        status.value = status.value.copy(requestedTransport = transport)
    }

    /** Reconcile after returning from Developer options, without trusting a TCP socket as ADB. */
    suspend fun refreshConnectionStatus() {
        val transportConnected = withContext(Dispatchers.IO) { AdbTransport.isConnected }
        val tcpListening = withContext(Dispatchers.IO) { tcpPresent() }
        val current = status.value
        if (current.busy) return
        status.value = if (current.connected && !transportConnected) {
            current.copy(connected = false, connectedTransport = null, shellUid = null,
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

    fun pairedLocally(context: Context): Boolean = AdbIdentity.exists(context)

    fun wirelessDebuggingFlag(context: Context): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null
        else Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrNull()

    fun usbDebuggingFlag(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrNull()

    /** For endpoint discovery only, never as evidence of ADB authorization. */
    fun tcpPortListening(): Boolean = tcpPresent()

    private fun tcpPresent(): Boolean = runCatching {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", 5555), 600)
            true
        }
    }.getOrDefault(false)

    suspend fun discover(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        status.value = status.value.copy(busy = true, message = "Looking for the pairing dialog…")
        val result = runCatching {
            require(Build.VERSION.SDK_INT >= 30) { "Wireless debugging needs Android 11 or newer." }
            AdbTransport.discoverPort(context.applicationContext,
                AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs = 8000)
                ?: error("No pairing dialog found. Open Android's six-digit pairing screen.")
        }
        status.value = status.value.copy(busy = false, pairingPort = result.getOrNull(),
            message = result.fold(
                onSuccess = { "Pairing port " + it + " detected." },
                onFailure = { it.message ?: "Pairing port not found." }
            ))
        result
    }

    suspend fun pair(context: Context, port: Int, code: String): Result<Unit> {
        require(code.matches(Regex("[0-9]{6}"))) { "Enter six digits from Android's pairing screen." }
        require(port in 1..65535) { "Pairing port must be 1–65535." }
        status.value = status.value.copy(busy = true, message = "Pairing with Android…")
        val result = AdbTransport.pair(context.applicationContext, "127.0.0.1", port, code)
        status.value = status.value.copy(busy = false,
            message = result.fold(
                onSuccess = { "Paired. Tap Connect to verify shell access." },
                onFailure = { "Pairing failed: " + it.message }
            ))
        return result
    }

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
        return if (confirmed) Result.success(Unit)
            else Result.failure(result.exceptionOrNull()
                ?: IllegalStateException("Android did not confirm advanced detection permission."))
    }

    suspend fun connect(context: Context, manualPort: Int? = null,
                        wirelessOnly: Boolean = false, automatic: Boolean = false): Result<Int> {
        val requested = if (wirelessOnly) Transport.WIRELESS else Transport.TCP
        if (automatic) {
            if (status.value.busy) return Result.failure(IllegalStateException("ADB setup is in progress."))
        } else {
            selectTransport(context, requested)
        }
        status.value = status.value.copy(busy = true, connected = false,
            connectedTransport = null, requestedTransport = requested, shellUid = null,
            message = if (wirelessOnly) "Connecting via Wireless debugging…"
                else "Connecting to TCP/5555. Approve VREC's Android ADB authorization prompt if it appears.")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (manualPort == null)
                    AdbTransport.autoConnect(context.applicationContext, timeoutMs = 6000,
                        allowLegacyTcpFallback = !wirelessOnly).getOrThrow()
                else {
                    require(manualPort in 1..65535)
                    require(!wirelessOnly || manualPort != 5555) {
                        "Port 5555 uses TCP debugging. Select TCP/5555 instead."
                    }
                    // A port-specific connection must not reuse a stale wireless
                    // session and report it as a verified TCP session.
                    AdbTransport.close()
                    AdbTransport.connect(context.applicationContext, "127.0.0.1", manualPort).getOrThrow()
                }
                val response = AdbTransport.exec("id -u").getOrThrow()
                val uid = response.lineSequence().mapNotNull { it.trim().toIntOrNull() }
                    .firstOrNull() ?: error("ADB returned no shell UID")
                check(uid == 2000) { "Connected UID " + uid + " is not Android shell UID 2000." }
                uid
            }
        }
        if (result.isFailure) {
            status.value = status.value.copy(
                busy = false, connected = false, shellUid = null, connectedTransport = null,
                tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
                message = "ADB connection failed: " + result.exceptionOrNull()?.message,
            )
            return result
        }
        // Shell UID 2000 was verified. An explicit connection enables future
        // retries to this transport; background recovery cannot authorize a new one.
        if (method(context) == Method.BUILT_IN_ADB) {
            rememberVerifiedTransport(context, requested)
        }
        // A user explicitly selected Built-in ADB and successfully verified
        // shell UID 2000. Start or reattach the privileged Java helper now,
        // without another button or an unattended ADB bootstrap.
        status.value = status.value.copy(
            busy = true, connected = true, shellUid = 2000, connectedTransport = requested,
            tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
            message = "ADB authorized via ${if (wirelessOnly) "Wireless debugging" else "TCP/5555"}. Starting enhanced helper…",
        )
        val helper = if (method(context) == Method.BUILT_IN_ADB)
            AdbPrivilegedApiHelper.startWithAdb(context)
        else Result.success(Unit)
        // The ADB selection includes consent to configure advanced Telecom
        // detection. Only issue the AppOp command on this verified shell link.
        // Automated recovery only restores a helper the user had authorized.
        // Do not silently re-grant an AppOp the user may have revoked.
        val telecom = if (!automatic && method(context) == Method.BUILT_IN_ADB)
            grantAdvancedDetection(context)
        else Result.success(Unit)
        status.value = status.value.copy(
            busy = false,
            message = when {
                helper.isFailure -> "ADB verified, but enhanced helper failed: " +
                    (helper.exceptionOrNull()?.message ?: "unknown error")
                telecom.isFailure -> "ADB and helper ready; advanced call detection still needs access: " +
                    (telecom.exceptionOrNull()?.message ?: "permission not confirmed")
                automatic && method(context) == Method.BUILT_IN_ADB ->
                    "ADB verified (shell UID 2000). Enhanced helper automatically restored."
                method(context) == Method.BUILT_IN_ADB ->
                    "ADB verified (shell UID 2000). Helper ready; advanced call detection enabled."
                else -> "ADB verified (shell UID 2000)."
            },
        )
        return if (helper.isSuccess) result
            else Result.failure(helper.exceptionOrNull()
                ?: IllegalStateException("Enhanced helper failed to start"))
    }

    /** Ends only VRec's ADB session. The detached shell recorder is not stopped. */
    suspend fun disconnect(context: Context) {
        // A deliberate Disconnect must not be undone by the foreground retry loop.
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTO_RECOVERY_KEY, false).apply()
        status.value = status.value.copy(busy = true, message = "Disconnecting ADB…")
        AdbTransport.close()
        status.value = status.value.copy(
            busy = false, connected = false, shellUid = null, connectedTransport = null,
            tcpPortOpen = withContext(Dispatchers.IO) { tcpPresent() },
            message = "ADB disconnected. The enhanced helper may keep running; " +
                "use Home to turn recording on or off.",
        )
    }

    suspend fun enableTcp(context: Context): Result<Unit> {
        // Consent happens in the UI. Do not restart adbd from an unauthenticated
        // socket or a stale connection.
        val authorized = status.value.connected && status.value.shellUid == 2000 &&
            withContext(Dispatchers.IO) { AdbTransport.isConnected }
        if (!authorized) return Result.failure(IllegalStateException(
            "Authorize Wireless ADB first, or use one-time USB setup if Wireless debugging is unavailable."
        ))
        val previousTransport = status.value.connectedTransport
        var commandSent = false
        selectTransport(context, Transport.TCP)
        status.value = status.value.copy(busy = true,
            message = "Switching ADB to TCP/5555; Android may restart adbd…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // adbd is expected to drop the old TLS session on tcpip:5555.
                // Do not interpret that deliberate disconnection as a failure.
                AdbTransport.switchToTcp(5555).getOrThrow()
                commandSent = true
                AdbTransport.close()

                var lastFailure: Throwable? = null
                var verified = false
                // Restart duration varies across Android versions and OEMs.
                for (attempt in 1..8) {
                    delay(1500L)
                    status.value = status.value.copy(
                        message = "Verifying TCP/5555 (attempt $attempt/8). " +
                            "If Android asks to authorize this ADB key, unlock the phone and tap Allow."
                    )
                    if (!tcpPresent()) continue
                    // A failed TLS/RSA handshake can poison libadb's manager;
                    // use a new one on each attempt until the shell is verified.
                    AdbTransport.close()
                    val probe = runCatching {
                        AdbTransport.connect(context.applicationContext, "127.0.0.1", 5555).getOrThrow()
                        val uid = AdbTransport.exec("id -u").getOrThrow()
                        check(uid.lineSequence().any { line -> line.trim() == "2000" }) {
                            "TCP connected but Android shell UID was not verified"
                        }
                    }
                    if (probe.isSuccess) {
                        verified = true
                        break
                    }
                    lastFailure = probe.exceptionOrNull()
                }
                check(verified && tcpPresent()) {
                    "TCP/5555 did not become available after ADB restarted." +
                        (lastFailure?.message?.let { " $it" } ?: "")
                }
            }
        }
        val open = withContext(Dispatchers.IO) { tcpPresent() }
        val ok = result.isSuccess && open
        if (ok) {
            rememberVerifiedTransport(context, Transport.TCP)
            status.value = status.value.copy(
                busy = true, connected = true, shellUid = 2000,
                connectedTransport = Transport.TCP, tcpPortOpen = true,
                message = "TCP/5555 verified. Starting enhanced helper…",
            )
            val helper = if (method(context) == Method.BUILT_IN_ADB)
                AdbPrivilegedApiHelper.startWithAdb(context)
            else Result.success(Unit)
            val telecom = if (method(context) == Method.BUILT_IN_ADB)
                grantAdvancedDetection(context)
            else Result.success(Unit)
            status.value = status.value.copy(
                busy = false, connected = true, shellUid = 2000,
                connectedTransport = Transport.TCP, tcpPortOpen = true,
                message = when {
                    helper.isFailure -> "TCP/5555 connected, but enhanced helper failed: " +
                        (helper.exceptionOrNull()?.message ?: "unknown error")
                    telecom.isFailure -> "TCP/5555 and helper ready; advanced call detection needs access: " +
                        (telecom.exceptionOrNull()?.message ?: "permission not confirmed")
                    else -> "TCP/5555 verified. Helper ready; advanced call detection enabled."
                },
            )
            return helper
        }
        // A rejected restart request may leave the previously authorized Wireless
        // session intact. Do not show "Disconnected" unless that link was truly lost.
        val previousStillAuthorized = if (!commandSent && previousTransport != null) {
            withContext(Dispatchers.IO) {
                AdbTransport.isConnected && runCatching {
                    AdbTransport.exec("id -u").getOrThrow().lineSequence()
                        .any { it.trim() == "2000" }
                }.getOrDefault(false)
            }
        } else false
        status.value = status.value.copy(
            busy = false,
            connected = previousStillAuthorized,
            connectedTransport = if (previousStillAuthorized) previousTransport else null,
            tcpPortOpen = open,
            shellUid = if (previousStillAuthorized) 2000 else null,
            message = when {
                previousStillAuthorized ->
                    "TCP switch failed, but the existing Wireless ADB connection remains authorized. " +
                        "You can retry Enable TCP. " + (result.exceptionOrNull()?.message ?: "")
                !commandSent ->
                    "ADB did not accept the TCP switch command. Reconnect an authorized " +
                        "ADB session and retry. " + (result.exceptionOrNull()?.message ?: "")
                open ->
                    "TCP/5555 is listening, but ADB shell authorization was not verified. " +
                        "Tap Connect TCP/5555 to retry. " +
                        (result.exceptionOrNull()?.message ?: "")
                else ->
                    "Android did not expose TCP/5555 after the restart. " +
                        "Reconnect Wireless debugging or use one-time USB setup. " +
                        (result.exceptionOrNull()?.message ?: "")
            },
        )
        return Result.failure(result.exceptionOrNull() ?: IllegalStateException("TCP unavailable"))
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
            busy = false, connected = false, connectedTransport = null, shellUid = null,
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
