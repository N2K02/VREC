package com.vrec.app.experimental.daemon

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSourceHandshakeTest {
    @Test fun twoImmediateEofsIndicateLegacyRunningDaemon() = runBlocking {
        var reads = 0
        var waits = 0
        val response = CaptureSourceHandshake.request(
            read = { reads++; null },
            waitBetween = { waits++ },
        )
        assertEquals(CaptureSourceHandshake.UNSUPPORTED, response)
        assertEquals(2, reads)
        assertEquals(1, waits)
    }

    @Test fun transientEofThenGoodAckDoesNotTriggerUpgrade() = runBlocking {
        var reads = 0
        val response = CaptureSourceHandshake.request(
            read = { if (++reads == 1) null else "SOURCE 0\n" },
            waitBetween = {},
        )
        assertEquals("SOURCE 0", response)
        assertEquals(2, reads)
    }

    @Test fun timeoutDoesNotTriggerUpgradeOrAnotherImmediateConnection() = runBlocking {
        var reads = 0
        val response = CaptureSourceHandshake.request(
            read = { reads++; throw SocketTimeoutException("slow during active call") },
            waitBetween = { error("No automatic retry on socket timeout") },
        )
        assertNull(response)
        assertEquals(1, reads)
    }

    @Test fun diagnosticReportsSocketFailureWithoutTreatingItAsLegacy() = runBlocking {
        var failureType: String? = null
        val response = CaptureSourceHandshake.request(
            read = { throw java.net.SocketException("Connection reset") },
            waitBetween = { error("A socket reset must not spin an immediate retry") },
            onTransportError = { failureType = it.javaClass.simpleName },
        )
        assertNull(response)
        assertEquals("SocketException", failureType)
    }

    @Test fun cancellationPropagatesSoQuitCanStopSetup() = runBlocking {
        var cancelled = false
        try {
            CaptureSourceHandshake.request(
                read = { throw CancellationException("Quit") },
                waitBetween = {},
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun realInvalidAndBusyRepliesDoNotTriggerUpgrade() = runBlocking {
        for (text in listOf("SOURCE BUSY", "SOURCE INVALID", "SOURCE 2")) {
            val response = CaptureSourceHandshake.request(
                read = { text },
                waitBetween = { error("Valid responses must not cause retry") },
            )
            assertEquals(text, response)
        }
    }
}
