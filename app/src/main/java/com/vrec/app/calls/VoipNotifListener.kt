package com.vrec.app.calls

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.vrec.app.Log

/**
 * Optional VoIP detection. Android can't give an app the audio of a WhatsApp/Telegram/Signal call
 * directly, so VREC records these the same way it records a SIM call where needed: with the
 * loudspeaker fallback. This listener only tells [CallMonitor] when such a call is active, read
 * from the app's own call notification. Requires user-granted Notification access, and is used
 * only when "Record VoIP calls" is turned on.
 */
class VoipNotifListener : NotificationListenerService() {

    companion object {
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            return flat.contains(ComponentName(context, VoipNotifListener::class.java).flattenToString())
        }

        /** Apps whose calls VREC will try to record. */
        private val VOIP_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.viber.voip", "com.skype.raider",
            "com.google.android.apps.tachyon", // Google Meet / Duo
        )

        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_INCOMING = 1

        private fun isCallStyle(n: Notification): Boolean {
            val ex = n.extras ?: return false
            return ex.containsKey(EXTRA_CALL_TYPE) ||
                ex.getString(Notification.EXTRA_TEMPLATE)?.endsWith("\$CallStyle") == true
        }

        /** True when the notification shows a call that is actually connected (not just ringing). */
        private fun isActiveCall(n: Notification): Boolean {
            val ex = n.extras ?: return false
            if (ex.getInt(EXTRA_CALL_TYPE, 0) == CALL_TYPE_ONGOING) return true
            // Fallback: an ongoing call-category notification that isn't the incoming ring.
            return n.category == Notification.CATEGORY_CALL &&
                ex.getInt(EXTRA_CALL_TYPE, 0) != CALL_TYPE_INCOMING &&
                (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        }
    }

    /** Keys of notifications currently showing an active call, per package. */
    private val activeKeys = HashMap<String, MutableSet<String>>()

    override fun onListenerConnected() {
        Log.i("VoIP notification access active")
        activeKeys.clear()
        runCatching { activeNotifications.orEmpty().forEach(::onNotificationPosted) }
    }

    override fun onListenerDisconnected() {
        activeKeys.clear()
        Log.i("VoIP notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg !in VOIP_PACKAGES) return
        val n = sbn.notification ?: return
        if (!isCallStyle(n) && n.category != Notification.CATEGORY_CALL) return

        val active = isActiveCall(n)
        val keys = activeKeys.getOrPut(pkg) { mutableSetOf() }
        val wasActive = keys.isNotEmpty()
        if (active) keys.add(sbn.key) else keys.remove(sbn.key)

        if (active && keys.isNotEmpty()) {
            Log.i("VoIP call signal: ${appLabel(pkg)}; type=${n.extras?.getInt(EXTRA_CALL_TYPE, 0)}; callerPresent=${callerName(n) != null}; actions=${n.actions?.size ?: 0}")
            CallMonitor.onVoipState(this, appLabel(pkg), callerName(n), active = true)
        } else if (wasActive && keys.isEmpty()) {
            CallMonitor.onVoipState(this, appLabel(pkg), null, active = false)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val keys = activeKeys[pkg] ?: return
        if (keys.remove(sbn.key) && keys.isEmpty()) {
            CallMonitor.onVoipState(this, appLabel(pkg), null, active = false)
        }
    }

    private fun callerName(n: Notification): String? {
        val extras = n.extras ?: return null
        val person = if (Build.VERSION.SDK_INT >= 33)
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        else if (Build.VERSION.SDK_INT >= 28) {
            @Suppress("DEPRECATION")
            extras.getParcelable<android.app.Person>(Notification.EXTRA_CALL_PERSON)
        } else null
        val candidates = listOf(
            person?.name?.toString(),
            if (Build.VERSION.SDK_INT >= 28) extras.getParcelableArrayList<android.app.Person>(Notification.EXTRA_PEOPLE_LIST)?.firstOrNull()?.name?.toString() else null,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
        return candidates.firstOrNull { value ->
            !value.isNullOrBlank() && value.length <= 100 &&
                !value.equals("Ongoing call", true) &&
                !value.equals("Incoming call", true) &&
                !value.equals("Call in progress", true)
        }?.trim()
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
