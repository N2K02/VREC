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
import com.vrec.app.recording.CallDirection

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

    private data class Signal(
        val status: String,
        val metadata: CallerMetadata?,
        val direction: CallDirection,
    )
    private val signals = mutableMapOf<String, MutableMap<String, Signal>>()
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = mutableMapOf<String, Runnable>()
    private val reconcileIntervalMs = 3_000L
    // Some VoIP apps update/remove call notifications without sending a
    // symmetrical callback; compare the listener cache with Android's actual
    // active notifications while VRec has a notification-based VoIP call.
    private val watchdog = object : Runnable {
        override fun run() {
            reconcileActiveNotifications()
            if (signals.isNotEmpty() || expiry.isNotEmpty() ||
                CallMonitor.activeVoipPackages().isNotEmpty()) {
                handler.postDelayed(this, reconcileIntervalMs)
            }
        }
    }

    private fun scheduleWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, reconcileIntervalMs)
    }

    private fun reconcileActiveNotifications() {
        val live = runCatching { activeNotifications.orEmpty().toList() }.getOrNull() ?: return
        val watched = (signals.keys + CallMonitor.activeVoipPackages()).toSet()
        for (pkg in watched) {
            if (pkg !in VOIP_PACKAGES) continue
            val latest = live.asSequence()
                .filter { it.packageName == pkg }
                .mapNotNull { sbn ->
                    val n = sbn.notification ?: return@mapNotNull null
                    val state = stateOf(n) ?: return@mapNotNull null
                    sbn.key to Signal(state, CallMetadataResolver.fromNotification(this, n),
                        directionOf(n, state))
                }
                .toMap()
            if (latest.isEmpty()) {
                signals.remove(pkg)
                scheduleCallEnd(pkg)
            } else {
                expiry.remove(pkg)?.let(handler::removeCallbacks)
                if (signals[pkg] != latest) {
                    signals[pkg] = latest.toMutableMap()
                    publish(pkg, false)
                }
            }
        }
    }

    private fun scheduleCallEnd(pkg: String) {
        if (expiry.containsKey(pkg)) return
        val task = Runnable {
            expiry.remove(pkg)
            val remaining = runCatching {
                activeNotifications.orEmpty().any { it.packageName == pkg &&
                    it.notification?.let(::stateOf) != null }
            }.getOrNull()
            if (remaining != false) {
                if (remaining == true) reconcileActiveNotifications()
                return@Runnable
            }
            signals.remove(pkg)
            Log.i("VoIP call notification ended app=" + pkg)
            CallMonitor.onNotification(this, pkg, pkg == dialer(), "IDLE", null)
        }
        expiry[pkg] = task
        handler.postDelayed(task, 2_500L)
    }

    private fun dialer(): String? =
        runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()

    override fun onListenerConnected() {
        Log.i("Call notification metadata listener connected")
        signals.clear()
        expiry.values.forEach(handler::removeCallbacks)
        expiry.clear()
        runCatching { activeNotifications.orEmpty().forEach(::onNotificationPosted) }
        scheduleWatchdog()
    }

    override fun onListenerDisconnected() {
        Log.w("Call notification metadata listener disconnected")
        // Listener reconnection should not end a real call or discard its last caller.
        handler.removeCallbacks(watchdog)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val phone = pkg == dialer()
        if (!phone && pkg !in VOIP_PACKAGES) return
        val n = sbn.notification ?: return
        val state = stateOf(n)
        if (state == null) {
            // A former call notification may be rewritten as an ordinary
            // notification with the same key; do not keep its old ACTIVE entry.
            onNotificationRemoved(sbn)
            return
        }
        expiry.remove(pkg)?.let(handler::removeCallbacks)
        val map = signals.getOrPut(pkg) { mutableMapOf() }
        map[sbn.key] = Signal(state, CallMetadataResolver.fromNotification(this, n),
            directionOf(n, state))
        publish(pkg, phone)
        if (!phone) scheduleWatchdog()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val remaining = signals[pkg] ?: return
        remaining.remove(sbn.key)
        if (remaining.isNotEmpty()) {
            publish(pkg, pkg == dialer())
        } else {
            // Short grace window for WhatsApp's ringing -> answered key switch.
            scheduleCallEnd(pkg)
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
        val known = values.map { it.direction }
            .filter { it == CallDirection.INCOMING || it == CallDirection.OUTGOING }
            .distinct()
        CallMonitor.onNotification(this, pkg, phone, status, metadata,
            if (known.size == 1) known.single() else CallDirection.UNKNOWN)
    }

    private fun directionOf(n: Notification, state: String): CallDirection =
        VoipNotificationDirection.fromSignal(
            n.extras?.getInt(EXTRA_CALL_TYPE, 0) ?: 0,
            state,
            n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )

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
        handler.removeCallbacks(watchdog)
        super.onDestroy()
    }
}
