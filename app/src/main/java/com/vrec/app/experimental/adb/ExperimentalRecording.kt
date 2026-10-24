package com.vrec.app.experimental.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
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
    data class Status(
        val message: String = "Standard recording available.",
        val pairingPort: Int? = null,
        val shellUid: Int? = null,
        val connected: Boolean = false,
        val tcpPortOpen: Boolean = false,
        val busy: Boolean = false,
    )
    val status = MutableStateFlow(Status())
    private const val PREFERENCES = "vrec_experimental_recorder"
    private const val METHOD_KEY = "recorder_method"

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
            .edit().putString(METHOD_KEY, method.name).apply()
    }

    fun pairedLocally(context: Context): Boolean = AdbIdentity.exists(context)

    fun wirelessDebuggingFlag(context: Context): Boolean? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) null
        else Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrNull()

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

    suspend fun connect(context: Context, manualPort: Int? = null): Result<Int> {
        status.value = status.value.copy(busy = true, message = "Connecting to ADB…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (manualPort == null)
                    AdbTransport.autoConnect(context.applicationContext, timeoutMs = 6000).getOrThrow()
                else {
                    require(manualPort in 1..65535)
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
        status.value = status.value.copy(
            busy = false, connected = result.isSuccess, shellUid = result.getOrNull(),
            tcpPortOpen = tcpPresent(), message = result.fold(
                onSuccess = {
                    if (manualPort == 5555) "ADB verified over TCP/5555 (shell UID 2000)."
                    else "ADB verified: shell UID 2000."
                },
                onFailure = { "Connection failed: " + it.message }
            )
        )
        return result
    }

    suspend fun enableTcp(context: Context): Result<Unit> {
        status.value = status.value.copy(busy = true, message = "Switching ADB to TCP/5555…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                // adbd is expected to drop the old TLS session on tcpip:5555.
                // Do not interpret that deliberate disconnection as a failure.
                AdbTransport.switchToTcp(5555).getOrThrow()
                AdbTransport.close()

                var lastFailure: Throwable? = null
                var verified = false
                // Restart duration varies across Android versions and OEMs.
                for (attempt in 1..8) {
                    delay(1500L)
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
        val open = tcpPresent()
        val ok = result.isSuccess && open
        status.value = status.value.copy(
            busy = false, connected = ok, tcpPortOpen = open,
            shellUid = if (ok) 2000 else null,
            message = if (ok)
                "Connected via TCP/5555. The original Wireless debugging connection was replaced."
            else
                "ADB restarted but TCP could not be verified. Tap Connect TCP/5555 to retry; " +
                    "if it is unavailable, turn Wireless debugging back on and reconnect. " +
                    (result.exceptionOrNull()?.message ?: "")
        )
        return if (ok) Result.success(Unit)
            else Result.failure(result.exceptionOrNull() ?: IllegalStateException("TCP unavailable"))
    }

    suspend fun disableTcp(): Result<Unit> {
        status.value = status.value.copy(busy = true, message = "Restoring USB debugging mode…")
        val result = AdbTransport.switchToUsb()
        status.value = status.value.copy(
            busy = false, connected = false, shellUid = null, tcpPortOpen = tcpPresent(),
            message = if (result.isSuccess) "USB mode requested; disable debugging in Android Settings if finished."
                else "TCP disable failed: " + result.exceptionOrNull()?.message
        )
        return result
    }

}
