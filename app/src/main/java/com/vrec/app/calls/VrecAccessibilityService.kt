package com.vrec.app.calls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
        @Volatile private var activeService: VrecAccessibilityService? = null

        /**
         * mDNS can resolve *after* the Settings window sends its last event.
         * Re-read that window on the service main thread when a pairing port
         * appears, so automatic pairing doesn't depend on another user tap.
         */
        fun onPairingPortDiscovered() {
            val service = activeService ?: return
            service.pairingHandler.post {
                if (activeService === service) service.schedulePairingWindowRead()
            }
        }

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

    private val pairingHandler = Handler(Looper.getMainLooper())
    private var readsRemaining = 0
    // Ephemeral signature only (not the code itself), for suppressing retries
    // on unchanged Android Settings dialogs. Never persisted or logged.
    private var lastAttemptSignature: Int? = null
    private val pairingWindowRead = object : Runnable {
        override fun run() {
            if (!AdbUserChoices.autoPairCode(this@VrecAccessibilityService)) return
            if (tryPairingFromSettingsWindow()) return
            if (readsRemaining-- > 0) pairingHandler.postDelayed(this, 400L)
        }
    }

    private fun schedulePairingWindowRead() {
        if (!AdbUserChoices.autoPairCode(this)) return
        pairingHandler.removeCallbacks(pairingWindowRead)
        // Android 17 may render the code AFTER its last accessibility event.
        // Poll only briefly, only while the pairing port is present, and only
        // inspect Android Settings' actual root window.
        readsRemaining = 15
        pairingWindowRead.run()
    }


    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        activeService = this
        if (LocalWirelessAdb.status.value.pairingPort != null) schedulePairingWindowRead()
        Log.i("Accessibility recording helper connected")
    }

    /** Read only the foreground Android Settings pairing dialog (never other apps). */
    private fun tryPairingFromSettingsWindow(): Boolean {
        val state = LocalWirelessAdb.status.value
        if (state.pairing) return true
        if (state.pairingPort == null) return false
        val root = rootInActiveWindow ?: return false
        if (!PairingCodeUiPolicy.isSettingsPackage(root.packageName?.toString())) return false
        val nodes: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        val labels = mutableListOf<String>()
        nodes.add(root)
        var examined = 0
        while (nodes.isNotEmpty() && examined++ < 100) {
            val node = nodes.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            for (index in 0 until node.childCount.coerceAtMost(20)) {
                node.getChild(index)?.let(nodes::add)
            }
        }
        val code = PairingCodeUiPolicy.extractCode(labels) ?: return false
        val signature = (state.pairingPort.toString() + ":" + root.windowId + ":" + code).hashCode()
        if (signature == lastAttemptSignature) return true
        lastAttemptSignature = signature
        LocalWirelessAdb.pair(applicationContext, code)
        // No code is written to preferences, diagnostics, or activity logs.
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        if (PairingCodeUiPolicy.isSettingsPackage(packageName)) {
            // The Settings dialog may render the code asynchronously.
            schedulePairingWindowRead()
            return
        }
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
        pairingHandler.removeCallbacks(pairingWindowRead)
        if (activeService === this) activeService = null
        running = false
        super.onDestroy()
    }
}
