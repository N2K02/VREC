package com.vrec.app.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingQuickTileSafetyContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun tileDoesNotChangeCaptureModeDuringACall() {
        val source = read("app/src/main/java/com/vrec/app/recording/RecordingQuickTile.kt")
        val guard = source.substringAfter("if (CallMonitor.hasLiveCall()")
            .substringBefore("val target = !old")
        assertTrue(guard.contains("AdbFallbackNotice.enhancedActive.value"))
        assertTrue(guard.contains("AdbFallbackNotice.enhancedPaused.value"))
        assertTrue(guard.contains("return@withLock"))
        assertTrue(source.contains("toggleMutex.withLock"))
    }

    @Test fun manualHealthTestHandlesErrorsAndCancellation() {
        val screen = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertTrue(screen.contains("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        assertTrue(screen.contains("Could not check the recorder. Retry after reconnecting ADB."))
    }
}
