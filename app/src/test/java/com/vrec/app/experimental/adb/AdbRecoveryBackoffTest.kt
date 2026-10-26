package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRecoveryBackoffTest {
    @Test fun doublesFromOneMinuteAndCapsAtThirty() {
        val b = AdbRecoveryBackoff()
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_800_000L, 1_800_000L),
            (1..7).map { b.delayFor(it) })
    }

    @Test fun deadlineDoesNotWrapNearMaximumTimestamp() {
        val b = AdbRecoveryBackoff()
        b.onFailure(Long.MAX_VALUE - 1)
        assertFalse(b.allowed(0))
        assertFalse(b.allowed(Long.MAX_VALUE - 1))
        assertTrue(b.allowed(Long.MAX_VALUE))
        b.onSuccess()
        assertTrue(b.allowed(0))
    }

    @Test fun blocksUntilDelayPassesAndResetsOnSuccess() {
        val b = AdbRecoveryBackoff()
        assertTrue(b.allowed(0))
        b.onFailure(1_000)
        assertFalse(b.allowed(60_999))
        assertTrue(b.allowed(61_000))
        b.onFailure(61_000)
        assertFalse(b.allowed(180_999))
        b.onSuccess()
        assertTrue(b.allowed(0))
        assertEquals(0, b.failures)
    }
}

class BackgroundRecoveryContractTest {
    private fun read(path: String) = listOf(java.io.File(path), java.io.File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun recoveryRunsFromTheAppProcessNotOnlyHome() {
        val lifecycle = read("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("recover(app, \"background check\")"))
        assertTrue(lifecycle.contains("registerNetworkCallback"))
        assertTrue(read("app/src/main/java/com/vrec/app/VrecApp.kt").contains("AdbLifecycle.startBackground(this)"))
    }

    @Test fun automaticDiscoveryIsBoundedAndPausesAfterRepeatedEmptySearches() {
        val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(wireless.contains("emptySearches >= MAX_EMPTY_SEARCHES || now < nextAutoSearchAt"))
        assertTrue(wireless.contains("private const val AUTO_SEARCH_MS = 60_000L"))
        assertTrue(wireless.contains("repeat(20) { if (!listening)"))
        assertTrue(wireless.contains(".reconnectTcp(context, automatic = false) == null"))
    }
}
