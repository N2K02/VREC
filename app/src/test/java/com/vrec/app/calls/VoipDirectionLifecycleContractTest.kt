package com.vrec.app.calls

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direction metadata must not be inferred from a generic ongoing notification or lost while recording. */
class VoipDirectionLifecycleContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun callScreenDirectionRequiresVerifiedCallControls() {
        val screen = source("app/src/main/java/com/vrec/app/calls/VrecAccessibilityService.kt")
        val guard = screen.indexOf("if (!hasEndCallControl) return")
        val publish = screen.indexOf("VoipAccessibilityDirection.fromCallLabels(labels)")
        assertTrue("Only actual call screens may publish a direction", guard >= 0 && publish > guard)
        assertTrue(screen.contains("CallMonitor.onAccessibilityCallDirection(this, pkg, flow)"))
        assertTrue(screen.contains("if (!needsMetadata) return"))
        assertFalse(screen.contains("CallMonitor.onNotification(this, pkg"))
    }

    @Test fun earlyOutgoingHintIsShortLivedAndDoesNotOverrideTelecom() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(monitor.contains("VOIP_DIRECTION_WINDOW_MS = 45_000L"))
        assertTrue(monitor.contains("age in 0..VOIP_DIRECTION_WINDOW_MS"))
        assertTrue(monitor.contains("session.flowDirection != CallDirection.UNKNOWN"))
        assertTrue(monitor.contains("target.flowDirection != CallDirection.UNKNOWN"))
        assertTrue(monitor.contains("pendingVoipDirections.remove(packageName)"))
        assertTrue(monitor.contains("pendingVoipDirections.clear()"))
    }

    @Test fun activeNotificationsPersistEvenWhenShellOwnsRecording() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val notify = monitor.substringAfter("fun onNotification(").substringBefore("private fun applyPendingVoipDirection(")
        val active = notify.substringAfter("if (status == \"ACTIVE\") {")
        assertTrue(active.contains("rememberShellIdentity(context, session)"))
        assertTrue(active.indexOf("rememberShellIdentity(context, session)") < active.indexOf("reconcile(context)"))
        assertTrue(monitor.contains("if (AdbFallbackNotice.enhancedActive.value) return"))
    }
}
