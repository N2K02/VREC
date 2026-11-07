package com.vrec.app.experimental.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun libraryVersionAndSecurityAdapterArePinned() {
        val root = listOf(File("app"), File("../app")).first { it.isDirectory }
        val settings = root.resolve("../settings.gradle.kts").readText()
        val build = root.resolve("build.gradle.kts").readText()
        val src = root.resolve("src/main/java/com/vrec/app/experimental/adb/SharedAdbHostAdapter.kt").readText()
        assertTrue(settings.contains("n2k02AdbCoreLibrary"))
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.3"))
        assertTrue(src.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(src.contains("AdbUserChoices.manuallyStopped(app)"))
        assertTrue(src.contains("AdbUserChoices.lastVerifiedPort(app)"))
        assertTrue(src.contains("LibAdbWire"))
    }
}
