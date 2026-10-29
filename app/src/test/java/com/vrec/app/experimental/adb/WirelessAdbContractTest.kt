package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pairing must be same-device, user-approved and guard privileged grants. */
class WirelessAdbContractTest {
    private fun read(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun wirelessHostIsLoopbackOnlyAndShellUidIsVerified() {
        val transport = read("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt")
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(transport.contains("pair(LOOPBACK, port, code)"))
        assertTrue(transport.contains("connect(LOOPBACK, port)"))
        assertTrue(wireless.contains("belongsToThisPhone(host)"))
        assertTrue(controller.contains("suspend fun connectWireless("))
        assertTrue(controller.contains("context: Context, port: Int, automatic: Boolean = false"))
        assertTrue(controller.contains("if (uid != 2000) throw AdbConnectionException(AdbFailure.PERMISSION_DENIED"))
        assertTrue(controller.substringAfter("suspend fun connectWireless(").contains("verifyShellUid()"))
        assertTrue(controller.contains("deferHelperUntilTcp"))
        assertTrue(controller.contains("startHelperTyped(context, manualRepair = !automatic)"))
        val helper = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val flow = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperStartFlow.kt")
        assertTrue(helper.contains("AdbPermissionBootstrap.grantEligible(app)"))
        assertTrue(flow.indexOf("steps.verifyUid2000()") in
            0 until flow.indexOf("steps.configurePermissions()"))
    }

    @Test fun explicitSetupStartsDiscoveryAndNotificationCodeIsSupported() {
        val home = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val ui = read("app/src/main/java/com/vrec/app/ui/WirelessAdbSetupCard.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        val foreground = read("app/src/main/java/com/vrec/app/experimental/adb/AdbForegroundSetup.kt")
        assertTrue(home.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(foreground.contains("LocalWirelessAdb.start(app, userInitiated = true)"))
        assertFalse(home.contains("LocalAdbMiddleware.setup(context, userInitiated = false)"))
        assertTrue(ui.contains("LocalWirelessAdb.openSettings(context)"))
        assertTrue(ui.contains("Pair and configure ADB"))
        assertTrue(wireless.contains("_adb-tls-pairing._tcp"))
        assertTrue(wireless.contains("_adb-tls-connect._tcp"))
        assertTrue(wireless.contains("RemoteInput.Builder"))
        assertTrue(manifest.contains("VrecPairingReceiver"))
        assertFalse(wireless.contains("Log.i(\"pairing code"))
    }
}
