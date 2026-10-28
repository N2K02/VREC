package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-layer failure contracts for device-only Start/Retry bugs.
 * The operation itself runs in an application scope so Compose navigation
 * cannot cancel it. These source guards complement the executable policy tests.
 */
class AdbStartupCancellationRegressionTest {
    private fun source(path: String) =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing source: $path")

    @Test fun homeAndPermissionCardShareAppOwnedStart() {
        val home = source("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val permissions = source("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        assertTrue(home.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(permissions.contains("AdbForegroundSetup.begin(context)"))
        assertFalse(home.contains("scope.launch {"))
        assertTrue(home.contains("AdbForegroundSetup.busy.collectAsState()"))
    }

    @Test fun userNavigationDoesNotCancelExplicitSetup() {
        val coordinator = source("app/src/main/java/com/vrec/app/experimental/adb/AdbForegroundSetup.kt")
        assertTrue(coordinator.contains("CoroutineScope(SupervisorJob() + Dispatchers.IO)"))
        assertTrue(coordinator.contains("starting.compareAndSet(false, true)"))
        assertTrue(coordinator.contains("generation.get() == requestGeneration"))
        assertTrue(coordinator.contains("AdbLifecycle.coordinateSetup {"))
        val lifecycle = source("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        assertTrue(lifecycle.contains("AdbForegroundSetup.shutdown()"))
        assertTrue(lifecycle.contains("LocalWirelessAdb.shutdown(app)"))
    }

    @Test fun helperStartReprobesCacheAndCanPromoteExistingTls() {
        val middleware = source("app/src/main/java/com/vrec/app/experimental/adb/LocalAdbMiddleware.kt")
        assertTrue(middleware.contains("AdbPrivilegedApiHelper.reconnect(app))"))
        assertTrue(middleware.contains("LocalWirelessAdb.promoteVerifiedShellToTcp(app)"))
        assertTrue(middleware.contains("AdbPrivilegedApiHelper.startWithAdb(app)"))
        val restart = source("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(restart.contains("AdbTransport.exec(\"id -u\", timeoutMs = 3_000)"))
        assertTrue(restart.contains("if (!authorized)"))
    }

    @Test fun cancelledHandshakeAndHelperLaunchResetBusy() {
        val connection = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertTrue(connection.contains("withContext(kotlinx.coroutines.NonCancellable) { AdbTransport.close() }"))
        assertTrue(connection.contains("status.value = status.value.copy(busy = false, connected = false, shellUid = null)"))
        val helper = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        assertTrue(helper.contains("catch (cancelled: CancellationException)"))
        assertTrue(helper.contains("state.value = State.STOPPED"))
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("if (it is CancellationException) throw it"))
    }

    @Test fun deniedRuntimePermissionUsesAndroidWithoutShellAuthorization() {
        val card = source("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        assertTrue(card.contains("val result = if (authorized)"))
        assertTrue(card.contains("launcher.launch(runtime!!)"))
        assertTrue(card.contains("finally {\n                    working = false"))
        val grants = source("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
        assertTrue(grants.contains("restricted call-log access"))
        assertTrue(grants.contains("Android App info > Permissions"))
    }
}
