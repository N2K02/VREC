package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent future UI changes from quietly restoring microphone fallback or self-authorization. */
class AdbOnlySafetyContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun methodIsAdbOnlyAndStandardSelectorIsGone() {
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val settings = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertFalse(controller.contains("enum class Method"))
        assertFalse(settings.contains("RecordingMethodOption("))
        assertFalse(settings.contains("Permissions & setup"))
    }

    @Test fun missingHelperCannotTriggerAutomaticStandardRecording() {
        val recorder = read("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        assertTrue(recorder.contains("Never launch a second microphone recorder or silently fall back"))
        assertFalse(recorder.contains("val standardFallback = false"))
    }

    @Test fun shellPermissionCommandsRequireVerifiedUidAndAreCheckedAfterwards() {
        val boot = read("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
        assertTrue(boot.contains("ExperimentalRecording.status.value.shellUid != 2000"))
        assertTrue(boot.contains("!AdbTransport.isConnected"))
        assertTrue(boot.contains("pm grant"))
        assertTrue(boot.contains("checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED"))
        assertTrue(boot.contains("unavailable.add("))
    }

    @Test fun usbOnlyClientNeverDiscoversNetworkAdbAndShowsBadge() {
        val client = read("app/src/main/java/com/vrec/app/experimental/adb/AdbTransport.kt")
        val card = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        assertFalse(client.contains("AdbMdns"))
        assertFalse(client.contains("discoverPort("))
        assertFalse(client.contains("suspend fun pair("))
        assertTrue(card.contains("AdbUidBadge(badge, needsUsbBootstrap)"))
        assertTrue(card.contains("Vrec call recording helper"))
    }
}
