package com.vrec.app.calls

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.vrec.app.Log

/**
 * VREC's recording privilege holder. It does not read the screen or tap anything; it exists only
 * so that, while it is enabled, Android treats VREC's microphone capture as a privileged
 * (accessibility) client and keeps delivering audio during a call instead of silence.
 *
 * This is the same mechanism Cube ACR and ACR Phone rely on, and the reason they ship a helper
 * app. It needs no root, Shizuku or debugging.
 */
class VrecAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var running = false
            private set

        /** Whether the user has switched the service on in Android's Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = ComponentName(context, VrecAccessibilityService::class.java).flattenToString()
            return flat.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Log.i("Recording privilege service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally does nothing: VREC only needs this service to be enabled.
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
