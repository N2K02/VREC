package com.vrec.app.experimental.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stream EOF must not leave an apparently healthy UID-2000 ADB manager. */
class AdbBrokenStreamRecoveryTest {
    private fun transportSource(): String =
        listOf(File("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt"), File("../app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt"))
            .first { it.isFile }.readText()

    @Test fun streamFailureRetiresWholeManagerBeforeNextCommand() {
        val src = transportSource()
        assertTrue(src.contains("synchronized(this@AdbTransport)"))
        assertTrue(src.contains("if (manager === mgr)"))
        assertTrue(src.contains("manager = null"))
        assertTrue(src.contains("lastKnownConnected = false"))
        assertTrue(src.contains("runCatching { mgr.disconnect() }"))
        assertTrue(src.contains("throw t") || src.contains("throw failure"))
        assertTrue(src.contains("private const val SHELL_SERVICE = \"exec:sh\""))
        assertTrue(src.contains("mutex.withLock"))
    }

    @Test fun watchdogNeverClosesReaderWhileReadLineIsBlocked() {
        val src = transportSource()
        assertTrue(src.contains("@Volatile private var running = false"))
        assertTrue(src.contains("@Volatile private var closing = false"))
        assertTrue(src.contains("check(!closing)"))
        assertTrue(src.contains("if (closing) runCatching { reader.close() }"))
        assertTrue(src.contains("if (!running) runCatching { reader.close() }"))
        assertTrue(src.contains("runCatching { if (!stream.isClosed) stream.close() }"))
        assertTrue(src.contains("runCatching { out.close() }"))
    }

    @Test fun expiredWatchdogCannotClearNewVerifiedAdbSession() {
        val source = transportSource()
        val watchdog = source.substringAfter(
            "val watchdog = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {"
        ).substringBefore("session = session ?:")
        assertTrue(watchdog.contains("synchronized(this@AdbTransport)"))
        val guarded = watchdog.substringAfter("if (manager === mgr) {")
            .substringBefore("} else if (shell === session)")
        assertTrue(guarded.contains("manager = null"))
        assertTrue(guarded.contains("lastKnownConnected = false"))
        assertTrue(guarded.contains("shell = null"))
        assertTrue(watchdog.contains("else if (shell === session) shell = null"))
        assertTrue(!watchdog.contains("if (manager === mgr) manager = null"))
    }


    @Test fun verifiedSharedLibraryUpgradeKeepsAdbRecoveryBuildable() {
        val build = listOf(File("app/build.gradle.kts"), File("../app/build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue("Expected approved shared theme", build.contains("dev.n2k02:theme:0.1.5"))
        assertTrue("Expected approved shared updater", build.contains("dev.n2k02:updater:0.1.20"))
    }
}
