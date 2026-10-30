package com.vrec.app.calls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.AdbUserChoices
import com.vrec.app.experimental.adb.LocalWirelessAdb
import java.util.ArrayDeque

/**
 * Keeps the existing optional recording helper, and on experimental mode reads only the
 * foreground call app when an active call lacks an identity. Never performs UI actions.
 */
class VrecAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var running = false
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val component = ComponentName(context, VrecAccessibilityService::class.java).flattenToString()
            return flat.split(':').any { it.equals(component, ignoreCase = true) }
        }

        private val packages = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.viber.voip",
            "com.skype.raider", "com.google.android.apps.tachyon",
        )
    }

    private val lastScan = mutableMapOf<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i("Accessibility recording helper connected")
    }

    /**
     * Explicit user opt-in only. Read the short-lived pairing code from Android
     * Settings, never arbitrary application windows. This is not a way to enable
     * Wireless debugging or authorize ADB without Android's pairing dialog.
     */
    private fun tryPairingFromSettings(event: AccessibilityEvent?): Boolean {
        if (!AdbUserChoices.autoPairCode(this)) return false
        val pkg = event?.packageName?.toString() ?: return false
        if (pkg != "com.android.settings" && pkg != "com.google.android.settings") return false
        val state = LocalWirelessAdb.status.value
        if (state.pairingPort == null || state.pairing) return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != pkg) return false
        val items: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        val labels = mutableListOf<String>()
        items.add(root)
        var seen = 0
        while (items.isNotEmpty() && seen++ < 100) {
            val node = items.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (i in 0 until node.childCount.coerceAtMost(20)) {
                node.getChild(i)?.let(items::add)
            }
        }
        val dialogVisible = labels.any {
            it.contains("pair device with pairing code", ignoreCase = true) ||
                it.contains("pair with device", ignoreCase = true) ||
                it.contains("wi-fi pairing code", ignoreCase = true) ||
                it.contains("pairing code", ignoreCase = true)
        }
        if (!dialogVisible) return false
        val code = labels.asSequence().map { it.trim() }
            .firstOrNull { it.matches(Regex("[0-9]{6}")) } ?: return false
        LocalWirelessAdb.pair(applicationContext, code)
        // Pairing code is intentionally not persisted or logged.
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (tryPairingFromSettings(event)) return
        if (!Prefs.experimentalCallDetection(this)) return
        val pkg = event?.packageName?.toString() ?: return
        val dialer = runCatching {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        val isDialer = pkg == dialer
        if (pkg !in packages && !isDialer) return
        if (!CallMonitor.needsScreenMetadata(pkg, isDialer)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemClock.elapsedRealtime()
        if (now - (lastScan[pkg] ?: 0L) < 1500L) return
        lastScan[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val nodes: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        val labels = linkedSetOf<String>()
        var hasEndCallControl = false
        nodes.add(root)
        var examined = 0
        while (nodes.isNotEmpty() && examined++ < 90) {
            val node = nodes.removeFirst()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.length in 1..120) labels.add(text)
            val controls = (description + " " + text).lowercase()
            if (controls.contains("end call") || controls.contains("hang up") ||
                controls.contains("disconnect call") || controls.contains("end voice call")) {
                hasEndCallControl = true
            }
            // Only use visible labels on a recognizable call screen. Other chat text
            // is never a trustworthy caller identity even while an audio call is active.
            if (node.childCount > 0) {
                for (i in 0 until node.childCount.coerceAtMost(20)) {
                    node.getChild(i)?.let(nodes::add)
                }
            }
        }
        if (!hasEndCallControl) return
        val metadata = CallMetadataResolver.fromVisibleLabels(this, labels) ?: return
        CallMonitor.onAccessibilityMetadata(this, pkg, metadata, isDialer)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
