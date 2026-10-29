package com.vrec.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts protect the existing Home actions during presentation-only
 * redesigns. Actual contrast/layout still require device screenshot review.
 */
class HomeDashboardVisualContractTest {
    private fun read(path: String): String = listOf(
        File(path), File("../$path"),
    ).firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun recordingSwitchStillUsesTheOriginalRecordingCoordinator() {
        val main = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(main.contains("Switch("))
        assertTrue(main.contains("checked = enabled,"))
        assertTrue(main.contains("onCheckedChange = onEnabledChange,"))
        assertTrue(main.contains("enabled = RecordingTransportPresentation.canChangeMasterToggle(state, shellActive)"))
        assertTrue(main.contains("CallMonitor.setRecordingEnabled(context, enabled)"))
        assertTrue(main.contains("recordingEnabled = enabled"))
        assertFalse(main.contains("RadioButton(selected = enabled"))
        assertTrue(main.contains("RecordingMasterCard("))
        assertTrue(main.contains("enabled = recordingEnabled,"))
        assertTrue(main.contains("shellActive = enhancedActive,"))
        assertTrue(main.contains("Screen.HOME -> HomeScreen(onOpenRecordings = { navigateTo(Screen.RECORDINGS) })"))
    }

    @Test fun homeCardsShareDistinctSurfacesAndReadableMetricColumns() {
        val main = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val adb = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        val access = read("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        val setup = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val palette = read("app/src/main/java/com/vrec/app/ui/HomePanelStyle.kt")
        assertTrue(palette.contains("Color(0xFF272C34)"))
        assertTrue(palette.contains("BorderStroke(1.dp, stroke)"))
        assertTrue(main.contains("HomePanelStyle.border(highlight = enabled)") ||
            main.contains("HomePanelStyle.border(highlight = ready && enabled)"))
        assertTrue(adb.contains("HomePanelStyle.border(highlight = allReady)"))
        assertTrue(access.contains("HomePanelStyle.border()"))
        assertTrue(setup.contains("HomePanelStyle.border()"))
        assertTrue(access.contains("permissions.chunked(2)"))
        assertTrue(access.contains("LinearProgressIndicator("))
        assertTrue(adb.contains("Stat(\"Connection\""))
        assertTrue(adb.contains("Stat(\"Shell UID\""))
    }

    @Test fun manualCaptureAndADBOnlyLiveTransportRemainVisible() {
        val main = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        val transport = read("app/src/main/java/com/vrec/app/LiveRecordingTransport.kt")
        val coordinator = read("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val helper = read("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(main.contains("ManualRecordingCard("))
        assertTrue(main.contains("Text(\"Record manually\""))
        assertTrue(main.contains("val manualCallAvailable by CallMonitor.manualCallAvailable.collectAsState()"))
        assertTrue(main.contains("if (enhancedActive) {"))
        assertTrue(main.contains("recordingState !is RecordingService.State.Idle || shellActive"))
        assertTrue(transport.contains("val shellPaused by AdbFallbackNotice.enhancedPaused.collectAsState()"))
        assertTrue(transport.contains("if (paused) 'R' else 'P'"))
        assertTrue(coordinator.contains("(!it.isVoip || Prefs.recordVoip(app))"))
        assertTrue(coordinator.contains("ExperimentalCaptureDaemon.startCurrentCall(app)"))
        assertTrue(helper.contains("suspend fun startCurrentCall("))
        assertTrue(coordinator.contains("verifyVoipShellCapture("))
        assertFalse(main.contains("ManualRecordButton("))
    }

    @Test fun helperActionsAndPermissionsRemainGuarded() {
        val adb = read("app/src/main/java/com/vrec/app/ui/AdbDashboard.kt")
        val setup = read("app/src/main/java/com/vrec/app/ui/LocalAdbHomeCard.kt")
        val access = read("app/src/main/java/com/vrec/app/ui/PermissionDashboardCard.kt")
        assertTrue(adb.contains("enabled = !busy && !recordingActive && !healthy"))
        assertTrue(adb.contains("enabled = !busy && !recordingActive && healthy"))
        assertTrue(adb.contains("AdbPrivilegedApiHelper.stop(context)"))
        assertTrue(adb.contains("AdbLifecycle.coordinateSetup"))
        assertTrue(setup.contains("AdbForegroundSetup.begin(context)"))
        assertTrue(access.contains("tryGrant(label)"))
        assertTrue(access.contains("PermissionDashboardPolicy.grantedCount"))
    }
}
