package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard the R8 release configuration against WorkManager's startup crash.
 * Room and WorkManager reflectively load generated database and merger types.
 * Keeping the class alone is insufficient in R8 full mode: their zero-argument
 * constructors must be explicitly retained.
 *
 * This source-level contract complements (but cannot replace) an instrumented
 * cold-launch test of an R8-minified APK.
 */
class WorkManagerReleaseStartupContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun releaseRetainsReflectivelyLoadedRoomAndWorkManagerConstructors() {
        val gradle = read("app/build.gradle.kts")
        val rules = read("app/proguard-rules.pro")
        assertTrue("Releases must remain optimized", gradle.contains("isMinifyEnabled = true"))
        assertTrue("Release build must load the app's keep rules",
            gradle.contains("proguardFiles(getDefaultProguardFile(") &&
                gradle.contains("\"proguard-rules.pro\")"))
        assertTrue("WorkManager dependency must be present",
            gradle.contains("implementation(\"androidx.work:work-runtime-ktx:"))
        assertTrue("Preserve Room's WorkDatabase_Impl zero-argument constructor",
            rules.contains(
                "-keep class * extends androidx.room.RoomDatabase {\n    <init>();\n}"
            ))
        assertTrue("Preserve WorkManager InputMerger zero-argument constructors",
            rules.contains(
                "-keep class * extends androidx.work.InputMerger {\n    <init>();\n}"
            ))
    }

    @Test fun backgroundWorkerRemainsScheduledByTheExistingWatchdog() {
        val worker = read("app/src/main/java/com/vrec/app/experimental/adb/AdbRecoveryWorker.kt")
        val lifecycle = read("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(worker.contains("PeriodicWorkRequestBuilder<AdbRecoveryWorker>"))
        assertTrue(lifecycle.contains("AdbRecoveryWorker.schedule(app)"))
        assertTrue(lifecycle.contains("AdbRecoveryWorker.cancel(app)"))
    }
}
