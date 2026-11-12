package com.vrec.app.experimental.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The shared library, not VREC, owns native streams and deadline teardown. */
class AdbBrokenStreamRecoveryTest {
    private fun transportSource(): String = listOf(
        File("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt"),
        File("../app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt"),
    ).first { it.isFile }.readText()

    @Test fun commandsAreAlwaysExecutedByVerifiedCore() {
        val src = transportSource()
        assertTrue(src.contains("existing().execVerified(command, timeoutMs)"))
        assertFalse(src.contains("openStream("))
        assertFalse(src.contains("readLine()"))
    }

    @Test fun failuresPreserveCancellationAndRetireOnlySharedSession() {
        val src = transportSource()
        assertTrue(src.contains("catch (cancelled: CancellationException)"))
        assertTrue(src.contains("throw cancelled"))
        assertTrue(src.contains("SharedAdbHostAdapter.current()?.disconnectIdle()"))
        assertFalse(src.contains("AbsAdbConnectionManager"))
    }

    @Test fun noIndependentWatchdogCanCloseNewerCoreConnection() {
        val src = transportSource()
        assertFalse(src.contains("val watchdog ="))
        assertFalse(src.contains("manager = null"))
        assertFalse(src.contains("mutex.withLock"))
        assertTrue(src.contains("val isConnected: Boolean"))
    }

    @Test fun verifiedSharedLibraryUpgradeKeepsAdbRecoveryBuildable() {
        val build = listOf(File("app/build.gradle.kts"), File("../app/build.gradle.kts"))
            .first { it.isFile }.readText()
        assertTrue(build.contains("dev.n2k02:theme:0.1.14"))
        assertTrue(build.contains("dev.n2k02:updater:0.1.32"))
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.7"))
    }
}
