package com.vrec.app.ui

import com.vrec.app.experimental.adb.ExperimentalRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDashboardPolicyTest {
    @Test fun microphoneGrantOnlyShownForStandard() {
        assertTrue(PermissionDashboardPolicy.visibleFor(ExperimentalRecording.Method.STANDARD, true))
        assertFalse(PermissionDashboardPolicy.visibleFor(ExperimentalRecording.Method.BUILT_IN_ADB, true))
    }

    @Test fun sharedPermissionsAppearInBothModes() {
        assertTrue(PermissionDashboardPolicy.visibleFor(ExperimentalRecording.Method.STANDARD, false))
        assertTrue(PermissionDashboardPolicy.visibleFor(ExperimentalRecording.Method.BUILT_IN_ADB, false))
    }

    @Test fun permissionBadgeUsesExplicitGrantAndDenialLabels() {
        assertEquals("Granted", PermissionDashboardPolicy.permissionBadge(true))
        assertEquals("Not granted", PermissionDashboardPolicy.permissionBadge(false))
    }

    @Test fun badgeColorsDistinguishGrantedAndDenied() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF2E7D32), PermissionDashboardPolicy.badgeColor(true))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFC62828), PermissionDashboardPolicy.badgeColor(false))
    }

    @Test fun grantedCountDoesNotTreatMissingAsGranted() {
        assertEquals(2, PermissionDashboardPolicy.grantedCount(listOf(true, false, true, false)))
        assertEquals(0, PermissionDashboardPolicy.grantedCount(listOf(false, false)))
    }
}
