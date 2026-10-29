package com.vrec.app.experimental.adb

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.vrec.app.experimental.adb.AdbFailurePolicy.Stage

/** Authorization success/rejection/timeout and retry rules, decided by type, not text. */
class AdbFailurePolicyTest {
    @Test fun socketErrorsDuringHandshakeAreLostConnections() {
        assertEquals(AdbFailure.CONNECTION_LOST, AdbFailurePolicy.classify(Stage.HANDSHAKE, ConnectException("refused")))
        assertEquals(AdbFailure.CONNECTION_LOST,
            AdbFailurePolicy.classify(Stage.HANDSHAKE, IllegalStateException("x", SocketTimeoutException())))
    }

    @Test fun otherHandshakeFailuresAreRejectedKeys() {
        // e.g. libadb returning false / adbd closing the stream after "Deny" or a revoked key.
        assertEquals(AdbFailure.AUTH_REJECTED, AdbFailurePolicy.classify(Stage.HANDSHAKE, IllegalStateException("closed")))
        assertEquals(AdbFailure.AUTH_REJECTED, AdbFailurePolicy.classify(Stage.HANDSHAKE, IOException("stream closed")))
    }

    @Test fun endpointUidAndHelperStages() {
        assertEquals(AdbFailure.NETWORK_UNAVAILABLE, AdbFailurePolicy.classify(Stage.ENDPOINT, IllegalStateException()))
        assertEquals(AdbFailure.CONNECTION_LOST, AdbFailurePolicy.classify(Stage.VERIFY_UID, IOException("eof")))
        assertEquals(AdbFailure.PERMISSION_DENIED, AdbFailurePolicy.classify(Stage.VERIFY_UID, IllegalStateException()))
        assertEquals(AdbFailure.HELPER_UNAVAILABLE, AdbFailurePolicy.classify(Stage.HELPER, RuntimeException()))
    }

    @Test fun typedExceptionKeepsItsKindRegardlessOfMessage() {
        val e = AdbConnectionException(AdbFailure.AUTH_TIMEOUT, "connection refused, timed out, failed")
        assertEquals(AdbFailure.AUTH_TIMEOUT, AdbFailurePolicy.classify(Stage.HANDSHAKE, e))
        assertEquals(AdbFailure.AUTH_TIMEOUT, (e as Throwable).adbFailure)
        assertEquals(null, (RuntimeException("AUTH_TIMEOUT") as Throwable).adbFailure)
    }

    @Test fun onlyLostConnectionsRetryAndAuthFailuresNeedManualRetry() {
        AdbFailure.entries.forEach { kind ->
            assertEquals(kind == AdbFailure.CONNECTION_LOST, AdbFailurePolicy.retryable(kind))
        }
        assertFalse(AdbFailurePolicy.retryable(null))
        val manual = setOf(AdbFailure.AUTH_TIMEOUT, AdbFailure.AUTH_REJECTED, AdbFailure.PERMISSION_DENIED)
        AdbFailure.entries.forEach { assertEquals(it in manual, AdbFailurePolicy.requiresManualRetry(it)) }
        // A retryable failure must never also latch manual retry, and vice versa.
        AdbFailure.entries.forEach {
            assertFalse(AdbFailurePolicy.retryable(it) && AdbFailurePolicy.requiresManualRetry(it))
        }
    }

    @Test fun cancellationIsNeverTurnedIntoAFailure() {
        try {
            AdbFailurePolicy.staged(Stage.HANDSHAKE) { throw CancellationException("quit") }
            fail("cancellation swallowed")
        } catch (e: CancellationException) { assertEquals("quit", e.message) }
        try {
            catchingNonCancellation { throw CancellationException("quit") }
            fail("cancellation swallowed")
        } catch (e: CancellationException) { assertEquals("quit", e.message) }
        assertTrue(catchingNonCancellation { error("boom") }.isFailure)
        val staged = runCatching { AdbFailurePolicy.staged(Stage.ENDPOINT) { error("no listener") } }
        assertEquals(AdbFailure.NETWORK_UNAVAILABLE, staged.exceptionOrNull().adbFailure)
    }
}
