package com.vrec.app.experimental.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.net.InetSocketAddress
import java.net.Socket

/** Privilege/setup controller, intentionally independent from the standard audio recorder. */
object ExperimentalRecording {
    enum class Method { STANDARD, BUILT_IN_ADB, SHIZUKU }
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

    fun method(context: Context): Method = runCatching {
        Method.valueOf(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(METHOD_KEY, Method.STANDARD.name) ?: Method.STANDARD.name)
    }.getOrDefault(Method.STANDARD)

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
                onSuccess = { "ADB verified: shell UID 2000." },
                onFailure = { "Connection failed: " + it.message }
            )
        )
        return result
    }

    suspend fun enableTcp(context: Context): Result<Unit> {
        status.value = status.value.copy(busy = true, message = "Switching ADB to TCP/5555…")
        val result = withContext(Dispatchers.IO) {
            runCatching {
                AdbTransport.switchToTcp(5555).getOrThrow()
                delay(1500)
                AdbTransport.connect(context.applicationContext, "127.0.0.1", 5555).getOrThrow()
                val uid = AdbTransport.exec("id -u").getOrThrow()
                check(uid.lineSequence().any { it.trim() == "2000" })
            }
        }
        val ok = result.isSuccess && tcpPresent()
        status.value = status.value.copy(
            busy = false, connected = ok, tcpPortOpen = tcpPresent(),
            shellUid = if (ok) 2000 else null,
            message = if (ok) "TCP/5555 connected. This may expose debugging on your Wi-Fi network."
                else "TCP could not be verified: " + (result.exceptionOrNull()?.message ?: "port closed")
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

    fun shizukuStatus(): String {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        return when {
            !running -> "Not running"
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false) -> "Authorized"
            else -> "Needs permission"
        }
    }

    fun requestShizuku() {
        check(Shizuku.pingBinder()) { "Shizuku is not running." }
        Shizuku.requestPermission(6720)
    }

    suspend fun testShizuku(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(Shizuku.pingBinder()) { "Start Shizuku first." }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Authorize VRec in Shizuku first."
            }
            @Suppress("DEPRECATION")
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", "id -u"), null, null)
            try {
                val output = proc.inputStream.bufferedReader().readText().trim()
                check(proc.waitFor() == 0) { "Shizuku command failed." }
                val uid = output.toInt()
                check(uid == 2000 || uid == 0) { "Expected shell/root, got UID " + uid }
                uid
            } finally {
                proc.destroy()
            }
        }
    }
}
