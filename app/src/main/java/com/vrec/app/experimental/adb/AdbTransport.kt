package com.vrec.app.experimental.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbTransportMode
import kotlinx.coroutines.CancellationException

/**
 * Compatibility API for VRec's capture helper. All ADB sockets, pairing,
 * shell streams, UID verification, deadlines and TCP transitions are owned by
 * n2k02AdbCoreLibrary. Never create a second AbsAdbConnectionManager here.
 */
object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555

    val isConnected: Boolean get() = SharedAdbHostAdapter.current()?.state?.value?.connected == true
    val lastKnownConnected: Boolean get() = isConnected

    private fun core(context: Context) = SharedAdbHostAdapter.core(context.applicationContext)
    private fun existing(): AdbCore = checkNotNull(SharedAdbHostAdapter.current()) {
        "ADB has not been initialized; independent transport fallback is forbidden"
    }

    private suspend inline fun <T> result(crossinline block: suspend () -> T): Result<T> =
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }

    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> = result {
        require(host == LOOPBACK && port == LEGACY_TCP_PORT) { "Only localhost TCP/5555 allowed" }
        val adb = core(context)
        check(adb.retry(auto = true) &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) {
            "No authorized TCP/5555 shell UID 2000"
        }
    }

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> = result {
        check(core(context).pair(port, code)) { "Android rejected automatic pairing" }
    }

    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> = result {
        val adb = core(context)
        // Do not tear down an already verified TCP connection just to revisit a TLS port.
        if (adb.state.value.connected &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) return@result
        check(adb.connectTls(port)) { "ADB TLS authorization failed" }
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000L): Result<String> =
        result { existing().execVerified(command, timeoutMs) }

    suspend fun switchToTcpip(port: Int = LEGACY_TCP_PORT): Result<Unit> = result {
        require(port == LEGACY_TCP_PORT)
        val adb = existing()
        if (adb.state.value.connected &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) return@result
        adb.setOption(AdbOption.TCP_5555, true)
        check(adb.retry(auto = true) &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555) {
            "TCP/5555 not verified after switching"
        }
    }

    suspend fun switchToUsb(): Result<Unit> = result {
        check(existing().disableTcp()) { "Could not confirm TCP/5555 shutdown" }
    }

    /** Ordinary cleanup retains authorization; only Quit persists manual Stop. */
    suspend fun disconnect() = close()
    suspend fun close() { SharedAdbHostAdapter.current()?.disconnectIdle() }
}
