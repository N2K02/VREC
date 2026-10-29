package com.vrec.app.experimental.adb

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/** Why an ADB connection or helper start failed. Decisions use this, never message text. */
enum class AdbFailure {
    /** Android showed (or would show) an authorization prompt and nobody answered in time. */
    AUTH_TIMEOUT,
    /** adbd turned VRec's key away during the handshake (denied or revoked). */
    AUTH_REJECTED,
    /** The socket couldn't be reached or dropped mid-way; adbd restarting looks like this. */
    CONNECTION_LOST,
    /** No ADB endpoint to talk to (no TCP/5555 listener, no Wi-Fi, no Wireless debugging service). */
    NETWORK_UNAVAILABLE,
    /** ADB connected, but the recording helper didn't start or answer. */
    HELPER_UNAVAILABLE,
    /** Connected, but not as Android shell UID 2000, or Android refused a permission/setting. */
    PERMISSION_DENIED,
}

/** Carries the typed reason through Result.failure. */
class AdbConnectionException(val kind: AdbFailure, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

val Throwable?.adbFailure: AdbFailure?
    get() = (this as? AdbConnectionException)?.kind

object AdbFailurePolicy {
    /** Where in the connection sequence a throwable happened. */
    enum class Stage { ENDPOINT, HANDSHAKE, VERIFY_UID, HELPER }

    /**
     * Classify by stage and exception type. A connect-level socket error is a
     * lost connection; anything else during the handshake, after the socket
     * opened, is adbd refusing the key.
     */
    fun classify(stage: Stage, error: Throwable): AdbFailure {
        if (error is AdbConnectionException) return error.kind
        val root = generateSequence(error) { it.cause }.toList()
        val socketLevel = root.any {
            it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException
        }
        return when (stage) {
            Stage.ENDPOINT -> AdbFailure.NETWORK_UNAVAILABLE
            Stage.HANDSHAKE -> if (socketLevel) AdbFailure.CONNECTION_LOST else AdbFailure.AUTH_REJECTED
            Stage.VERIFY_UID -> if (root.any { it is IOException }) AdbFailure.CONNECTION_LOST
                else AdbFailure.PERMISSION_DENIED
            Stage.HELPER -> AdbFailure.HELPER_UNAVAILABLE
        }
    }

    /** Only a genuinely transient failure is retried automatically. */
    fun retryable(kind: AdbFailure?): Boolean = kind == AdbFailure.CONNECTION_LOST

    /**
     * After these, automatic attempts stop until the user presses Retry, so
     * Android's authorization prompt is never re-requested in the background.
     */
    fun requiresManualRetry(kind: AdbFailure?): Boolean =
        kind == AdbFailure.AUTH_TIMEOUT || kind == AdbFailure.AUTH_REJECTED ||
            kind == AdbFailure.PERMISSION_DENIED

    /** Runs [block], turning any non-cancellation throwable into a typed failure. */
    inline fun <T> staged(stage: Stage, block: () -> T): T = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        throw AdbConnectionException(classify(stage, t), t.message ?: t.javaClass.simpleName, t)
    }
}

/**
 * Like runCatching, but never swallows coroutine cancellation: Quit, timeouts
 * and scope cancellation must stop the work instead of being reported as an
 * ordinary failure.
 */
inline fun <T> catchingNonCancellation(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    Result.failure(t)
}
