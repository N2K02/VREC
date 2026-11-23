package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** USB-debugging watch, adapted from JemRec. Pairing expiry is LocalAdb's on this track. */
class JemRecResilienceTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun custodianPutsTheTimeoutBackOnUninstall() {
        val main = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(main.contains("\"/data/local/tmp/vrec-pairing-timeout\""))
        assertTrue(main.contains("recorded.matches(\"[0-9]+\")"))
        val cleanup = main.substringAfter("private static void cleanUpAfterApp()")
            .substringBefore("private static void givePairingTimeoutBack()")
        assertTrue(cleanup.indexOf("givePairingTimeoutBack();") <
            cleanup.indexOf("\"adb_wifi_enabled\", \"adb_enabled\""))
    }

    private fun signals(stopped: Boolean = false, quitting: Boolean = false,
                        recording: Boolean = true, secure: Boolean = true) =
        UsbDebuggingWatch.Signals(stopped, quitting, recording, secure)

    @Test fun usbWatchRespectsQuitStopAndRecordingOff() {
        assertTrue(UsbDebuggingWatch.shouldRevive(signals()))
        assertFalse(UsbDebuggingWatch.shouldRevive(signals(stopped = true)))
        assertFalse(UsbDebuggingWatch.shouldRevive(signals(quitting = true)))
        assertFalse(UsbDebuggingWatch.shouldRevive(signals(recording = false)))
    }

    @Test fun shieldRestoredOnlyWithSecureSettingsAndWhenRevivingAtAll() {
        assertTrue(UsbDebuggingWatch.shouldRestoreShield(signals()))
        assertFalse(UsbDebuggingWatch.shouldRestoreShield(signals(secure = false)))
        assertFalse(UsbDebuggingWatch.shouldRestoreShield(signals(recording = false)))
        assertFalse(UsbDebuggingWatch.shouldRestoreShield(signals(stopped = true)))
    }

    @Test fun usbWatchIsArmedReArmedAndCancelledOnQuit() {
        val watch = source("app/src/main/java/com/vrec/app/experimental/adb/UsbDebuggingWatch.kt")
        val work = watch.substringAfter("override suspend fun doWork()").substringBefore("data class Signals")
        assertTrue(work.trimStart().contains("arm(app, ExistingWorkPolicy.APPEND_OR_REPLACE)"))
        assertTrue(work.indexOf("arm(app,") < work.indexOf("shouldRevive("))
        assertTrue(watch.contains("addContentUriTrigger(Settings.Global.getUriFor(ADB_ENABLED), false)"))
        assertEquals(500L, UsbDebuggingWatch.UPDATE_DELAY.toMillis())
        assertEquals(1_000L, UsbDebuggingWatch.MAX_DELAY.toMillis())
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("UsbDebuggingWatch.arm(app)"))
        val quit = lifecycle.substringAfter("suspend fun quit(context: Context)")
        assertTrue(quit.contains("UsbDebuggingWatch.cancel(app)"))
    }

    @Test fun shizukuTrackLeavesPairingToLocalAdb() {
        assertFalse(File("app/src/main/java/com/vrec/app/experimental/adb/PairingTimeout.kt").isFile ||
            File("../app/src/main/java/com/vrec/app/experimental/adb/PairingTimeout.kt").isFile)
        assertFalse(source("app/src/main/java/com/vrec/app/ui/ShizukuSettingsPage.kt").contains("KeepPairingCard"))
        assertFalse(source("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt").contains("PairingTimeout"))
    }

    @Test fun helperFilesNoLongerCollideWithJemRec() {
        val greeting = source("shellserver/src/com/vrec/app/shell/Greeting.java")
        assertTrue(greeting.contains("/data/local/tmp/vrec-greeting.pcm"))
        assertFalse(greeting.contains("jemrec-greeting"))
        assertFalse(source("shellserver/src/com/vrec/app/shell/Main.java").contains("JemRec is no longer installed"))
    }
}
