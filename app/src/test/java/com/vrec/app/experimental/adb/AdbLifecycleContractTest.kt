package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quit cleanup, cancellation propagation and serialized permission bootstrap. */
class AdbLifecycleContractTest {
    private fun read(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun quitStopsRecoveryListenersDiscoveryAndSession() {\n        val main = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val lifecycle = read("app/src/main/java/com/vrec/app/experimental/adb/AdbLifecycle.kt")
        val wireless = read("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
        assertTrue(main.contains("AdbLifecycle.quit(app)"))
        assertTrue(main.contains("VrecQuitSafety.sequence("))
        assertTrue(lifecycle.contains("AdbUserChoices.setManuallyStopped(app, true)"))
        assertTrue(lifecycle.contains("loop?.cancel()"))
        assertTrue(lifecycle.contains("unregisterNetworkCallback"))
        assertTrue(lifecycle.contains("LocalWirelessAdb.shutdown(app)"))
        assertTrue(lifecycle.contains("transportMutex.withLock { AdbTransport.close() }"))
        assertTrue(lifecycle.contains("SharedAdbHostAdapter.shutdown()"))
        assertTrue(lifecycle.contains("withContext(NonCancellable + Dispatchers.IO)"))
        assertTrue(wireless.contains("observing?.cancel()"))
        assertTrue(wireless.contains("observedCore?.releaseService()"))\n    }

    @Test fun setupPropagatesCancellationInsteadOfReportingFailure() {
        val mw = read("app/src/main/java/com/vrec/app/experimental/adb/LocalAdbMiddleware.kt")
        val cancel = mw.indexOf("catch (c: kotlin.coroutines.cancellation.CancellationException)")
        assertTrue(cancel >= 0 && cancel < mw.indexOf("catch (t: Throwable)"))
        assertTrue(mw.contains("throw c"))
        assertFalse(mw.contains("runCatching"))
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        assertFalse(controller.contains("runCatching { AdbPermissionBootstrap"))
        // Permission setup moved into the helper's verified startup flow.
        val helper = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbPrivilegedApiHelper.kt")
        val flow = read("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperStartFlow.kt")
        assertTrue(helper.contains("AdbPermissionBootstrap.grantEligible(app)"))
        assertTrue(flow.indexOf("steps.verifyUid2000()") in
            0 until flow.indexOf("steps.configurePermissions()"))
    }

    @Test fun permissionBootstrapIsSerializedAndReverifiesUidLive() {
        val boot = read("app/src/main/java/com/vrec/app/experimental/adb/AdbPermissionBootstrap.kt")
        assertTrue(boot.contains("suspend fun grantEligible(context: Context): ResultSummary = mutex.withLock {"))
        assertTrue(boot.indexOf("AdbTransport.exec(\"id -u\"") in 0 until boot.indexOf("AdbTransport.exec(\"pm grant"))
        assertFalse(boot.contains("runCatching"))
        assertTrue(boot.contains("unavailable.add(\"\$short (\$instruction)\")"))
        assertTrue(boot.contains("restricted call-log access"))
    }

    @Test fun manualRetryLatchOnlyForAuthorizationFailures() {
        val controller = read("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
        val latches = Regex("awaitingManualRetry = true").findAll(controller).count()
        val guarded = Regex("if \\(AdbFailurePolicy\\.requiresManualRetry\\(kind\\)\\) awaitingManualRetry = true")
            .findAll(controller).count()
        assertTrue(latches == guarded && guarded == 2)
    }
}
