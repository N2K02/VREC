package com.vrec.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the bottom Quit menu and its app-owned shutdown behavior. */
class QuitNavigationContractTest {
    @Test fun quitStaysBesideSettingsAndOldControlsDoNotReturn() {
        val relative = "app/src/main/java/com/vrec/app/MainActivity.kt"
        val src = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        assertTrue("Missing label = { Text(Settings) }", src.contains("Triple(Screen.SETTINGS, \"Settings\", Icons.Rounded.Settings)"))
        assertTrue("Missing label = { Text(Quit) }", src.contains("label = { Text(\"Quit\") }"))
        assertTrue("Missing CallMonitor.setRecordingEnabled(quitContext, false)", src.contains("CallMonitor.setRecordingEnabled(quitContext, false)"))
        assertTrue("Missing finishAndRemoveTask()", src.contains("finishAndRemoveTask()"))

        assertTrue(
            "Quit must follow Settings in the bottom menu",
            src.indexOf("label = { Text(\"Quit\") }") > src.indexOf("Triple(Screen.SETTINGS, \"Settings\", Icons.Rounded.Settings)"),
        )
    }
}
