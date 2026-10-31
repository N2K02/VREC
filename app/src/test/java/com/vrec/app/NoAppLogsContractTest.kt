package com.vrec.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun settingsKeepADBAndUpdatesWithoutEmbeddedLogs() {
        val ui=src("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        assertFalse(ui.contains("onOpenLog"))
        assertFalse(ui.contains("headlineContent = { Text(\"Logs\") }"))
        assertTrue(ui.contains("headlineContent = { Text(\"ADB diagnostics\") }"))
        assertTrue(ui.contains("headlineContent = { Text(\"About & updates\") }"))
        assertFalse(src("app/src/main/java/com/vrec/app/MainActivity.kt").contains("Screen.LOG"))
    }
    @Test fun redactToLogcatWithoutPersistingPrivateJournals() {
        val log=src("app/src/main/java/com/vrec/app/AppLog.kt")
        assertTrue(log.contains("Log.d("))
        assertFalse(log.contains("appendText("))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}
