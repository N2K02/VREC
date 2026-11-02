package com.vrec.app.experimental.adb

import java.io.File
import java.security.KeyPairGenerator
import org.junit.Assert.*
import org.junit.Test

class AdbSessionKeyLifecycleTest {
    private fun code(): String {
        val path = "app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt"
        return listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    }

    @Test fun eachSessionOwnsAnIndependentRsaKey() {
        val saved = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private
        val first = AdbSessionKey.copyOf(saved)
        val second = AdbSessionKey.copyOf(saved)
        assertNotSame(saved, first)
        assertNotSame(saved, second)
        assertNotSame(first, second)
        assertArrayEquals(saved.encoded, first.encoded)
        assertArrayEquals(saved.encoded, second.encoded)
    }

    @Test fun verifiedTcpAndTlsSessionsAreReusedWithoutSkippingUidCheck() {
        val path = "app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt"
        val src = listOf(File(path), File("../" + path)).first { it.isFile }.readText()
        assertTrue(src.contains("val canReuseTcp"))
        assertTrue(src.contains("val canReuseTls"))
        assertTrue(src.contains("verifyShellUid() == 2000"))
        assertTrue(src.contains("if (!healthy) {"))
        assertTrue(src.contains("AdbTransport.close()"))
        assertTrue(src.contains("previous.activePort == port"))
    }

    @Test fun timeoutAndShutdownRetireManagersWithoutDestroyingCachedKey() {
        val src = code()
        for (expected in listOf(
            "AdbSessionKey.copyOf(identity.privateKey)",
            "getPrivateKey(): PrivateKey = sessionKey",
            "oldManager?.disconnect()", "if (manager === mgr) manager = null",
            "lastKnownConnected = false", "runCatching { reader.close() }",
            "suspend fun disconnect() = close()"))
            assertTrue(expected, src.contains(expected))
        assertFalse(src.contains("mgr.close()"))
        assertFalse(src.contains("manager?.close()"))
        assertFalse(src.contains("getPrivateKey(): PrivateKey = identity.privateKey"))
        val watchdog = src.substringAfter("val watchdog = launch {")
            .substringBefore("session = session ?:")
        assertTrue(watchdog.contains("mgr.disconnect()"))
        assertTrue(watchdog.contains("if (manager === mgr) manager = null"))
    }
}
