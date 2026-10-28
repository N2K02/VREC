package com.vrec.app.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vrec.app.MainActivity
import com.vrec.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/** A visible, dismissible warning when capture or import cannot complete. */
object RecordingFailureNotice {
    private const val CHANNEL = "vrec_recording_failures"
    private const val NOTIFICATION_ID = 8602
    private const val HELPER_START_FAILURE =
        "Recording helper could not start after ADB authorization. Calls will not be captured until recovery succeeds."
    val warning = MutableStateFlow<String?>(null)
    @Volatile private var persistent = false

    fun warn(context: Context, explanation: String, keepUntilDismissed: Boolean = false) {
        if (persistent && !keepUntilDismissed) return
        // Notifications never include call identity, recording paths or raw exception messages.
        val detail = explanation.take(240)
        if (warning.value == detail) return
        persistent = keepUntilDismissed
        warning.value = detail
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Recording problems", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = PendingIntent.getActivity(context, 8602,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("VRec recording warning")
                .setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build())
        }
    }

    fun helperStartFailed(context: Context) = warn(context, HELPER_START_FAILURE)

    /** Clear only the resolved startup warning; preserve failed imports and call warnings. */
    fun clearAfterHelperRecovered(context: Context) {
        if (warning.value == HELPER_START_FAILURE) dismiss(context)
    }

    fun dismiss(context: Context) {
        persistent = false
        warning.value = null
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    fun clearAfterCaptureStarted(context: Context) {
        if (warning.value != null && !persistent) dismiss(context)
    }
}
