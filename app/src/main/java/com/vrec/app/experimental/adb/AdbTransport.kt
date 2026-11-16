package com.vrec.app.experimental.adb

import android.content.Context
import com.vrec.app.shizuku.ShizukuSession
import kotlinx.coroutines.CancellationException

/** Legacy call-site signatures routed exclusively through the app-authorized Shizuku service.
 * No libadb, wireless pairing, TLS socket, TCP listener or fallback transport is created.
 */
object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555

    val isConnected: Boolean get() = ShizukuHostAdapter.current()?.state?.value?.connected == true
    val lastKnownConnected: Boolean get() = isConnected

    private fun core(context: Context): ShizukuSession = ShizukuHostAdapter.core(context.applicationContext)
    private fun existing(): ShizukuSession = checkNotNull(ShizukuHostAdapter.current()) {
        "Shizuku session is not initialized"
    }

    private suspend inline fun <T> checked(crossinline operation: suspend () -> T): Result<T> =
        try { Result.success(operation()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }

    /** Former localhost entry point; now checks only the Shizuku Binder and UID 2000. */
    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> = checked {
        require(host == LOOPBACK && port == LEGACY_TCP_PORT) { "Invalid legacy connection request" }
        check(core(context).retry(auto = true)) { "Authorize VRec in the LocalADB Shizuku server" }
    }

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku uses app permission; pairing is not supported"))

    /** Compatibility entry point: the port is ignored, no TLS socket is ever opened. */
    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> = checked {
        check(core(context).retry(auto = true)) { "Shizuku permission required" }
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000L): Result<String> =
        checked { existing().execVerified(command, timeoutMs) }

    suspend fun switchToTcpip(port: Int = LEGACY_TCP_PORT): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku does not use TCP/5555"))

    suspend fun switchToUsb(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku does not control USB debugging"))

    suspend fun disconnect() = close()
    suspend fun close() { ShizukuHostAdapter.current()?.disconnectIdle() }
}
