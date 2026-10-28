package com.vrec.app.experimental.daemon

import kotlinx.coroutines.CancellationException

/**
 * Differentiates an older daemon's unknown-command EOF from a temporary
 * socket timeout. Legacy daemons answer E but close the socket on J, while
 * an interrupted network/recording session must never authorize replacement.
 */
internal object CaptureSourceHandshake {
    const val UNSUPPORTED = "SOURCE UNSUPPORTED"

    suspend fun request(
        read: suspend () -> String?,
        waitBetween: suspend () -> Unit,
        onTransportError: (Exception) -> Unit = {},
    ): String? {
        repeat(2) { attempt ->
            val reply = try {
                read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A timeout/reset is not evidence of an unsupported command.
                // Surface only the exception category, never the pairing secret.
                onTransportError(failure)
                return null
            }
            if (reply != null) return reply.trim()
            if (attempt == 0) waitBetween()
        }
        // Two separate authenticated sessions ended without any response.
        // The caller may use the guarded Q/upgrade path (Q refuses active calls).
        return UNSUPPORTED
    }
}
