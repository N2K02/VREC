package com.vrec.app.experimental.daemon

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ShellHelperBuildPolicyTest {
    private val expected = "9f86d081" // First eight SHA-256 hex characters of "test".

    @Test fun bundledJarFingerprintMatchesDaemonEncoding() {
        assertEquals(expected, ShellHelperBuildPolicy.fingerprint(
            ByteArrayInputStream("test".toByteArray(Charsets.UTF_8))))
    }

    @Test fun currentHelperCanReuseItsAuthenticatedSession() {
        assertEquals(ShellHelperBuildPolicy.Decision.CURRENT,
            ShellHelperBuildPolicy.decide("PONG 2 $expected", expected))
    }

    @Test fun olderHelperWithSameProtocolIsReplacedSafely() {
        assertEquals(ShellHelperBuildPolicy.Decision.STALE,
            ShellHelperBuildPolicy.decide("PONG 2 abcdef12", expected))
        assertEquals(ShellHelperBuildPolicy.Decision.STALE,
            ShellHelperBuildPolicy.decide("PONG 2", expected))
    }

    @Test fun unknownFingerprintMustNotForceRestart() {
        assertEquals(ShellHelperBuildPolicy.Decision.UNKNOWN,
            ShellHelperBuildPolicy.decide("PONG 2 unknown", expected))
        assertEquals(ShellHelperBuildPolicy.Decision.UNKNOWN,
            ShellHelperBuildPolicy.decide("PONG 2 garbled", expected))
    }

    @Test fun badHandshakeCannotAuthorizeShellOperations() {
        for (banner in listOf<String?>(null, "", "PONG 1 $expected", "ERROR", "PONG 20 $expected")) {
            assertEquals(ShellHelperBuildPolicy.Decision.INCOMPATIBLE_PROTOCOL,
                ShellHelperBuildPolicy.decide(banner, expected))
        }
    }
}
