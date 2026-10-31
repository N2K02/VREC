package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensure removing the in-app Activity Log never loses Logcat or background recovery. */
class ActivityLogContractTest {
    private fun source(relative: String): String =
        listOf(File("app/src/main/java/$relative"), File("src/main/java/$relative"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing $relative")

    @Test fun inAppLogViewerAndPersistentBufferAreRetired() {
        val activity = source("com/vrec/app/MainActivity.kt")
        val settings = source("com/vrec/app/ui/SettingsScreen.kt")
        val log = source("com/vrec/app/Log.kt")
        assertFalse(activity.contains("Screen.LOG"))
        assertFalse(settings.contains("onOpenLog"))
        assertFalse(log.contains("MutableStateFlow"))
        assertFalse(log.contains("writeHandler"))
        assertFalse(log.contains("persistNow"))
    }

    @Test fun sanitizedLogcatRetainsDiagnosticMessages() {
        val log = source("com/vrec/app/Log.kt")
        assertTrue(log.contains("AndroidLog.i(TAG, redact(message)"))
        assertTrue(log.contains("AndroidLog.w(TAG, safe"))
        assertTrue(log.contains("private fun redact(message: String)"))
        assertTrue(log.contains("vrec_daily_activity"))
    }

    @Test fun loggerInitializesBeforeBackgroundRecoveryAndNotOnEveryActivityLaunch() {
        val app = source("com/vrec/app/VrecApp.kt")
        val initializer = app.indexOf("Log.initialize(this)")
        val updater = app.indexOf("AppUpdater.init(this")
        assertTrue(initializer > 0 && updater > initializer)
        assertFalse(app.contains("AdbLifecycle.startBackground(this)"))
        val activity = source("com/vrec/app/MainActivity.kt")
        assertTrue(activity.contains("AdbLifecycle.startBackground(applicationContext)"))
        assertFalse(activity.contains("Log.initialize(applicationContext)"))
    }
}
