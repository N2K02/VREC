package com.vrec.app.experimental.daemon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable simulations of the actual production startup transaction.
 * These fail when future refactors reorder shell verification, permission
 * setup, daemon launch or authenticated health verification.
 */
class AdbHelperStartFlowTest {
    private class Device : AdbHelperStartFlow.Steps {
        var authorized = false
        var deniedPermissions = emptyList<String>()
        var launchOk = true
        var helperAnswers = true
        var permissionException: Exception? = null
        val calls = mutableListOf<String>()

        override suspend fun verifyUid2000(): Boolean {
            calls += "verify UID 2000"
            return authorized
        }
        override suspend fun configurePermissions(): List<String> {
            calls += "configure permissions"
            permissionException?.let { throw it }
            return deniedPermissions
        }
        override suspend fun ensureHelperStarted(): Result<Unit> {
            calls += "launch helper"
            return if (launchOk) Result.success(Unit)
                else Result.failure(IllegalStateException("shell helper unavailable"))
        }
        override suspend fun probeHelper(): Boolean {
            calls += "probe authenticated helper"
            return helperAnswers
        }
    }

    @Test fun firstTimeWithoutAuthorizationCannotLaunchOrGrant() = runBlocking {
        val device = Device()
        val result = AdbHelperStartFlow.run(device)
        assertTrue(result is AdbHelperStartFlow.Outcome.Failed)
        assertEquals(AdbHelperStartFlow.Stage.VERIFY_SHELL,
            (result as AdbHelperStartFlow.Outcome.Failed).stage)
        assertEquals(listOf("verify UID 2000"), device.calls)
    }

    @Test fun firstTimeAfterPairingGrantsThenLaunchesThenProbes() = runBlocking {
        val device = Device()
        // Android code pairing and connecting moves the session from unknown to UID 2000.
        AdbHelperStartFlow.run(device)
        device.calls.clear()
        device.authorized = true
        val result = AdbHelperStartFlow.run(device)
        assertTrue(result is AdbHelperStartFlow.Outcome.Ready)
        assertEquals(listOf("verify UID 2000", "configure permissions",
            "launch helper", "probe authenticated helper"), device.calls)
    }

    @Test fun deniedOptionalPermissionsDoNotPreventWorkingRecordingHelper() = runBlocking {
        val device = Device().apply {
            authorized = true
            deniedPermissions = listOf("READ_CALL_LOG restricted by Android", "Advanced Telecom access unavailable")
        }
        val result = AdbHelperStartFlow.run(device)
        assertTrue(result is AdbHelperStartFlow.Outcome.Ready)
        assertEquals(device.deniedPermissions,
            (result as AdbHelperStartFlow.Outcome.Ready).unavailablePermissions)
        assertTrue(device.calls.contains("probe authenticated helper"))
    }

    @Test fun unexpectedPermissionFailureStillAttemptsHelperAndReportsIssue() = runBlocking {
        val device = Device().apply {
            authorized = true
            permissionException = IllegalStateException("permission manager unavailable")
        }
        val result = AdbHelperStartFlow.run(device)
        assertTrue(result is AdbHelperStartFlow.Outcome.Ready)
        assertTrue((result as AdbHelperStartFlow.Outcome.Ready)
            .unavailablePermissions.any { it.contains("permission manager unavailable") })
        assertEquals("probe authenticated helper", device.calls.last())
    }

    @Test fun failedLaunchDoesNotClaimHelperIsReady() = runBlocking {
        val device = Device().apply { authorized = true; launchOk = false }
        val result = AdbHelperStartFlow.run(device)
        assertEquals(AdbHelperStartFlow.Stage.LAUNCH,
            (result as AdbHelperStartFlow.Outcome.Failed).stage)
        assertFalse(device.calls.contains("probe authenticated helper"))
    }

    @Test fun disconnectedHelperAfterTcpRepairRequiresHealthyProbe() = runBlocking {
        val device = Device().apply { authorized = true; helperAnswers = false }
        val failed = AdbHelperStartFlow.run(device)
        assertEquals(AdbHelperStartFlow.Stage.PROBE,
            (failed as AdbHelperStartFlow.Outcome.Failed).stage)
        // Recovery verifies the new transport again; no success from a port alone.
        device.calls.clear()
        device.helperAnswers = true
        assertTrue(AdbHelperStartFlow.run(device) is AdbHelperStartFlow.Outcome.Ready)
        assertEquals("verify UID 2000", device.calls.first())
        assertEquals("probe authenticated helper", device.calls.last())
    }

    @Test fun cancellationNeverTurnsIntoASpuriousStartupFailure() = runBlocking {
        val device = Device().apply {
            authorized = true
            permissionException = CancellationException("Quit")
        }
        var cancelled = false
        try {
            AdbHelperStartFlow.run(device)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertFalse(device.calls.contains("launch helper"))
    }
}
