package com.vrec.app.experimental.adb

import com.vrec.app.ui.PairingStatusText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing expiry and USB-debugging watch, adapted from JemRec. */
class JemRecResilienceTest {
    private fun source(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun parsesEveryPairingOutcome() {
        assertEquals(PairingTimeout.Outcome.KEPT, PairingTimeout.parse("x\nVREC_PAIRING_KEPT\n"))
        assertEquals(PairingTimeout.Outcome.ALREADY_OFF, PairingTimeout.parse("VREC_PAIRING_ALREADY_OFF"))
        assertEquals(PairingTimeout.Outcome.GIVEN_BACK, PairingTimeout.parse(" VREC_PAIRING_GIVEN_BACK "))
        assertEquals(PairingTimeout.Outcome.NOTHING_TO_GIVE_BACK, PairingTimeout.parse("VREC_PAIRING_NOTHING"))
        assertEquals(PairingTimeout.Outcome.FAILED, PairingTimeout.parse(null))
        assertEquals(PairingTimeout.Outcome.FAILED, PairingTimeout.parse("Permission denial"))
        // A marker inside other text is not a success.
        assertEquals(PairingTimeout.Outcome.FAILED, PairingTimeout.parse("echo VREC_PAIRING_KEPT later"))
    }

    @Test fun keepRecordsBeforeWritingAndVerifiesReadBack() {
        val keep = PairingTimeout.keepCommand()
        assertTrue(keep.indexOf("> ${PairingTimeout.RECORD}") < keep.indexOf("settings put global adb_allowed_connection_time 0"))
        assertTrue(keep.contains("[ -e ${PairingTimeout.RECORD} ] ||"))
        assertTrue(keep.contains("= 0 ] && echo VREC_PAIRING_KEPT"))
        assertFalse(keep.contains("\n"))
    }

    @Test fun giveBackOnlyWhileStillOffAndNeverExecutesRecordedText() {
        val back = PairingTimeout.giveBackCommand()
        assertTrue(back.contains("''|*[!0-9]*) v=null"))
        assertTrue(back.contains("if [ \"\$(settings get global adb_allowed_connection_time)\" = 0 ]"))
        assertTrue(back.contains("settings delete global adb_allowed_connection_time"))
        assertFalse(back.contains("eval"))
        assertFalse(back.contains("\n"))
    }

    @Test fun custodianPutsTheTimeoutBackOnUninstall() {
        val main = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(main.contains("\"/data/local/tmp/vrec-pairing-timeout\""))
        assertTrue(main.contains("recorded.matches(\"[0-9]+\")"))
        val cleanup = main.substringAfter("private static void cleanUpAfterApp()")
            .substringBefore("private static void givePairingTimeoutBack()")
        assertTrue(cleanup.indexOf("givePairingTimeoutBack();") <
            cleanup.indexOf("\"adb_wifi_enabled\", \"adb_enabled\""))
    }

    @Test fun pairingAppliedAfterEveryVerifiedHelperStart() {
        val helper = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val ready = helper.substringAfter("is AdbHelperStartFlow.Outcome.Ready ->")
            .substringBefore("is AdbHelperStartFlow.Outcome.Failed ->")
        assertTrue(ready.contains("PairingTimeout.apply(app)"))
    }

    @Test fun pairingStatusText() {
        assertEquals("Pairing will not expire.", PairingStatusText.of(true, PairingTimeout.Outcome.KEPT))
        assertNull(PairingStatusText.of(false, PairingTimeout.Outcome.KEPT))
        assertNull(PairingStatusText.of(true, null))
        assertTrue(PairingStatusText.of(true, PairingTimeout.Outcome.FAILED)!!.contains("next recorder start"))
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

    @Test fun wifiLossShieldSwitchReachesTheHelper() {
        assertEquals("rm -f /data/local/tmp/vrec-shield-off && settings put global adb_enabled 1 && echo VREC_SHIELD_ON",
            WifiLossShield.onCommand())
        assertEquals("touch /data/local/tmp/vrec-shield-off && echo VREC_SHIELD_OFF", WifiLossShield.offCommand())
        // Off never turns USB debugging off; it only stops forcing it on.
        assertFalse(WifiLossShield.offCommand().contains("adb_enabled"))
        assertTrue(WifiLossShield.applied(true, "x\nVREC_SHIELD_ON"))
        assertFalse(WifiLossShield.applied(true, "VREC_SHIELD_OFF"))
        assertTrue(WifiLossShield.applied(false, "VREC_SHIELD_OFF"))
        assertFalse(WifiLossShield.applied(false, null))
        val main = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(main.contains("new File(\"/data/local/tmp/vrec-shield-off\")"))
        assertTrue(main.contains("if (!off && usb != null && !\"1\".equals(usb))"))
        assertTrue(main.contains("leftovers.add(SHIELD_OFF);"))
        val helper = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        assertTrue(helper.contains("WifiLossShield.apply(app)"))
    }

    @Test fun usbWatchDoesNotRestoreShieldWhenSwitchedOff() {
        assertFalse(UsbDebuggingWatch.shouldRestoreShield(
            UsbDebuggingWatch.Signals(false, false, true, true, shieldOn = false)))
        assertTrue(UsbDebuggingWatch.shouldRestoreShield(
            UsbDebuggingWatch.Signals(false, false, true, true, shieldOn = true)))
    }

    @Test fun settingsShowBothResilienceSwitches() {
        val card = source("app/src/main/java/com/vrec/app/ui/AdbResilienceCard.kt")
        assertTrue(card.contains("Toggle(\"Keep pairing past 7 days\""))
        assertTrue(card.contains("Toggle(\"Survive Wi-Fi loss\""))
        assertTrue(source("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt").contains("AdbResilienceCard()"))
    }

    @Test fun helperFilesNoLongerCollideWithJemRec() {
        val greeting = source("shellserver/src/com/vrec/app/shell/Greeting.java")
        assertTrue(greeting.contains("/data/local/tmp/vrec-greeting.pcm"))
        assertFalse(greeting.contains("jemrec-greeting"))
        assertFalse(source("shellserver/src/com/vrec/app/shell/Main.java").contains("JemRec is no longer installed"))
    }
}
