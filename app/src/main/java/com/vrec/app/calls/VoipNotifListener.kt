package com.vrec.app.calls

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.vrec.app.Log

/** Caller metadata enrichment for Telecom calls; standalone VoIP detection where Telecom is silent. */
class VoipNotifListener : NotificationListenerService() {
    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.split(':').any {
                it.equals(ComponentName(context, VoipNotifListener::class.java).flattenToString(), true)
            }
        }

        private val VOIP_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.viber.voip",
            "com.skype.raider", "com.google.android.apps.tachyon",
        )
        private const val EXTRA_CALL_TYPE = "android.callType"
    }

    private data class Signal(val status: String, val metadata: CallerMetadata?)
    private val signals = mutableMapOf<String, MutableMap<String, Signal>>()
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = mutableMapOf<String, Runnable>()

    private fun dialer(): String? =
        runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    override fun onListenerConnected() {
        Log.i("Call notification metadata listener connected")
        signals.clear()
        runCatching { activeNotifications.orEmpty().forEach(::onNotificationPosted) }
    }

    override fun onListenerDisconnected() {
        Log.w("Call notification metadata listener disconnected")
        // Listener reconnection should not end a real call or discard its last caller.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val phone = pkg == dialer()
        if (!phone && pkg !in VOIP_PACKAGES) return
        val n = sbn.notification ?: return
        val state = stateOf(n) ?: return
        expiry.remove(pkg)?.let(handler::removeCallbacks)
        val map = signals.getOrPut(pkg) { mutableMapOf() }
        map[sbn.key] = Signal(state, CallMetadataResolver.fromNotification(this, n))
        publish(pkg, phone)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val remaining = signals[pkg] ?: return
        remaining.remove(sbn.key)
        if (remaining.isNotEmpty()) {
            publish(pkg, pkg == dialer())
        } else {
            // WhatsApp can replace a ringing notification with another key when answered.
            // A short grace window prevents spurious stop/start sequences.
            expiry.remove(pkg)?.let(handler::removeCallbacks)
            val task = Runnable {
                if (signals[pkg].isNullOrEmpty()) {
                    signals.remove(pkg)
                    CallMonitor.onNotification(this, pkg, pkg == dialer(), "IDLE", null)
                }
            }
            expiry[pkg] = task
            handler.postDelayed(task, 2_500L)
        }
    }

    private fun publish(pkg: String, phone: Boolean) {
        val values = signals[pkg]?.values.orEmpty()
        val status = when {
            values.any { it.status == "ACTIVE" } -> "ACTIVE"
            values.any { it.status == "RINGING" } -> "RINGING"
            else -> "UNKNOWN"
        }
        val metadata = values.firstNotNullOfOrNull { it.metadata?.takeIf { m ->
            m.number != null || m.name != null
        } }
        CallMonitor.onNotification(this, pkg, phone, status, metadata)
    }

    private fun stateOf(n: Notification): String? {
        val type = n.extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0
        if (type == 1 || type == 3) return "RINGING"
        if (type == 2) return "ACTIVE"
        val callStyle = n.extras?.getString(Notification.EXTRA_TEMPLATE)
            ?.endsWith("\$CallStyle") == true
        if (n.category != Notification.CATEGORY_CALL && !callStyle) return null
        val labels = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }
        if (labels.any { it.contains("end call") || it.contains("hang up") || it.contains("disconnect") })
            return "ACTIVE"
        if (labels.any { it.contains("answer") || it.contains("accept") } &&
            labels.any { it.contains("decline") || it.contains("reject") }) return "RINGING"
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return "ACTIVE"
        return "UNKNOWN"
    }

    override fun onDestroy() {
        expiry.values.forEach(handler::removeCallbacks)
        expiry.clear()
        super.onDestroy()
    }
}
