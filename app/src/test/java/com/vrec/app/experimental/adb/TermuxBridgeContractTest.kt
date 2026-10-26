package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Android Termux RUN_COMMAND integration must not pose as shell authorization. */
class TermuxBridgeContractTest {
    private fun read(file: String) =
        listOf(File(file), File("../$file")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $file")

    @Test fun userGrantsTermuxAndResultsArePrivate() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("com.termux.permission.RUN_COMMAND"))
        assertTrue(manifest.contains("android:name=\".experimental.adb.TermuxResultService\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        val bridge = read("app/src/main/java/com/vrec/app/experimental/adb/TermuxCommandBridge.kt")
        assertTrue(bridge.contains("com.termux.app.RunCommandService"))
        assertTrue(bridge.contains("FLAG_MUTABLE"))
        assertTrue(bridge.contains("RUN_COMMAND_PENDING_INTENT"))
        assertTrue(bridge.contains("RESULT_BUNDLE = \"result\""))
        assertTrue(bridge.contains("RUN_COMMAND_BACKGROUND"))
        assertFalse(bridge.contains("AdbPermissionBootstrap.grantEligible"))
    }

    @Test fun consoleIsExplicitAndDoesNotReplaceVerifiedAdb() {
        val ui = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val terminal = read("app/src/main/java/com/vrec/app/ui/TermuxTerminalCard.kt")
        assertTrue(ui.contains("TermuxTerminalCard()"))
        assertTrue(terminal.contains("Termux command console (optional)"))
        assertTrue(terminal.contains("allow-external-apps=true"))
        assertTrue(terminal.contains("does not emulate a second USB host"))
        assertTrue(terminal.contains("adb devices -l"))
        assertFalse(terminal.contains("pm grant"))
    }
}
