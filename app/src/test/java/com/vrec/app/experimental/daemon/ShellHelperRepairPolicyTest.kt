package com.vrec.app.experimental.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellHelperRepairPolicyTest {
    private val current = ShellHelperBuildPolicy.Decision.CURRENT
    private val stale = ShellHelperBuildPolicy.Decision.STALE

    @Test fun backgroundCannotRestartCurrentDaemonAfterTemporarySourceTimeout() {
        assertFalse(ShellHelperRepairPolicy.shouldRequestReplacement(
            build = current, incompatibleReply = false, manualRepair = false))
    }

    @Test fun tappingRepairMayRequestOneGuardedReplacement() {
        assertTrue(ShellHelperRepairPolicy.shouldRequestReplacement(
            build = current, incompatibleReply = false, manualRepair = true))
    }

    @Test fun obsoleteOrExplicitlyIncompatibleDaemonCanBeUpgraded() {
        assertTrue(ShellHelperRepairPolicy.shouldRequestReplacement(
            build = stale, incompatibleReply = false, manualRepair = false))
        assertTrue(ShellHelperRepairPolicy.shouldRequestReplacement(
            build = current, incompatibleReply = true, manualRepair = false))
    }

    @Test fun unknownBuildDoesNotCauseAutomaticRestart() {
        assertFalse(ShellHelperRepairPolicy.shouldRequestReplacement(
            build = ShellHelperBuildPolicy.Decision.UNKNOWN,
            incompatibleReply = false, manualRepair = false))
    }
}
