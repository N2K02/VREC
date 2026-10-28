package com.vrec.app.experimental.adb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-component contract: never confuse a verified old TLS UID with live TCP. */
class AdbHelperTcpRecoveryContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun wirelessTcpPromotesTransportBeforeAnyHelperStartup() {
        val wireless = source("app/src/main/java/com/vrec/app/experimental/adb/ExperimentalRecording.kt")
            .substringAfter("suspend fun connectWireless(")
            .substringBefore("/** Runs a connection attempt")
        val defer = wireless.indexOf("if (deferHelperUntilTcp)")
        val bootstrap = wireless.indexOf("val helper = startHelperTyped(context)")
        assertTrue(defer >= 0 && bootstrap > defer)
        assertTrue(wireless.substring(defer, bootstrap).contains("return attempt"))
        val coordinator = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
            .substringAfter("private suspend fun connectAndSwitch(")
            .substringBefore("fun shutdown(")
        assertTrue(coordinator.contains("deferHelperUntilTcp = switchToTcp"))
        assertTrue(coordinator.contains("if (switchToTcp) return switchToLocalTcp(app)"))
        val tcp = source("app/src/main/java/com/vrec/app/experimental/adb/LocalWirelessAdb.kt")
            .substringAfter("private suspend fun switchToLocalTcp(")
        assertTrue(tcp.contains("reconnectTcp(context, automatic = false)"))
    }

    @Test fun startButtonDoesNotTrustAnOldUidBadge() {
        val dashboard = source("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        assertTrue(dashboard.contains("onStart()"))
        assertTrue(dashboard.contains("never bypass it using a cached UI badge"))
        val middleware = source("app/src/main/java/com/vrec/app/experimental/adb/LocalAdbMiddleware.kt")
        assertTrue(middleware.contains("AdbTransport.isConnected"))
        assertTrue(middleware.contains("LocalAdbSetupPolicy.Next.CONNECT_TCP"))
    }

    @Test fun optionalTelecomDenialCannotBlockHelperLaunch() {
        val helper = source("app/src/main/java/com/vrec/app/experimental/daemon/AdbHelperStartFlow.kt")
        assertTrue(helper.indexOf("steps.configurePermissions()") <
            helper.indexOf("steps.ensureHelperStarted()"))
        assertTrue(helper.contains("Permission setup incomplete"))
        val access = source("app/src/main/java/com/vrec/app/calls/CallDetectionAccess.kt")
        assertTrue(access.contains("cmd appops set --uid"))
        assertTrue(access.contains("MANAGE_ONGOING_CALLS allow"))
    }
}
