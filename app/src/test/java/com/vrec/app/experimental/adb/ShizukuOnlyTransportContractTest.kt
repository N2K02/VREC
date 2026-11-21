package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforce Shizuku-only privilege transport. The ADB label in Settings is
 * intentional; legacy adb-core code and dependency must never return.
 */
class ShizukuOnlyTransportContractTest {
    private fun root(): File {
        var current = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(current, "settings.gradle.kts").exists()) return current
            current = current.parentFile ?: return@repeat
        }
        error("Could not locate Android repository root")
    }

    @Test fun externalAdbCoreLibraryIsAbsent() {
        val root = root()
        root.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name == "gradle.properties") }
            .forEach { file ->
                val contents = file.readText()
                assertFalse("${file.path} still references adb-core library",
                    contents.contains("dev.n2k02:adb-core"))
            }
    }

    @Test fun legacyCoreSourceIsAbsent() {
        val root = root()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" &&
                it.path.replace('\\', '/').contains("/src/main/") }
            .forEach { file ->
                val contents = file.readText()
                assertFalse("${file.path} imports the removed shared ADB core",
                    contents.contains("dev.n2k02.adb.core"))
            }
    }
    @Test fun userServiceProvidesRequiredProcessNameBeforeBinding() {
        val bridge = File(root(), "app/src/main/java/com/vrec/app/shizuku/ShizukuSession.kt").readText()
        val args = bridge.substringAfter("private val args = Shizuku.UserServiceArgs(")
            .substringBefore("private val lock")
        assertTrue(args.contains(".processNameSuffix(\"recorder_shell\")"))
        assertTrue(args.contains(".daemon(false)"))
        assertTrue(bridge.contains("Shizuku.bindUserService(args, connection)"))
    }
}
