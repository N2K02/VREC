package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-component regression coverage for independent watchdog/boot switches and Home ports. */
class AdbRecoveryPreferencesContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun togglesPersistSeparatelyAndDefaultToExistingAutomaticBehavior() {
        val prefs = source("app/src/main/java/com/vrec/app/experimental/adb/AdbUserChoices.kt")
        assertTrue(prefs.contains("getBoolean(CONNECTION_WATCHDOG, true)"))
        assertTrue(prefs.contains("getBoolean(START_ON_BOOT, true)"))
        assertFalse("Disabled Start on boot must suppress unseen-boot recovery",
            AdbUserChoices.backgroundRecoveryAllowed(false, 42, 41))
        assertTrue("Foreground-opened same-boot recovery is allowed",
            AdbUserChoices.backgroundRecoveryAllowed(false, 42, 42))
        assertTrue("Unknown boot state must not bypass a disabled boot switch",
            !AdbUserChoices.backgroundRecoveryAllowed(false, -1, -1))
        assertTrue("Enabled Start on boot works independently of foreground history",
            AdbUserChoices.backgroundRecoveryAllowed(true, 42, -1))
        assertTrue(prefs.contains("fun setConnectionWatchdog(context: Context, enabled: Boolean)"))
        assertTrue(prefs.contains("fun setStartOnBoot(context: Context, enabled: Boolean)"))
        assertTrue(prefs.contains("getBoolean(RESTORE_AFTER_RESTART, false)"))
        assertTrue(prefs.contains("getBoolean(AUTO_PAIR, true)"))
    }

    @Test fun watchdogToggleCancelsBothPeriodicSchedulersWithoutDisconnectingCapture() {
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val worker = source("app/src/main/java/com/vrec/app/experimental/adb/AdbRecoveryWorker.kt")
        val body = lifecycle.substringAfter("fun configureWatchdog(")
            .substringBefore("fun requestRecovery(")
        assertTrue(lifecycle.contains("if (AdbUserChoices.connectionWatchdog(app))"))
        assertTrue(lifecycle.contains("while (isActive && AdbUserChoices.connectionWatchdog(app))"))
        assertTrue(worker.contains("!AdbUserChoices.connectionWatchdog(app)"))
        assertTrue(worker.contains("!AdbUserChoices.backgroundRecoveryAllowed(app)"))
        assertTrue(body.contains("loop?.cancel()"))
        assertTrue(body.contains("AdbRecoveryWorker.cancel(app)"))
        assertTrue(body.contains("startBackground(app)"))
        assertFalse(body.contains("AdbTransport.close()"))
        assertFalse(body.contains("ExperimentalCaptureDaemon.quit("))
    }

    @Test fun startOnBootOffSuppressesBootOnlyWithoutBlockingNormalLaunch() {
        val receiver = source("app/src/main/java/com/vrec/app/calls/PhoneStateReceiver.kt")
        val application = source("app/src/main/java/com/vrec/app/VrecApp.kt")
        val activity = source("app/src/main/java/com/vrec/app/MainActivity.kt")
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(receiver.contains("choices.backgroundRecoveryAllowed(app)"))
        assertTrue(application.contains("Do not start recovery from Application.onCreate"))
        assertTrue(activity.contains("AdbUserChoices.markUserOpenedThisBoot(applicationContext)"))
        assertTrue(receiver.contains("choices.manuallyStopped(app)"))
        assertFalse(application.contains("AdbLifecycle.startBackground(this)"))
        assertTrue(activity.contains("AdbLifecycle.startBackground(applicationContext)"))
        assertTrue("Previously paired TLS discovery must still start from authorized entry points",
            lifecycle.contains("LocalWirelessAdb.start(app)"))
    }

    @Test fun homeShowsVerifiedLivePortAndSeparatesCachedOrListeningEndpoints() {
        val controller = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val home = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertTrue(controller.contains("val activePort: Int? = null"))
        assertTrue(controller.contains("val activeTransport: String? = null"))
        assertTrue(controller.contains("activePort = 5555, activeTransport = \"TCP\""))
        assertTrue(controller.contains("activePort = port, activeTransport = \"TLS\""))
        assertTrue(controller.contains("activePort = null, activeTransport = null"))
        assertTrue(home.contains("ADB connection ports"))
        assertTrue(home.contains("verified && adbState.activePort != null && adbState.activeTransport != null"))
        assertTrue(home.contains("TCP/5555 listening · no verified active ADB session"))
        assertTrue(home.contains("Wireless TLS discovered: "))
        assertTrue(home.contains("AdbUserChoices.lastVerifiedPort(context)"))
        assertTrue(home.indexOf("ADB connection ports") < home.indexOf("if (healthy) {"))
    }

    @Test fun switchesAreUnderTheExistingSettingsConnectionSubmenu() {
        val settings = source("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertTrue(settings.contains("if (section == \"ADB connection\")"))
        assertTrue(settings.contains("\"Connection watchdog\""))
        assertTrue(settings.contains("\"Start on boot\""))
        assertTrue(settings.contains("AdbLifecycle.configureWatchdog(context, enabled)"))
        assertTrue(settings.contains("AdbUserChoices.setStartOnBoot(context, enabled)"))
        assertTrue(settings.contains("AdbSecurityChoices()"))
        assertTrue(settings.contains("AdbPairingRecoveryChoices()"))
        assertFalse(settings.contains("AdbSetupProgressCard("))
    }
}
