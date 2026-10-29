package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protect the Activity Log UI and early background diagnostics across UI refactors. */
class ActivityLogContractTest {
    private fun source(relative: String): String =
        listOf(File("app/src/main/java/$relative"), File("src/main/java/$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing $relative")

    @Test fun activityPageOffersAAYouTubeStyleControls() {
        val screen = source("com/vrec/app/ui/LogScreen.kt")
        assertTrue(screen.contains("Text(\"Enable logging\""))
        assertTrue(screen.contains("Switch(checked = enabled, onCheckedChange = { Log.setEnabled(it) })"))
        assertTrue(screen.contains("LogPagePolicy.selectedPage("))
        assertTrue(screen.contains("followNewest = false"))
        assertTrue(screen.contains("followNewest = page == pages - 1"))
        assertTrue(screen.contains("Text(\"Copy\""))
        assertTrue(screen.contains("Text(\"Clear all\""))
        assertTrue(screen.contains("Log.snapshot()"))
        assertTrue(screen.contains("Log.clear()"))
    }

    @Test fun optedOutLogsStopPersistingButLogcatStaysAvailable() {
        val log = source("com/vrec/app/Log.kt")
        assertTrue(log.contains("val enabled: StateFlow<Boolean> = _enabled"))
        assertTrue(log.contains("fun setEnabled(on: Boolean)"))
        assertTrue(log.contains("if (!_enabled.value) return"))
        assertTrue(log.contains("AndroidLog.i(TAG, safe)"))
        assertTrue(log.contains("AndroidLog.w(TAG, safe)"))
        assertTrue(log.contains("private fun redact(message: String)"))
        assertTrue(log.contains("private const val MAX = 400"))
    }

    @Test fun loggerInitializesBeforeBackgroundRecoveryAndNotOnEveryActivityLaunch() {
        val app = source("com/vrec/app/VrecApp.kt")
        val initializer = app.indexOf("Log.initialize(this)")
        val recovery = app.indexOf("AdbLifecycle.startBackground(this)")
        assertTrue(initializer > 0 && recovery > initializer)
        val main = source("com/vrec/app/MainActivity.kt")
        assertFalse(main.contains("Log.initialize(applicationContext)"))
        val log = source("com/vrec/app/Log.kt")
        assertTrue(log.contains("if (context != null) {"))
    }
}
