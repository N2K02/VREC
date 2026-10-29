package com.vrec.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class UnifiedLogsContractTest {
    private fun read(p: String) = listOf(File(p), File("../$p")).firstOrNull { it.isFile }
        ?.readText() ?: error(p)
    @Test fun onlyOneLogsRowAndBothStoresAreInUnifiedViewer() {
        val settings = read("app/src/main/java/com/vrec/app/ui/SettingsScreen.kt")
        val main = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val logs = read("app/src/main/java/com/vrec/app/LogsActivity.kt")
        val viewer = read("app/src/main/java/com/vrec/app/UnifiedLogsPage.kt")
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(settings.contains("headlineContent = { Text(\"Logs\") }"))
        assertFalse(settings.contains("headlineContent = { Text(\"Recording diagnostics\") }"))
        assertFalse(settings.contains("LogsActivity::class.java"))
        assertTrue(main.contains("VRECLogsPage(onBack ="))
        assertTrue(main.contains("AppLog.init(applicationContext)"))
        assertTrue(logs.contains("Log.lines.collectAsState()"))
        assertTrue(logs.contains("clearExtra = Log::clear"))
        assertTrue(viewer.contains("Text(\"Prev\")"))
        assertTrue(viewer.contains("Text(\"Next\")"))
        assertTrue(viewer.contains("Text(\"Copy\")"))
        assertTrue(viewer.contains("Text(\"Clear all\")"))
        assertTrue(viewer.contains("Switch(checked = enabled"))
        assertFalse(manifest.contains("LogsActivity"))
    }
}
