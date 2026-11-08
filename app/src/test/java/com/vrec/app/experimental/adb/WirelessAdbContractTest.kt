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
        val adapter = read("app/src/main/java/com/vrec/app/experimental/adb/SharedAdbHostAdapter.kt")
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(transport.contains("const val LOOPBACK = \"127.0.0.1\""))
        assertTrue(transport.contains("require(host == LOOPBACK && port == LEGACY_TCP_PORT)"))
        assertTrue(adapter.contains("LocalAdbDiscovery(app)"))
        assertTrue(transport.contains("existing().execVerified(command, timeoutMs)"))
        assertTrue(controller.contains("verifyShellUid()"))
        assertTrue(controller.contains("deferHelperUntilTcp"))
        assertFalse(transport.contains("AbsAdbConnectionManager"))
    }

    @Test fun explicitSetupStartsDiscoveryAndNotificationCodeIsSupported() {
        val home = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        val ui = read("app/src/main/java/com/vrec/app/ui/WirelessAdbSetupCard.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(home.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(wireless.contains("adb.acquireService()"))
        assertTrue(ui.contains("AdbHomeSetupPage("))
        assertFalse(ui.contains("OutlinedTextField"))
        assertFalse(wireless.contains("RemoteInput"))
        assertFalse(manifest.contains("VrecPairingReceiver"))
        assertFalse(wireless.contains("Log.i(\"pairing code"))
    }
}
